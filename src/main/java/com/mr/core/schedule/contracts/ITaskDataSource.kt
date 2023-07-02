package com.mr.core.schedule.contracts

import com.mr.core.schedule.enums.TaskStatus
import com.mr.core.schedule.models.Task
import com.mr.core.schedule.models.TaskInfo

interface ITaskDataSource {
    suspend fun getNextAvailableTask(): Task?

    suspend fun getTask(taskID: String): Task?

    suspend fun updateTaskInfo(taskID: String, taskStatus: TaskStatus, retryCount: Int): Boolean

    suspend fun addTask(task: TaskInfo)

    suspend fun addTasks(taskInfoList: List<TaskInfo>)

    suspend fun deleteTask(taskID: String)

    suspend fun deleteTasksWithIDs(taskIDs: Set<String>)

    suspend fun deleteAllTasks()

    suspend fun deleteAllTasks(tags: Set<String>)

    suspend fun hasTasks(): Boolean

    suspend fun updateTaskStatus(vararg fromStatus: TaskStatus, toStatus: TaskStatus)
}