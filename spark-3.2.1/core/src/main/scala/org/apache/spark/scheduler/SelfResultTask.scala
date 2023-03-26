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

package org.apache.spark.scheduler

import java.io._
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.util.Properties

import org.apache.spark._
import org.apache.spark.rdd.RDD

private[spark] class SelfResultTask[T, U](
                                       stageId: Int,
                                       stageAttemptId: Int,
                                       taskBinary: Array[Byte],
                                       partition: Partition,
                                       locs: Seq[TaskLocation],
                                       val outputId: Int,
                                       localProperties: Properties,
                                       serializedTaskMetrics: Array[Byte],
                                       jobId: Option[Int] = None,
                                       appId: Option[String] = None,
                                       appAttemptId: Option[String] = None,
                                       isBarrier: Boolean = false)
  extends Task[U](stageId, stageAttemptId, partition.index, localProperties, serializedTaskMetrics,
    jobId, appId, appAttemptId, isBarrier)
    with Serializable {

  @transient private[this] val preferredLocs: Seq[TaskLocation] = {
    if (locs == null) Nil else locs.distinct
  }

  override def runTask(context: TaskContext): U = {
    // Deserialize the RDD and the func using the broadcast variables.
    val threadMXBean = ManagementFactory.getThreadMXBean
    val deserializeStartTimeNs = System.nanoTime()
    val deserializeStartCpuTime = if (threadMXBean.isCurrentThreadCpuTimeSupported) {
      threadMXBean.getCurrentThreadCpuTime
    } else 0L
    val ser = SparkEnv.get.closureSerializer.newInstance()
    val start0 = System.nanoTime()
    val (rdd, func) = ser.deserialize[(RDD[T], (TaskContext, Iterator[T]) => U)](
      ByteBuffer.wrap(taskBinary), Thread.currentThread.getContextClassLoader)
    _executorDeserializeTimeNs = System.nanoTime() - deserializeStartTimeNs
    _executorDeserializeCpuTime = if (threadMXBean.isCurrentThreadCpuTimeSupported) {
      threadMXBean.getCurrentThreadCpuTime - deserializeStartCpuTime
    } else 0L
    val end0 = System.nanoTime()
    val used0 = end0 - start0
    print("deserialize in runTask takes time = " + used0)

    func(context, rdd.iterator(partition, context))
  }

  // This is only callable on the driver side.
  override def preferredLocations: Seq[TaskLocation] = preferredLocs

  override def toString: String = "ResultTask(" + stageId + ", " + partitionId + ")"
}

