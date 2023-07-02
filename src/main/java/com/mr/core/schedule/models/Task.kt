package com.mr.core.schedule.models

import com.mr.core.schedule.enums.TaskStatus

data class Task(
    val taskID: String,
    val tags: List<String>,
    var taskStatus: TaskStatus = TaskStatus.ENQUEUE,
    var retryCount: Int = 0
)
