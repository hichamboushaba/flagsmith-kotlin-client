package com.flagsmith

import com.flagsmith.entities.Flag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

expect fun Flagsmith.Companion.create(
    environmentKey: String,
    baseUrl: String = "https://edge.api.flagsmith.com/api/v1/",
    eventSourceBaseUrl: String = "https://realtime.flagsmith.com/",
    enableAnalytics: Boolean = DEFAULT_ENABLE_ANALYTICS,
    enableRealtimeUpdates: Boolean = false,
    analyticsFlushPeriod: Int = DEFAULT_ANALYTICS_FLUSH_PERIOD_SECONDS,
    cacheConfig: FlagsmithCacheConfig = FlagsmithCacheConfig(),
    defaultFlags: List<Flag> = emptyList(),
    requestTimeoutSeconds: Long = 4L,
    readTimeoutSeconds: Long = 6L,
    writeTimeoutSeconds: Long = 6L,
    lastFlagFetchTime: Double = 0.0, // from FlagsmithEventTimeTracker
    sseUpdatesScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
): Flagsmith
