package com.flagsmith

import com.flagsmith.entities.Flag
import kotlinx.coroutines.CoroutineScope

internal expect fun Flagsmith.Companion.create(
    environmentKey: String,
    baseUrl: String,
    eventSourceBaseUrl: String,
    enableAnalytics: Boolean,
    enableRealtimeUpdates: Boolean,
    analyticsFlushPeriod: Int,
    cacheConfig: FlagsmithCacheConfig,
    defaultFlags: List<Flag>,
    requestTimeoutSeconds: Long,
    readTimeoutSeconds: Long,
    writeTimeoutSeconds: Long,
    lastFlagFetchTime: Double,
    sseUpdatesScope: CoroutineScope,
): Flagsmith
