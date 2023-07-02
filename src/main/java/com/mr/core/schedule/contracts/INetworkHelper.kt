package com.mr.core.schedule.contracts

interface INetworkHelper {
    fun isWifi(): Boolean

    fun isCellular(): Boolean

    fun isConnected(): Boolean

    fun useCellular(): Boolean
}
