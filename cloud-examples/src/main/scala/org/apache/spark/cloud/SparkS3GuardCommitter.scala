/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.cloud

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.{Date, Locale}

import scala.collection.mutable

import com.google.common.base.Preconditions
import com.hortonworks.spark.cloud.{CloudLogging, TimeOperations}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.s3a.Constants._
import org.apache.hadoop.fs.s3a._
import org.apache.hadoop.fs.s3a.commit._
import org.apache.hadoop.mapred.{JobConf, JobID}
import org.apache.hadoop.mapreduce.lib.output._
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce.{JobContext, JobStatus, TaskAttemptContext, TaskAttemptID, TaskID, TaskType, TaskAttemptContext => MapReduceTaskAttemptContext}

import org.apache.spark.executor.CommitDeniedException
import org.apache.spark.internal.io.FileCommitProtocol
import org.apache.spark.{SparkEnv, TaskContext}

class SparkS3GuardCommitter(jobId: String, dest: String)
  extends FileCommitProtocol
    with Serializable
    with TimeOperations
    with CloudLogging {

  import FileCommitProtocol._

  /** Path is actually Serializable in Hadoop 3, but left alone for ease of
   * backporting this committer. */
  @transient private val destPath = new Path(dest)
  @transient private var workPath: Path = _
  @transient private var destFS: S3AFileSystem = _
  @transient private var commitActions: FileCommitActions = _
  @transient private var committer: Option[S3AOutputCommitter] = None

  /**
   * Tracks files staged by this task for absolute output paths. These outputs are not managed by
   * the Hadoop OutputCommitter, so we must move these to their final locations on job commit.
   *
   * The mapping is from the temp output path to the final desired output path of the file.
   */
  @transient private var addedAbsPathFiles: mutable.Map[String, String] = null

  /**
   * The staging directory for all files committed with absolute output paths.
   * This is the inefficient commit mechanism to be stringly discouraged
   */
  private def absPathStagingDir: Path = new Path(dest, s"_temporary-$jobId")

  /**
   * Get the FS of the specific conf and destPath as an S3aFS.
   *
   * @param jobContext job context
   * @return the FS instance
   */
  private def getS3aFS(jobContext: JobContext): S3AFileSystem = {
    getS3aFS(jobContext.getConfiguration)
  }

  private def getS3aFS(conf: Configuration): S3AFileSystem = {
    val fs = destPath.getFileSystem(conf)
    Preconditions.checkState(fs.isInstanceOf[S3AFileSystem],
      s"Filesystem of $destPath is not an S3AFileSystem: $fs")
    fs.asInstanceOf[S3AFileSystem]
  }

  private val MR_COMMITTER = "mapreduce.pathoutputcommitter.factory.class"

  override def setupJob(jobContext: JobContext): Unit = {
    // Setup IDs
    val jobId = createJobID(new Date, 0)
    val taskId = new TaskID(jobId, TaskType.MAP, 0)
    val taskAttemptId = new TaskAttemptID(taskId, 0)

    // Set up the configuration object
    val conf = jobContext.getConfiguration
    conf.set("mapred.job.id", jobId.toString)
    conf.set("mapred.tip.id", taskAttemptId.getTaskID.toString)
    conf.set("mapred.task.id", taskAttemptId.toString)
    conf.setBoolean("mapred.task.is.map", true)
    conf.setInt("mapred.task.partition", 0)
    conf.setBoolean(FAST_UPLOAD, true)
    conf.set(FAST_UPLOAD_BUFFER, FAST_UPLOAD_BUFFER_ARRAY)
    conf.setBoolean(COMMITTER_ENABLED, true)
    // force in S3a committer

    val committerFactoryClass = conf.get(MR_COMMITTER)
    Preconditions.checkState(committerFactoryClass == S3AOutputCommitterFactory.NAME,
      "MR Committer factory class set in " + MR_COMMITTER +
     " is not the S3guard factory: " + S3AOutputCommitterFactory.NAME)

    val taskAttemptContext = new TaskAttemptContextImpl(conf, taskAttemptId)
    committer = createCommitter(taskAttemptContext)
  }

  override def commitJob(jobContext: JobContext, taskCommits: Seq[TaskCommitMessage]): Unit = {

    logInfo(s"Commtting to $dest")
    createCommitter(jobContext).commitJob(jobContext)

    val filesToMove = taskCommits.map(_.obj.asInstanceOf[Map[String, String]])
      .foldLeft(Map[String, String]())(_ ++ _)
    logInfo(s"Committing ${filesToMove.size} files by COPY")
    logDebug(s"Committing files staged for absolute locations $filesToMove")
    val fs = getS3aFS(jobContext)
    for ((src, dst) <- filesToMove) {
      val srcPath = new Path(src)
      val destPath = new Path(dst)
      duration(s"Rename $srcPath to $destPath") {
        fs.rename(srcPath, destPath)
      }
    }
    deleteStagingDir(fs)
  }

  def deleteStagingDir(fs: S3AFileSystem): Unit = {
    duration(s"Delete Staging Dir $absPathStagingDir") {
      fs.delete(absPathStagingDir, true)
    }
  }

  override def abortJob(jobContext: JobContext): Unit = {
    committer.abortJob(jobContext, JobStatus.State.FAILED)
    deleteStagingDir(getS3aFS(jobContext))
  }

  override def setupTask(taskContext: TaskAttemptContext): Unit = {
    duration(s"Setting up task ${taskContext.getTaskAttemptID}") {
      committer = createCommitter(taskContext)
      committer.setupTask(taskContext)
      addedAbsPathFiles = mutable.Map[String, String]()
    }
  }

  override def newTaskTempFile(
      taskContext: TaskAttemptContext,
      dir: Option[String],
      ext: String): String = {

    workPath

  }

  override def newTaskTempFileAbsPath(
      taskContext: TaskAttemptContext,
      absoluteDir: String,
      ext: String): String = {

  }

  /**
   * Abort a task.
   * @param taskContext task
   */
  override def abortTask(taskContext: TaskAttemptContext): Unit = {
    duration(s"Aborting task ${taskContext.getTaskAttemptID}"){
      committer(taskContext).abortTask(taskContext)
      // best effort cleanup of other staged files
      val fS = getS3aFS(taskContext)
      for ((src, _) <- addedAbsPathFiles) {
        fS.delete(new Path(src), false)
      }
    }
  }

  /**
   * Creates an S3A output committer.
   * @param context task context
   * @return the committer
   * @throws IllegalStateException if the committer is of a different type.
   */
  protected def createCommitter(context: TaskAttemptContext): S3AOutputCommitter = {
    val formatClass = context.getOutputFormatClass
    val format = formatClass.newInstance()
    Preconditions.checkState(format.isInstanceOf[FileOutputFormat],
      s"Unsupported file output format $formatClass")
    val output = format.asInstanceOf[FileOutputFormat]
    val committer = output.getOutputCommitter(context)
    Preconditions.checkState(committer.isInstanceOf[S3AOutputCommitter],
      s"Format class $formatClass has an output committer" +
        s" which is not an S3AOutputCommitter: $committer")
    committer.asInstanceOf[S3AOutputCommitter]
  }

  /**
   * Creates an S3A output committer.
   * @param context task context
   * @return the committer
   * @throws IllegalStateException if the committer is of a different type.
   */
  protected def createCommitter(context: JobContext): S3AOutputCommitter = {
    val committerFactory = new S3AOutputCommitterFactory()
    committerFactory.createOutputCommitter()
    val formatClass = context.getOutputFormatClass
    val format = formatClass.newInstance()
    Preconditions.checkState(format.isInstanceOf[FileOutputFormat],
      s"Unsupported file output format $formatClass")
    val output = format.asInstanceOf[FileOutputFormat]
    val committer = output.getOutputCommitter(context)
    Preconditions.checkState(committer.isInstanceOf[S3AOutputCommitter],
      s"Format class $formatClass has an output committer" +
        s" which is not an S3AOutputCommitter: $committer")
    committer.asInstanceOf[S3AOutputCommitter]
  }

  /**
   * Get the committer. This either returns the existing one, or creates a new one
   * @param context
   * @return
   */
  protected def committer(context: TaskAttemptContext): S3AOutputCommitter = {
    committer match {
      case Some(c) => c
      case None =>
        committer = Some(createCommitter(context))
        committer.get
    }
  }

  def createJobID(time: Date, id: Int): JobID = {
    val jobtrackerID = createJobTrackerID(time)
    new JobID(jobtrackerID, id)
  }

  def createJobTrackerID(time: Date): String = {
    new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(time)
  }

  def createPathFromString(path: String, conf: JobConf): Path = {
    if (path == null) {
      throw new IllegalArgumentException("Output path is null")
    }
    val outputPath = new Path(path)
    val fs = outputPath.getFileSystem(conf)
    if (fs == null) {
      throw new IllegalArgumentException("Incorrectly formatted output path")
    }
    outputPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
  }

  override def commitTask(taskContext: TaskAttemptContext): TaskCommitMessage = {
    val attemptId = taskContext.getTaskAttemptID
    commitTask(
      committer, taskContext, attemptId.getJobID.getId, attemptId.getTaskID.getId)
    new TaskCommitMessage(addedAbsPathFiles.toMap)
  }

  /**
   * Commits a task output.  Before committing the task output, we need to know whether some other
   * task attempt might be racing to commit the same output partition. Therefore, coordinate with
   * the driver in order to determine whether this attempt can commit (please see SPARK-4879 for
   * details).
   *
   * Output commit coordinator is only used when `spark.hadoop.outputCommitCoordination.enabled`
   * is set to true (which is the default).
   */
  def commitTask(
      committer: S3AOutputCommitter,
      mrTaskContext: MapReduceTaskAttemptContext,
      jobId: Int,
      splitId: Int): Unit = {

    val mrTaskAttemptID = mrTaskContext.getTaskAttemptID

    // Called after we have decided to commit
    def performCommit(): Unit = {
      try {
        committer.commitTask(mrTaskContext)
        logInfo(s"$mrTaskAttemptID: Committed")
      } catch {
        case cause: IOException =>
          logError(s"Error committing the output of task: $mrTaskAttemptID", cause)
          committer.abortTask(mrTaskContext)
          throw cause
      }
    }

    // First, check whether the task's output has already been committed by some other attempt
    if (committer.needsTaskCommit(mrTaskContext)) {
      val outputCommitCoordinator = SparkEnv.get.outputCommitCoordinator
      val taskAttemptNumber = TaskContext.get().attemptNumber()
      val canCommit = outputCommitCoordinator.canCommit(jobId, splitId, taskAttemptNumber)

      if (canCommit) {
        performCommit()
      } else {
        val message =
          s"$mrTaskAttemptID: Not committed because the driver did not authorize commit"
        logInfo(message)
        // We need to abort the task so that the driver can reschedule new attempts, if necessary
        committer.abortTask(mrTaskContext)
        throw new CommitDeniedException(message, jobId, splitId, taskAttemptNumber)
      }
    } else {
      // Some other attempt committed the output, so we do nothing and signal success
      logInfo(s"No need to commit output of task because needsTaskCommit=false: $mrTaskAttemptID")
    }
  }

}


