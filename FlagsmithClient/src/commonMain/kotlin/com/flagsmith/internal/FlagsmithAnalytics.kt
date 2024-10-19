package com.flagsmith.internal

import com.flagsmith.internal.http.FlagsmithApi
import kotlinx.coroutines.CoroutineScope

internal interface FlagsmithAnalytics {
    fun trackEvent(flagName: String)

    interface Factory {
        fun create(
            flagsmithApi: FlagsmithApi,
            flushPeriod: Int,
            coroutineScope: CoroutineScope
        ): FlagsmithAnalytics
    }
}
