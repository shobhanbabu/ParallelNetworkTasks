package com.mr.core.schedule.util

import android.util.Log
import com.mr.core.schedule.BuildConfig

object BatchProcessUtility {
    private const val DEFAULT_BATCH_SIZE = 250

    suspend fun <T> startBatchProcessing(
        items: List<T>,
        batchLimit: Int = DEFAULT_BATCH_SIZE,
        isItemExcluded: suspend (item: T) -> Boolean,
        isBatchProcessCancelled: suspend () -> Boolean,
        processNextBatch: suspend (items: List<T>, firstBatch: Boolean, lastBatch: Boolean) -> Unit
    ) {
        if (BuildConfig.DEBUG) Log.e("BATCH PROCESS", "Batch process started..")

        var isFirstBatch = true
        val time = System.currentTimeMillis()
        var tempList = mutableListOf<T>()
        var insertionCount = 0
        items.forEach {
            if (isBatchProcessCancelled()) return@forEach

            if (!isItemExcluded(it)) {
                tempList.add(it)
                insertionCount++
                if (tempList.size % batchLimit == 0) {
                    processNextBatch(tempList, isFirstBatch, false)
                    if (isFirstBatch && BuildConfig.DEBUG)
                        Log.e("BATCH PROCESS", "On First batch completed")
                    isFirstBatch = false
                    tempList = mutableListOf()
                }
            }
        }
        if (!isBatchProcessCancelled()) {
            val newTemp = mutableListOf<T>()
            tempList.forEach {
                if (!isItemExcluded(it)) {
                    newTemp.add(it)
                }
            }
            processNextBatch(newTemp, isFirstBatch, true)
        }
        val timeForBatchProcess = System.currentTimeMillis() - time
        if (BuildConfig.DEBUG)
            Log.e(
                "BATCH PROCESS",
                "Duration for processing ${items.size} items - $timeForBatchProcess millis"
            )
    }
}