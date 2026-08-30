package com.flagsmith.internal

import com.flagsmith.internal.http.FlagsmithApi
import kotlinx.coroutines.CoroutineScope

internal interface FlagsmithAnalytics {
    fun trackEvent(flagName: String)

    /**
     * Stops periodic flushing and releases resources. Called from [com.flagsmith.Flagsmith.close].
     */
    fun stop()

    interface Factory {
        fun create(
            flagsmithApi: FlagsmithApi,
            flushPeriod: Int,
            coroutineScope: CoroutineScope
        ): FlagsmithAnalytics
    }
}
