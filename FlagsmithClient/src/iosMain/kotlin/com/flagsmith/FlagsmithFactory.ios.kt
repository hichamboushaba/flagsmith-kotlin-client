package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.internal.http.KtorFlagsmithApi
import kotlinx.coroutines.CoroutineScope

internal actual fun Flagsmith.Companion.create(
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
    sseUpdatesScope: CoroutineScope
): Flagsmith = Flagsmith(
    environmentKey = environmentKey,
    baseUrl = baseUrl,
    eventSourceBaseUrl = eventSourceBaseUrl,
    enableAnalytics = enableAnalytics,
    enableRealtimeUpdates = enableRealtimeUpdates,
    analyticsFlushPeriod = analyticsFlushPeriod,
    cacheConfig = cacheConfig,
    defaultFlags = defaultFlags,
    requestTimeoutSeconds = requestTimeoutSeconds,
    readTimeoutSeconds = readTimeoutSeconds,
    writeTimeoutSeconds = writeTimeoutSeconds,
    lastFlagFetchTime = lastFlagFetchTime,
    sseUpdatesScope = sseUpdatesScope,
    flagsmithApiFactory = KtorFlagsmithApi.Companion,
    flagsmithEventApiFactory = null,
    flagsmithAnalyticsFactory = null,
    analyticsStorage = null
)