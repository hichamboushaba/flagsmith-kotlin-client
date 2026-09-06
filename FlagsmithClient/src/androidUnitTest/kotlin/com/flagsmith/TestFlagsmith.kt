package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.entities.FlagEvent
import com.flagsmith.internal.FlagsmithAnalytics
import com.flagsmith.internal.http.FlagsmithApi
import com.flagsmith.internal.http.FlagsmithEventApi
import com.flagsmith.internal.http.KtorFlagsmithApi
import com.flagsmith.internal.http.KtorFlagsmithEventApi
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Counts trackEvent calls so tests can pin analytics behaviour against the TTL gate. */
internal class RecordingAnalytics : FlagsmithAnalytics {
    // Atomic because concurrent collectors of the observe* flows track from different threads;
    // a plain ++ loses increments and makes any count assertion flaky.
    private val count = java.util.concurrent.atomic.AtomicInteger(0)
    val trackEventCount: Int get() = count.get()

    override fun trackEvent(flagName: String) {
        count.incrementAndGet()
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

/** A real-time stream tests can drive directly, standing in for the Flagsmith SSE endpoint. */
internal class FakeEventApi : FlagsmithEventApi {
    // extraBufferCapacity + DROP_OLDEST keeps replay = 1 but lets a test emit without suspending,
    // so a subscriber queued behind the emitter on a virtual-time scheduler cannot deadlock it.
    val events = MutableSharedFlow<FlagEvent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    var closed = false
        private set

    override fun observeEvents(): Flow<FlagEvent> = events

    override fun close() {
        closed = true
    }
}

internal class FakeEventApiFactory(val api: FakeEventApi = FakeEventApi()) : FlagsmithEventApi.Factory {
    override fun create(sseUrl: String, environmentKey: String, userAgentOverride: String?) = api
}

/**
 * Builds a [Flagsmith] through the internal constructor so tests can inject a clock
 * ([nowMillis]) and an [analyticsFactory]. Companion `invoke` overloads don't expose these.
 */
internal fun testFlagsmith(
    baseUrl: String,
    identity: String? = null,
    transientIdentity: Boolean = false,
    cacheConfig: FlagsmithCacheConfig = FlagsmithCacheConfig(enableCache = false),
    defaultFlags: List<Flag> = emptyList(),
    enableAnalytics: Boolean = false,
    analyticsFactory: FlagsmithAnalytics.Factory? = null,
    enableRealtimeUpdates: Boolean = false,
    eventApiFactory: FlagsmithEventApi.Factory = KtorFlagsmithEventApi,
    nowMillis: () -> Long = ::getTimeMillis,
    apiFactory: FlagsmithApi.Factory = KtorFlagsmithApi,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
): Flagsmith = Flagsmith(
    environmentKey = "",
    identity = identity,
    transientIdentity = transientIdentity,
    baseUrl = baseUrl,
    enableAnalytics = enableAnalytics,
    enableRealtimeUpdates = enableRealtimeUpdates,
    cacheConfig = cacheConfig,
    defaultFlags = defaultFlags,
    coroutineScope = coroutineScope,
    flagsmithApiFactory = apiFactory,
    flagsmithEventApiFactory = eventApiFactory,
    flagsmithAnalyticsFactory = analyticsFactory,
    nowMillis = nowMillis
)
