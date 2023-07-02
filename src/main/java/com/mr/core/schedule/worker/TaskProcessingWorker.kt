package com.mr.core.schedule.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mr.core.schedule.NetworkTaskScheduler
import com.mr.core.schedule.enums.TaskResult
import com.mr.core.schedule.enums.TaskStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class TaskProcessingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private lateinit var schedulingManager: NetworkTaskScheduler

    override suspend fun doWork(): Result {
        try {
            val id = inputData.getString(NetworkTaskScheduler.KEY_TASK_ID)
            val schedulerID = inputData.getString(NetworkTaskScheduler.KEY_SCHEDULER_ID)
                ?: throw RuntimeException("Scheduler id is not mentioned.")

            schedulingManager = NetworkTaskScheduler.getInstance(schedulerID)
                ?: throw RuntimeException("Scheduler is not attached for: $schedulerID")

            val scheduledTask = id?.let { schedulingManager.dataSource.getTask(it) }

            scheduledTask?.let { task ->
                val preProcessingParams =
                    schedulingManager.taskProcessingHelper.preProcessing(task.taskID)

                setNotification(preProcessingParams, task.taskStatus)

                //Update status to in progress
                task.taskStatus = TaskStatus.IN_PROGRESS
                schedulingManager.dataSource.updateTaskInfo(
                    task.taskID, task.taskStatus, task.retryCount
                )

                setNotification(preProcessingParams, task.taskStatus)

                //start processing the task
                val result = schedulingManager.taskProcessingHelper.processTask(
                    preProcessingParams = preProcessingParams,
                    isCancelled = { isStopped },
                    progress = {
                        notifyProgress(task.taskID, it)
                    }
                ) {
                    schedulingManager.cancelAll()
                }
                when (result) {
                    TaskResult.SUCCESS -> {
                        task.taskStatus = TaskStatus.COMPLETED
                    }
                    TaskResult.FAIL -> {
                        if (task.retryCount == schedulingManager.configuration.maxRetryThreshold) {
                            task.taskStatus = TaskStatus.FAILED
                        } else {
                            task.taskStatus = TaskStatus.RETRY
                            task.retryCount += 1
                        }
                    }
                    TaskResult.PAUSED -> {
                        task.taskStatus = TaskStatus.PAUSED
                    }
                    TaskResult.ALREADY_COMPLETED -> {
                        task.taskStatus = TaskStatus.ALREADY_EXISTS
                    }
                }

                if (
                    schedulingManager.dataSource.updateTaskInfo(
                        task.taskID, task.taskStatus, task.retryCount
                    )
                ) {
                    setNotification(preProcessingParams, task.taskStatus)
                    schedulingManager.taskProcessingHelper.onTaskStatusChange(task)
                }
            }
            withContext(Dispatchers.Default) {
                schedulingManager.enqueueNextTask()
            }
        } catch (e: Exception) {
            Log.e("Shobhan", "doWork: ${e.stackTraceToString()}")
        }

        return Result.success()
    }

    private suspend fun setNotification(
        params: Map<String, Any>,
        taskStatus: TaskStatus
    ) {
        try {
            val parameters = mutableMapOf<String, Any>()
            parameters.putAll(params)
            parameters[NetworkTaskScheduler.PARAM_TASK_STATUS] = taskStatus
            setForeground(getForegroundInfo(parameters))
        } catch (e: Exception) {
            Log.e("Shobhan", "setNotification: ${e.stackTraceToString()}()")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return getForegroundInfo(mutableMapOf())
    }

    private suspend fun getForegroundInfo(preProcessingParams: MutableMap<String, Any>): ForegroundInfo {
        val taskID = inputData.getString(NetworkTaskScheduler.KEY_TASK_ID)!!
        preProcessingParams[NetworkTaskScheduler.KEY_TASK_ID] = taskID
        return schedulingManager.notificationHelper.getForegroundNotification(preProcessingParams)
    }

    private suspend fun notifyProgress(taskID: String, pairs: List<Pair<String, Any>>) {
        setProgress(
            workDataOf(
                Pair(NetworkTaskScheduler.KEY_TASK_ID, taskID),
                *pairs.toTypedArray()
            )
        )
    }
}