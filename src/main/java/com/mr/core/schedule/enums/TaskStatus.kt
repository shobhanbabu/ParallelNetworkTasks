package com.mr.core.schedule.enums

enum class TaskStatus {
    /**
     * New task inserted into the db
     */
    ENQUEUE,

    /**
     * Worker is scheduled for task
     */
    READY,

    /**
     * Task is running
     */
    IN_PROGRESS,

    /**
     * TASK is completed
     */
    COMPLETED,

    /**
     * Task is failed
     */
    FAILED,

    /**
     * Retrying
     */
    RETRY,

    /**
     * Task already completed
     */
    ALREADY_EXISTS,

    /**
     * Paused due to network failure
     */
    PAUSED
}