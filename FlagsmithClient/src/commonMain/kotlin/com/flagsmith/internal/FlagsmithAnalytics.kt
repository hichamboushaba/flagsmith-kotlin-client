package com.flagsmith.internal

import com.flagsmith.internal.http.FlagsmithApi

internal interface FlagsmithAnalytics {
    fun trackEvent(flagName: String)

    interface Storage {
        fun storeEvents(events: Map<String, Int?>)
        fun getEvents(): Map<String, Int?>
    }

    interface Factory {
        fun create(storage: Storage, flagsmithApi: FlagsmithApi, flushPeriod: Int): FlagsmithAnalytics
    }
}
