package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.internal.DefaultFlagsmithAnalytics
import com.flagsmith.internal.http.KtorFlagsmithApi
import com.flagsmith.internal.http.KtorFlagsmithEventApi
import kotlinx.coroutines.CoroutineScope

actual fun Flagsmith.Companion.create(
    environmentKey: String,
    baseUrl: String,
    eventSourceBaseUrl: String,
    userAgentOverride: String?,
    enableAnalytics: Boolean,
    enableRealtimeUpdates: Boolean,
    analyticsFlushPeriod: Int,
    cacheConfig: FlagsmithCacheConfig,
    defaultFlags: List<Flag>,
    requestTimeoutSeconds: Long,
    readTimeoutSeconds: Long,
    writeTimeoutSeconds: Long,
    lastFlagFetchTime: Double,
    coroutineScope: CoroutineScope
): Flagsmith = Flagsmith(
    environmentKey = environmentKey,
    baseUrl = baseUrl,
    eventSourceBaseUrl = eventSourceBaseUrl,
    enableAnalytics = enableAnalytics,
    userAgentOverride = userAgentOverride,
    enableRealtimeUpdates = enableRealtimeUpdates,
    analyticsFlushPeriod = analyticsFlushPeriod,
    cacheConfig = cacheConfig,
    defaultFlags = defaultFlags,
    requestTimeoutSeconds = requestTimeoutSeconds,
    readTimeoutSeconds = readTimeoutSeconds,
    writeTimeoutSeconds = writeTimeoutSeconds,
    lastFlagFetchTime = lastFlagFetchTime,
    coroutineScope = coroutineScope,
    flagsmithApiFactory = KtorFlagsmithApi,
    flagsmithEventApiFactory = KtorFlagsmithEventApi,
    flagsmithAnalyticsFactory = DefaultFlagsmithAnalytics
)