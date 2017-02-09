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

package com.hortonworks.spark.cloud.commit

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.{Date, Locale}

import scala.collection.mutable

import com.google.common.base.Preconditions
import com.hortonworks.spark.cloud.CloudLogging
import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.{JobContext, JobStatus, OutputCommitter, TaskAttemptContext, TaskAttemptID, TaskID, TaskType}
import org.apache.hadoop.fs.s3a._
import org.apache.hadoop.fs.s3a.Constants._
import org.apache.hadoop.fs.s3a.commit._
import org.apache.hadoop.mapred.{JobConf, JobID}
import org.apache.hadoop.mapreduce.lib.output._
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce.{TaskAttemptContext => MapReduceTaskAttemptContext}
import org.apache.hadoop.mapreduce.{OutputCommitter => MapReduceOutputCommitter}

import org.apache.spark.executor.CommitDeniedException
import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.internal.io.FileCommitProtocol

class SparkS3GuardCommitter(jobId: String, dest: String)
  extends FileCommitProtocol
    with Serializable
    with CloudLogging {

  import FileCommitProtocol._

  /** Path is actually Serializable in Hadoop 3, but left alone for ease of
   * backporting this committer. */
  @transient private var destPath = new Path(dest)
  @transient private var workPath: Path = null
  @transient private var destFS: S3AFileSystem = null
  @transient private var commitActions: FileCommitActions = null
  @transient private var committer: S3AOutputCommitter = _

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
  private def absPathStagingDir: Path = new Path(dest, "_temporary-" + jobId)

  /**
   * Get the FS of the specific conf and destPath as an S3aFS
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
    conf.set("mapreduce.pathoutputcommitter.factory.class",
        S3AOutputCommitterFactory.NAME)


    val taskAttemptContext = new TaskAttemptContextImpl(conf, taskAttemptId)
    committer = setupCommitter(taskAttemptContext)
    committer.setupJob(jobContext)
  }

  override def commitJob(jobContext: JobContext, taskCommits: Seq[TaskCommitMessage]): Unit = {

    logInfo(s"Commtting to $dest")
    committer.commitJob(jobContext)

    val filesToMove = taskCommits.map(_.obj.asInstanceOf[Map[String, String]])
      .foldLeft(Map[String, String]())(_ ++ _)
    logInfo(s"Committing ${filesToMove.size} files by COPY")
    logDebug(s"Committing files staged for absolute locations $filesToMove")
    val fs = getS3aFS(jobContext)
    for ((src, dst) <- filesToMove) {
      fs.rename(new Path(src), new Path(dst))
    }
    fs.delete(absPathStagingDir, true)

  }

  override def abortJob(jobContext: JobContext): Unit = {
    committer.abortJob(jobContext, JobStatus.State.FAILED)
    val fs = getS3aFS(jobContext)
    fs.delete(absPathStagingDir, true)
  }

  override def setupTask(taskContext: TaskAttemptContext): Unit = {
    committer = setupCommitter(taskContext)
    committer.setupTask(taskContext)

    addedAbsPathFiles = mutable.Map[String, String]()
  }

  override def newTaskTempFile(
      taskContext: TaskAttemptContext,
      dir: Option[String],
      ext: String): String = {

  }

  override def newTaskTempFileAbsPath(
      taskContext: TaskAttemptContext,
      absoluteDir: String,
      ext: String): String = {

  }

  override def abortTask(taskContext: TaskAttemptContext): Unit = {
    committer.abortTask(taskContext)
    // best effort cleanup of other staged files
    val fS = getS3aFS(taskContext)
    for ((src, _) <- addedAbsPathFiles) {
      fS.delete(new Path(src), false)
    }
  }

  protected def setupCommitter(context: TaskAttemptContext): S3AOutputCommitter = {
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
      committer: MapReduceOutputCommitter,
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
      val shouldCoordinateWithDriver: Boolean = {
        val sparkConf = SparkEnv.get.conf
        // We only need to coordinate with the driver if there are concurrent task attempts.
        // Note that this could happen even when speculation is not enabled (e.g. see SPARK-8029).
        // This (undocumented) setting is an escape-hatch in case the commit code introduces bugs.
        sparkConf.getBoolean("spark.hadoop.outputCommitCoordination.enabled", defaultValue = true)
      }

      if (shouldCoordinateWithDriver) {
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
        // Speculation is disabled or a user has chosen to manually bypass the commit coordination
        performCommit()
      }
    } else {
      // Some other attempt committed the output, so we do nothing and signal success
      logInfo(s"No need to commit output of task because needsTaskCommit=false: $mrTaskAttemptID")
    }
  }

}
