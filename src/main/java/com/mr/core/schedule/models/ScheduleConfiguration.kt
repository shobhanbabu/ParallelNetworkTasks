package com.mr.core.schedule.models

import com.mr.core.schedule.NetworkTaskScheduler

data class ScheduleConfiguration(
    val schedulerID: String,
    val maxWorkersThreshold: Int = NetworkTaskScheduler.DEFAULT_MAX_WORKERS_THRESHOLD,
    val maxRetryThreshold: Int = NetworkTaskScheduler.DEFAULT_MAX_RETRY_THRESHOLD,
    val schedulerDefaultTag: String = NetworkTaskScheduler.DEFAULT_TAG_SCHEDULE,
)