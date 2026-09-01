package com.flagsmith

import com.flagsmith.entities.*
import com.flagsmith.internal.FlagsCache
import com.flagsmith.internal.FlagsmithAnalytics
import com.flagsmith.internal.FlagsmithEventService
import com.flagsmith.internal.FlagsmithEventTimeTracker
import com.flagsmith.internal.http.FlagsmithApi
import com.flagsmith.internal.http.FlagsmithEventApi
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import okio.Path.Companion.toPath

/**
 * Flagsmith
 *
 * The main interface to all of the Flagsmith functionality
 *
 * @property environmentKey Take this API key from the Flagsmith dashboard and pass here
 * @property identity The identity to fetch flags and traits for. When provided, every
 * identity-scoped method targets this identity and [flagUpdateFlow] represents its flags. Passing
 * a different identity later means constructing a new instance (and [close]-ing the old one).
 * When `null`, the instance works in environment mode (environment-level flags only) and the
 * identity-scoped methods throw [IllegalStateException].
 * @property baseUrl By default we'll connect to the Flagsmith backend, but if you self-host you can configure here
 * @property enableAnalytics Enable analytics - default true
 * @property analyticsFlushPeriod The period in seconds between attempts by the Flagsmith SDK to push analytic events to the server
 * @constructor Create empty Flagsmith
 */
