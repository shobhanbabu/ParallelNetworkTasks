package com.mr.core.schedule.contracts

import com.mr.core.schedule.enums.TaskResult
import com.mr.core.schedule.models.Task

interface ITaskProcessingHelper {
    suspend fun preProcessing(taskID: String): Map<String, Any>

    suspend fun processTask(
        preProcessingParams: Map<String, Any>,
        isCancelled: () -> Boolean,
        progress: suspend (params: List<Pair<String, Any>>) -> Unit,
        cancelAllTasks: suspend () -> Unit
    ): TaskResult

    suspend fun onTaskStatusChange(task: Task)

    suspend fun onPreCancelAll()

    suspend fun onPostCancelAll()

    suspend fun onPreCancel(tags: Set<String>)

    suspend fun onPostCancel(tags: Set<String>)
}