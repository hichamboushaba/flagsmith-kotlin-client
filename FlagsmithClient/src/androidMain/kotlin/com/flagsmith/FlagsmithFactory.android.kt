package com.flagsmith

import android.content.Context
import com.flagsmith.entities.Flag
import com.flagsmith.internal.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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
) = Flagsmith(
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
    flagsmithApiFactory = RetrofitFlagsmithApi.Companion,
    flagsmithEventApiFactory = RetrofitFlagsmithEventApi.Companion,
    flagsmithAnalyticsFactory = null,
    analyticsStorage = null
)

operator fun Flagsmith.Companion.invoke(
    environmentKey: String,
    baseUrl: String = "https://edge.api.flagsmith.com/api/v1/",
    eventSourceBaseUrl: String = "https://realtime.flagsmith.com/",
    context: Context,
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
    flagsmithApiFactory = RetrofitFlagsmithApi.Companion,
    flagsmithEventApiFactory = RetrofitFlagsmithEventApi.Companion,
    flagsmithAnalyticsFactory = AndroidFlagsmithAnalytics.Companion,
    analyticsStorage = AndroidAnalyticsStorage(context)
)