class Flagsmith internal constructor(
    private val environmentKey: String,
    private val identity: String? = null,
    private val baseUrl: String = "https://edge.api.flagsmith.com/api/v1/",
    private val eventSourceBaseUrl: String = "https://realtime.flagsmith.com/",
    private val enableAnalytics: Boolean = DEFAULT_ENABLE_ANALYTICS,
    private val userAgentOverride: String? = null,
    private val enableRealtimeUpdates: Boolean = false,
    private val analyticsFlushPeriod: Int = DEFAULT_ANALYTICS_FLUSH_PERIOD_SECONDS,
    private val cacheConfig: FlagsmithCacheConfig = FlagsmithCacheConfig(),
    private val defaultFlags: List<Flag> = emptyList(),
    private val requestTimeoutSeconds: Long = 4L,
    private val readTimeoutSeconds: Long = 6L,
    private val writeTimeoutSeconds: Long = 6L,
    override var lastFlagFetchTime: Double = 0.0, // from FlagsmithEventTimeTracker
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    flagsmithApiFactory: FlagsmithApi.Factory,
    flagsmithEventApiFactory: FlagsmithEventApi.Factory?,
    flagsmithAnalyticsFactory: FlagsmithAnalytics.Factory?,
    private val nowMillis: () -> Long = ::getTimeMillis,
) : FlagsmithEventTimeTracker {
    private val eventService: FlagsmithEventService? = if (!enableRealtimeUpdates || flagsmithEventApiFactory == null) {
        null
    } else {
        FlagsmithEventService(
            eventSourceBaseUrl = eventSourceBaseUrl,
            environmentKey = environmentKey,
            userAgentOverride = userAgentOverride,
            flagsmithEventApiFactory = flagsmithEventApiFactory
        )
    }
    private var sseUpdatesJob: Job? = null
    @Volatile
    private var closed = false

    private val flagSmithApi: FlagsmithApi
    private val analytics: FlagsmithAnalytics?

    /**
     * Persists the flags most recently emitted to [flagUpdateFlow] so they survive a cold start.
     * `null` when caching is disabled.
     */
    private val flagsCache: FlagsCache?

    // The last time we got an event from the SSE stream or via the API
    private var lastEventUpdate: Double = 0.0

    // Guards [seqCounter], [lastAppliedSeq], [staleAsOfSeq], [cached] and the [flagsState] write.
    // Never held across network IO, and never while [FlagsCache]'s own lock is taken. The one
    // exception is the first touch, where resolving [primed] reads the snapshot file under this
    // lock; that read takes no locks itself, so the ordering stays acyclic.
    private val stateMutex = Mutex()
    private var seqCounter = 0L
    private var lastAppliedSeq = 0L

    // The sequence high-water mark when a real-time event last told us the server changed.
    // Anything allocated at or below it may carry a pre-event response.
    private var staleAsOfSeq = 0L

    // The document the TTL gate and the stale fallback may reuse, and when it was fetched
    // (`0L` = nothing reusable). Deliberately NOT [flagsState]: the flow holds whatever was
    // emitted last, including transient documents, which must never satisfy a gated call.
    // `null` means "not resolved from [primed] yet". Never use [lastFlagFetchTime] as the clock
    // here — that is the server's document timestamp and belongs to the SSE stream.
    private var cached: CachedDocument? = null

    /**
     * The one-time priming read, shared by [flagsState] and the TTL gate. Seeding the gate from
     * disk keeps it warm across process death, exactly as it was warm from the previous session's
     * last fetch.
     */
    private val primed: CachedDocument by lazy {
        val snapshot = flagsCache?.readIfValid()
        CachedDocument(snapshot?.flags ?: defaultFlags, snapshot?.savedAtEpochMillis ?: 0L)
    }

    /**
     * Backing state of [flagUpdateFlow]. `by lazy` guarantees the priming read happens exactly
     * once, happens-before any observer, and before any write that goes through [flagsState].
     */
    private val flagsState: MutableStateFlow<List<Flag>> by lazy { MutableStateFlow(primed.flags) }

    /** The most recently known flags: primed from disk on first access, then updated by every successful fetch. */
    val flagUpdateFlow: StateFlow<List<Flag>> get() = flagsState

    init {
        require(!cacheConfig.enableCache || cacheConfig.cacheDirectoryPath.isNotEmpty()) {
            "Cache directory path must be provided when cache is enabled"
        }
        if (enableRealtimeUpdates && flagsmithEventApiFactory == null) {
            error("Real-time updates are enabled but no event API factory was provided")
        }

        flagsCache = if (cacheConfig.enableCache) {
            FlagsCache(
                baseDirectory = cacheConfig.cacheDirectoryPath.toPath(),
                scope = FlagsCache.Scope(baseUrl, environmentKey, identity),
                ttl = cacheConfig.cacheTTL,
                acceptStale = cacheConfig.acceptStaleCache,
                maxFileBytes = cacheConfig.maxSnapshotSizeBytes,
                nowMillis = nowMillis,
            )
        } else null

        flagSmithApi = flagsmithApiFactory.create(
            baseUrl = baseUrl,
            environmentKey = environmentKey,
            userAgentOverride = userAgentOverride,
            requestTimeoutSeconds = requestTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds,
            writeTimeoutSeconds = writeTimeoutSeconds,
            timeTracker = this,
            json = defaultJson
        )

        analytics = if (enableAnalytics) {
            requireNotNull(flagsmithAnalyticsFactory) {
                "Analytics is enabled but no analytics factory was provided"
            }
            flagsmithAnalyticsFactory.create(flagSmithApi, analyticsFlushPeriod, coroutineScope)
        } else {
            null
        }

        sseUpdatesJob = eventService?.subscribeToEvents()
    }

    suspend fun getFeatureFlags(
        traits: List<Trait>? = null,
        transient: Boolean = false,
        forceRefresh: Boolean = false
    ): Result<List<Flag>> {
        // Without this a closed instance keeps answering from the gate, so the "unusable
        // afterwards" contract would only surface once the TTL happened to expire.
        check(!closed) { CLOSED_MESSAGE }

        if (!forceRefresh && traits == null && !transient) {
            cachedFlagsWithinTtl()?.let { return Result.success(it) }
        }

        val seq = beginOperation()
        val result = fetchFlags(traits, transient)

        // Emit and cache BEFORE falling back: a defaults fallback must never overwrite the
        // last-known flags in the flow, nor reach the disk snapshot.
        if (result.isSuccess) {
            applyFlags(result.getOrThrow(), seq, cacheable = isCacheable(transient, traits))
        }

        return result.recoverCatching { error -> lastKnownFlagsOrDefaults(error) }
    }

    suspend fun hasFeatureFlag(featureId: String): Result<Boolean> =
        getFeatureFlag(featureId).map { flag -> flag != null }

    suspend fun getValueForFeature(featureId: String): Result<Any?> =
        getFeatureFlag(featureId).map { flag -> flag?.featureStateValue }

    suspend fun getTrait(id: String): Result<Trait?> =
        getTraits().map { traits -> traits.find { it.key == id } }

    suspend fun getTraits(): Result<List<Trait>> {
        check(!closed) { CLOSED_MESSAGE }
        return flagSmithApi.getIdentityFlagsAndTraits(requireIdentity()).map { it.traits }
    }

    suspend fun setTrait(trait: Trait): Result<TraitWithIdentity> =
        setTraits(listOf(trait)).map { it.first() }

    suspend fun setTraits(traits: List<Trait>): Result<List<TraitWithIdentity>> {
        check(!closed) { CLOSED_MESSAGE }
        val identity = requireIdentity()
        val seq = beginOperation()
        val result = flagSmithApi.postTraits(IdentityAndTraits(identity, traits))

        if (result.isSuccess) {
            applyFlags(result.getOrThrow().flags, seq, cacheable = isCacheable(transient = false, traits = traits))
        }

        return result.map { response ->
            response.traits.map { trait ->
                TraitWithIdentity(
                    key = trait.key,
                    traitValue = trait.traitValue,
                    identity = Identity(identity)
                )
            }
        }
    }

    suspend fun getIdentity(transient: Boolean = false): Result<IdentityFlagsAndTraits> {
        check(!closed) { CLOSED_MESSAGE }
        return flagSmithApi.getIdentityFlagsAndTraits(requireIdentity(), transient)
    }

    /**
     * Forgets everything this instance knows: the cached document on disk, the in-memory flags
     * (reset to the configured defaults) and the TTL gate, so the next call hits the network.
     * Flag requests already in flight are discarded.
     *
     * Unlike the fetching methods this still works after [close], so `close()` then `clearCache()`
     * remains a valid teardown order — it touches no network client.
     */
    suspend fun clearCache() {
        val barrier = stateMutex.withLock {
            lastAppliedSeq = seqCounter
            cached = CachedDocument(defaultFlags, fetchedAtMillis = 0L)
            flagsState.value = defaultFlags
            seqCounter
        }
        flagsCache?.clear(barrier)
    }

    fun restartRealtimeUpdates() {
        check(!closed) { CLOSED_MESSAGE }
        if (!enableRealtimeUpdates) {
            error("Real-time updates are not enabled for this instance")
        }
        if (!coroutineScope.isActive) {
            error("The SSE updates scope has been canceled")
        }
        // Cancel first: resubscribing over a live collector duplicates every event, and with it
        // every refresh the event triggers.
        sseUpdatesJob?.cancel()
        sseUpdatesJob = eventService?.subscribeToEvents()
    }

    /**
     * Releases everything this instance owns: the real-time subscription, the analytics flush loop
     * and the underlying HTTP clients (each of which holds an engine and a connection pool).
     *
     * The instance is unusable afterwards — build a new one, which is also how you switch
     * [identity]. Stop analytics and real-time updates without discarding the instance is not
     * supported; use [restartRealtimeUpdates] only before closing.
     */
    fun close() {
        closed = true
        sseUpdatesJob?.cancel()
        analytics?.stop()
        flagSmithApi.close()
        eventService?.close()
    }

    private class CachedDocument(
        val flags: List<Flag>,
        val fetchedAtMillis: Long,
        /**
         * Set when a real-time event told us the server changed. The document stays available as
         * an offline fallback, but must never satisfy a TTL-gated call again — otherwise a failed
         * event-triggered refresh would suppress every retry for the rest of the TTL.
         */
        val knownStale: Boolean = false,
    )

    private fun requireIdentity(): String = identity ?: error(IDENTITY_REQUIRED_MESSAGE)

    /**
     * The in-memory TTL gate: within [FlagsmithCacheConfig.cacheTTL] of the last successful fetch
     * we answer from the last reusable document without touching Ktor at all. Returns `null` when
     * the caller must go to the network.
     */
    private suspend fun cachedFlagsWithinTtl(): List<Flag>? {
        if (!cacheConfig.enableCache) return null

        return stateMutex.withLock {
            val document = cachedLocked()
            val fetchedAt = document.fetchedAtMillis
            // Deliberately a two-sided window rather than a clamped elapsed time. A device
            // whose clock was ahead stamps `fetchedAt` in the future; clamping the resulting
            // negative age to zero would make the gate hit forever, and since only a fetch
            // restamps `fetchedAt`, nothing would ever break the loop. As written, any clock
            // jump in either direction is a miss: one extra request, then it self-heals.
            val age = nowMillis() - fetchedAt
            document.flags.takeIf {
                !document.knownStale &&
                    fetchedAt > 0L &&
                    age in 0..cacheConfig.cacheTTL.inWholeMilliseconds
            }
        }
    }

    private suspend fun fetchFlags(traits: List<Trait>?, transient: Boolean): Result<List<Flag>> {
        if (identity == null) {
            // Traits belong to an identity, so this combination is a programming error.
            if (traits != null) error(IDENTITY_REQUIRED_MESSAGE)
            return flagSmithApi.getFlags()
        }

        return if (traits != null) {
            flagSmithApi.postTraits(IdentityAndTraits(identity, traits, transient)).map { it.flags }
        } else {
            // Pass transient flag only if it's true
            // TODO: revisit this when https://github.com/Flagsmith/flagsmith/issues/5260 is resolved
            flagSmithApi.getIdentityFlagsAndTraits(identity, transient.takeIf { it }).map { it.flags }
        }
    }

    /**
     * Fallback for a failed fetch. With [FlagsmithCacheConfig.acceptStaleCache] we serve the
     * last-known document — including when it is legitimately empty, which is why the check is on
     * "have we ever fetched" rather than on the list being non-empty.
     */
    private suspend fun lastKnownFlagsOrDefaults(error: Throwable): List<Flag> {
        val document = stateMutex.withLock { cachedLocked() }

        return if (cacheConfig.enableCache && cacheConfig.acceptStaleCache && document.fetchedAtMillis > 0L) {
            document.flags
        } else {
            defaultFlags.ifEmpty { throw error }
        }
    }

    /** Allocates the ordering token for a flag-producing operation. Called at operation entry. */
    private suspend fun beginOperation(): Long = stateMutex.withLock { ++seqCounter }

    /**
     * A transient identity, or traits the server won't store, produce a document that doesn't
     * represent this identity's stored state. Such a document is still emitted, but it must
     * neither reach the disk snapshot nor satisfy a later TTL-gated call.
     */
    private fun isCacheable(transient: Boolean, traits: List<Trait>?): Boolean =
        !transient && traits.orEmpty().none { it.transient }

    /**
     * Applies [flags] to [flagsState] unless a newer operation has already applied (or [clearCache]
     * has invalidated everything up to the current sequence). Only a [cacheable] document advances
     * the TTL clock and is written to the [flagsCache].
     */
    private suspend fun applyFlags(flags: List<Flag>, seq: Long, cacheable: Boolean) {
        // Stamped once and shared with the disk write below, so the in-memory and on-disk clocks
        // cannot drift apart by however long the write waits for its dispatcher and lock.
        val fetchedAtMillis = nowMillis()

        val accepted = stateMutex.withLock {
            if (seq <= lastAppliedSeq) {
                false
            } else {
                lastAppliedSeq = seq
                if (cacheable) {
                    // An operation allocated before the last real-time event may be answering with
                    // a document generated before that event, so it must not clear the stale mark.
                    cached = CachedDocument(flags, fetchedAtMillis, knownStale = seq <= staleAsOfSeq)
                }
                flagsState.value = flags
                true
            }
        }
        if (accepted && cacheable) {
            flagsCache?.write(flags, seq, fetchedAtMillis)
        }
    }

    /** Call only while holding [stateMutex]. Resolves the primed snapshot on first use. */
    private fun cachedLocked(): CachedDocument = cached ?: primed.also { cached = it }

    /**
     * Marks the cached document known-stale. The 0.1.x code deleted its HTTP cache here, which was
     * durable: after a failed refresh every later read went back to the network. `forceRefresh`
     * alone only skips the gate for one call, so without this a failed event-triggered refresh
     * would leave the superseded document serving reads for the rest of its TTL.
     */
    private suspend fun markCachedDocumentStale() = stateMutex.withLock {
        staleAsOfSeq = seqCounter
        val document = cachedLocked()
        cached = CachedDocument(document.flags, document.fetchedAtMillis, knownStale = true)
    }

    private suspend fun getFeatureFlag(featureId: String) = getFeatureFlags().map { flags ->
        val foundFlag = flags.find { flag -> flag.feature.name == featureId && flag.enabled }
        analytics?.trackEvent(featureId)
        foundFlag
    }

    private fun FlagsmithEventService.subscribeToEvents() = sseEventsFlow
        .onEach { event ->
            lastEventUpdate = event.updatedAt ?: lastEventUpdate

            // Check whether this event is anything new
            if (lastEventUpdate > lastFlagFetchTime) {
                lastFlagFetchTime = lastEventUpdate

                // The event proves what we hold is superseded, so retire it before refreshing:
                // `forceRefresh` only bypasses the gate for this one call, and the refresh may
                // fail. Nothing is logged on success - a stale or defaults fallback also returns
                // a successful Result, so it would not mean we got the new values.
                markCachedDocumentStale()
                getFeatureFlags(forceRefresh = true) { res ->
                    if (res.isFailure) {
                        // TODO: provide a logging mechanism
                        println("Error getting flags in SSE stream: ${res.exceptionOrNull()}")
                    }
                }
            }
        }
        .launchIn(coroutineScope)

    companion object {
        const val DEFAULT_ENABLE_ANALYTICS = true
        const val DEFAULT_ANALYTICS_FLUSH_PERIOD_SECONDS = 10

        private const val CLOSED_MESSAGE = "This Flagsmith instance has been closed"

        private const val IDENTITY_REQUIRED_MESSAGE =
            "This Flagsmith instance was created without an identity. " +
                "Pass `identity` to the Flagsmith factory to use identity-scoped APIs."

        operator fun invoke(
            environmentKey: String,
            identity: String? = null,
            baseUrl: String = "https://edge.api.flagsmith.com/api/v1/",
            eventSourceBaseUrl: String = "https://realtime.flagsmith.com/",
            userAgentOverride: String? = null,
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
        ) = create(
            environmentKey = environmentKey,
            identity = identity,
            baseUrl = baseUrl,
            eventSourceBaseUrl = eventSourceBaseUrl,
            userAgentOverride = userAgentOverride,
            enableAnalytics = enableAnalytics,
            enableRealtimeUpdates = enableRealtimeUpdates,
            analyticsFlushPeriod = analyticsFlushPeriod,
            cacheConfig = cacheConfig,
            defaultFlags = defaultFlags,
            requestTimeoutSeconds = requestTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds,
            writeTimeoutSeconds = writeTimeoutSeconds,
            lastFlagFetchTime = lastFlagFetchTime,
            coroutineScope = sseUpdatesScope
        )
    }
}
