package com.mr.core.schedule

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.work.*
import com.mr.core.schedule.contracts.INetworkHelper
import com.mr.core.schedule.contracts.INotificationHelper
import com.mr.core.schedule.contracts.ITaskDataSource
import com.mr.core.schedule.contracts.ITaskProcessingHelper
import com.mr.core.schedule.enums.TaskStatus
import com.mr.core.schedule.models.ScheduleConfiguration
import com.mr.core.schedule.models.Task
import com.mr.core.schedule.models.TaskInfo
import com.mr.core.schedule.util.BatchProcessUtility
import com.mr.core.schedule.worker.TaskProcessingWorker
import java.util.concurrent.ExecutionException

class NetworkTaskScheduler constructor(
    private val appContext: Context,
    private val networkHelper: INetworkHelper,
    val dataSource: ITaskDataSource,
    val notificationHelper: INotificationHelper,
    val taskProcessingHelper: ITaskProcessingHelper,
    val configuration: ScheduleConfiguration,
) {
    companion object {
        private val schedulersMap = mutableMapOf<String, NetworkTaskScheduler>()
        fun getInstance(schedulerID: String): NetworkTaskScheduler? {
            return schedulersMap[schedulerID]
        }

        const val KEY_TASK_ID = "KEY_TASK_ID"
        const val KEY_SCHEDULER_ID = "KEY_SCHEDULER_ID"
        const val DEFAULT_MAX_WORKERS_THRESHOLD = 8
        const val DEFAULT_MAX_RETRY_THRESHOLD = 3
        const val DEFAULT_TAG_SCHEDULE = "DEFAULT_TAG_SCHEDULE"
        const val PARAM_TASK_STATUS = "PARAM_TASK_STATUS"
    }

    private var isBatchingInProgress = false
    private var isAllTasksCancelled = false
    private val cancelledTags = mutableSetOf<String>()

    init {
        schedulersMap[configuration.schedulerID] = this
    }

    /**
     * Task ID and it's progress params
     */
    fun getProgressParams() = getProgressParamsLivedata()

    /**
     * Task ID and it's progress params
     */
    fun getProgressParams(tag: String) = getProgressParamsLivedata(mutableListOf(tag))

    /**
     * Task ID and it's progress params
     */
    fun getProgressParams(tags: List<String>) = getProgressParamsLivedata(tags)

    private fun getProgressParamsLivedata(
        tagsList: List<String> = mutableListOf()
    ): LiveData<Map<String, Map<String, Any>>> {
        val tags = mutableListOf<String>()
        tags.add(configuration.schedulerDefaultTag)
        tags.addAll(tagsList)
        return Transformations.map(
            WorkManager.getInstance(appContext).getWorkInfosLiveData(
                WorkQuery.Builder.fromTags(tags).build()
            )
        ) { worksInfo ->
            worksInfo.filter {
                it.progress.keyValueMap.isNotEmpty()
            }.associate { info ->
                val params = info.progress.keyValueMap.filter {
                    it.key != null && it.key != KEY_TASK_ID
                }
                val taskID = info.progress.getString(KEY_TASK_ID)!!
                Pair(taskID, params)
            }
        }
    }

    suspend fun enqueueNextTask() {
        initMaxWorks()
    }

    suspend fun enqueueTasks(taskList: List<TaskInfo>, onFirstBatch: suspend () -> Unit) {
        scheduleTasks(taskList, onFirstBatch)
    }

    private suspend fun scheduleTasks(taskList: List<TaskInfo>, onFirstBatch: suspend () -> Unit) {
        isBatchingInProgress = true
        BatchProcessUtility.startBatchProcessing(
            items = taskList,
            isItemExcluded = this::isTaskCancelled,
            isBatchProcessCancelled = this::isAllTasksCancelled,
        ) { tasks: List<TaskInfo>, firstBatch: Boolean, lastBatch: Boolean ->
            dataSource.addTasks(tasks.map {
                it.task.taskStatus = TaskStatus.ENQUEUE
                it
            })
            if (firstBatch) {
                initMaxWorks()
                onFirstBatch()
            }
            if (lastBatch) {
                isBatchingInProgress = false
                isAllTasksCancelled = false
                clearCancelledTags()
            }
        }
    }

    private fun clearCancelledTags() = cancelledTags.clear()

    private fun isTaskCancelled(taskInfo: TaskInfo) =
        isAllTasksCancelled || cancelledTags.any { taskInfo.task.tags.contains(it) }

    private fun isAllTasksCancelled() = isAllTasksCancelled

    suspend fun cancelTask(taskID: String) {
        dataSource.deleteTask(taskID)
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelUniqueWork(taskID)
        enqueueNextTask()
    }

    suspend fun cancelTasksWithIds(taskIDs: Set<String>) {
        dataSource.deleteTasksWithIDs(taskIDs)
        taskIDs.forEach { taskID ->
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelUniqueWork(taskID)
        }
        enqueueNextTask()
    }

    suspend fun cancelTasks(tags: Set<String>) {
        taskProcessingHelper.onPreCancel(tags)
        if (isBatchingInProgress) {
            cancelledTags.addAll(tags)
        }
//        notificationHelper.onAllTasksCancel(tags)
        dataSource.deleteAllTasks(tags)
        tags.forEach { tag ->
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelAllWorkByTag(tag)
        }
        taskProcessingHelper.onPostCancel(tags)
        enqueueNextTask()
    }

    suspend fun cancelAll() {
        isAllTasksCancelled = true
        taskProcessingHelper.onPreCancelAll()
//        notificationHelper.onAllTasksCancel()
        dataSource.deleteAllTasks()
        if (!isBatchingInProgress) {
            isAllTasksCancelled = false
        }
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelAllWorkByTag(configuration.schedulerDefaultTag)
        taskProcessingHelper.onPostCancelAll()
    }

    private suspend fun initMaxWorks() {
        if (isAllTasksCancelled()) return

        val workManager = WorkManager.getInstance(appContext)
        workManager.pruneWork()
        //Fetching next enqueued file -> task status = ENQUEUED
        var task = dataSource.getNextAvailableTask()
        if (dataSource.hasTasks()) {
            notificationHelper.showNetworkStatusIfRequired(
                networkHelper.isConnected(),
                networkHelper.isWifi(),
                networkHelper.isCellular(),
                networkHelper.useCellular(),
            )
        }
        while (task != null && !isMaxWorkersRunning(workManager)) {
            task.taskStatus = TaskStatus.READY
            dataSource.updateTaskInfo(task.taskID, task.taskStatus, task.retryCount)
            scheduleUniqueWork(workManager, task)
            task = dataSource.getNextAvailableTask()
        }
    }

    private fun isMaxWorkersRunning(workManager: WorkManager): Boolean {
        val statuses = workManager.getWorkInfosByTag(configuration.schedulerDefaultTag)
        var count = 1
        return try {
            val workInfoList = statuses.get()
            for (workInfo in workInfoList) {
                val state = workInfo.state
                val running =
                    state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
                if (running) {
                    count++
                }
            }
            count > configuration.maxWorkersThreshold
        } catch (e: ExecutionException) {
            e.printStackTrace()
            false
        } catch (e: InterruptedException) {
            e.printStackTrace()
            false
        }
    }

    private fun scheduleUniqueWork(workManager: WorkManager, task: Task) {
        val workRequest = getTaskProcessingWorkRequest(task)

        val existingWorkPolicy = ExistingWorkPolicy.REPLACE

        workManager.beginUniqueWork(
            task.taskID,
            existingWorkPolicy,
            workRequest
        ).enqueue()
    }

    private fun getTaskProcessingWorkRequest(task: Task): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (networkHelper.isWifi() || networkHelper.useCellular()) NetworkType.CONNECTED
                else NetworkType.UNMETERED
            )
            .build()

        val builder = OneTimeWorkRequest.Builder(TaskProcessingWorker::class.java)
            .addTag(configuration.schedulerDefaultTag)
            .setInputData(
                workDataOf(
                    Pair(KEY_SCHEDULER_ID, configuration.schedulerID),
                    Pair(KEY_TASK_ID, task.taskID)
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints)

        task.tags.forEach { tag ->
            builder.addTag(tag)
        }

        return builder.build()
    }

    suspend fun onNetworkChange(cancelEnqueued: Boolean) {
        if (cancelEnqueued) {
            cancelEnqueuedWorkers()
        }
        val connected = networkHelper.isConnected()
        val wifi = networkHelper.isWifi()
        val useCellular = networkHelper.useCellular()

        if (connected && (wifi || useCellular)) {
            if (cancelEnqueued) {
                dataSource.updateTaskStatus(
                    TaskStatus.READY,
                    TaskStatus.PAUSED,
                    toStatus = TaskStatus.ENQUEUE
                )
            } else {
                dataSource.updateTaskStatus(
                    TaskStatus.PAUSED,
                    toStatus = TaskStatus.ENQUEUE
                )
            }
        } else {
            dataSource.updateTaskStatus(
                TaskStatus.IN_PROGRESS,
                TaskStatus.READY,
                TaskStatus.ENQUEUE,
                TaskStatus.RETRY,
                toStatus = TaskStatus.PAUSED
            )
            cancelAllWorkers()
        }
        enqueueNextTask()
    }

    private fun cancelAllWorkers() {
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelAllWorkByTag(configuration.schedulerDefaultTag)
    }

    private fun cancelEnqueuedWorkers() {
        val workManager = WorkManager.getInstance(appContext)
        val statuses = workManager.getWorkInfosByTag(configuration.schedulerDefaultTag)
        try {
            val workInfoList = statuses.get()
            for (workInfo in workInfoList) {
                val state = workInfo.state
                if (state == WorkInfo.State.ENQUEUED) {
                    workManager.cancelWorkById(workInfo.id)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}