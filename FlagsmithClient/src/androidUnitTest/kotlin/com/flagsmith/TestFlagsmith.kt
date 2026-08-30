package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.internal.FlagsmithAnalytics
import com.flagsmith.internal.http.FlagsmithApi
import com.flagsmith.internal.http.KtorFlagsmithApi
import com.flagsmith.internal.http.KtorFlagsmithEventApi
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Counts trackEvent calls so tests can pin analytics behaviour against the TTL gate. */
internal class RecordingAnalytics : FlagsmithAnalytics {
    var trackEventCount = 0
        private set

    override fun trackEvent(flagName: String) {
        trackEventCount++
    }

    override fun stop() {
        // nothing to release
    }
}

internal class RecordingAnalyticsFactory : FlagsmithAnalytics.Factory {
    lateinit var analytics: RecordingAnalytics
        private set

    override fun create(
        flagsmithApi: FlagsmithApi,
        flushPeriod: Int,
        coroutineScope: CoroutineScope
    ): FlagsmithAnalytics {
        analytics = RecordingAnalytics()
        return analytics
    }
}

/**
 * Builds a [Flagsmith] through the internal constructor so tests can inject a clock
 * ([nowMillis]) and an [analyticsFactory]. Companion `invoke` overloads don't expose these.
 */
internal fun testFlagsmith(
    baseUrl: String,
    identity: String? = null,
    cacheConfig: FlagsmithCacheConfig = FlagsmithCacheConfig(enableCache = false),
    defaultFlags: List<Flag> = emptyList(),
    enableAnalytics: Boolean = false,
    analyticsFactory: FlagsmithAnalytics.Factory? = null,
    nowMillis: () -> Long = ::getTimeMillis
): Flagsmith = Flagsmith(
    environmentKey = "",
    identity = identity,
    baseUrl = baseUrl,
    enableAnalytics = enableAnalytics,
    cacheConfig = cacheConfig,
    defaultFlags = defaultFlags,
    coroutineScope = CoroutineScope(Dispatchers.Default),
    flagsmithApiFactory = KtorFlagsmithApi,
    flagsmithEventApiFactory = KtorFlagsmithEventApi,
    flagsmithAnalyticsFactory = analyticsFactory,
    nowMillis = nowMillis
)
