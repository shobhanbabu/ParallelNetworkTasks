package com.mr.core.schedule.contracts

import androidx.work.ForegroundInfo

interface INotificationHelper {
    suspend fun getForegroundNotification(params: Map<String, Any>): ForegroundInfo

    suspend fun showNetworkStatusIfRequired(
        isConnected: Boolean,
        isWifi: Boolean,
        isCellular: Boolean,
        useCellular: Boolean
    )

    fun cancelAllNotifications()
}
