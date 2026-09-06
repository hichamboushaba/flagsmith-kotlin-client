@file:OptIn(ExperimentalAtomicApi::class)

package com.flagsmith

import com.flagsmith.entities.*
import com.flagsmith.internal.FlagsCache
import com.flagsmith.internal.FlagsmithAnalytics
import com.flagsmith.internal.FlagsmithEventService
import com.flagsmith.internal.FlagsmithEventTimeTracker
import com.flagsmith.internal.RequestKey
import com.flagsmith.internal.satisfies
import com.flagsmith.internal.update
import com.flagsmith.internal.http.FlagsmithApi
import com.flagsmith.internal.http.FlagsmithEventApi
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import okio.Path.Companion.toPath

/**
 * The main interface to Flagsmith. Fetch and read are separate: [refresh] is the only call that
 * touches the network for flags. [hasFeatureFlag] and [getValueForFeature] read whatever it last
 * fetched, and the [observeHasFeatureFlag]/[observeValueForFeature] flows do the same reactively.
 *
 * @property environmentKey The API key from the Flagsmith dashboard.
 * @property identity The identity to fetch flags and traits for. Switching identity means
 * constructing a new instance (and [close]-ing the old one). When `null`, the instance works in
 * environment mode and the identity-scoped methods throw [IllegalStateException].
 * @property transientIdentity Marks every identity-scoped request as transient: the server
 * evaluates but does not persist it. Requires [identity].
 * @property baseUrl Override when self-hosting.
 * @property enableAnalytics Enable analytics - default true
 * @property analyticsFlushPeriod The period in seconds between attempts to push analytics events to the server
 */
class Flagsmith internal constructor(
    private val environmentKey: String,
    private val identity: String? = null,
    private val transientIdentity: Boolean = false,
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

    // Parented to the caller's job so cancelling the caller's scope still cancels SSE work, but
    // owned here so close() can cancel SSE work without touching the caller's scope.
    private val sseScope: CoroutineScope? =
        if (eventService == null) {
            null
        } else {
            CoroutineScope(
                coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job])
            )
        }

    @Volatile
    private var closed = false

    private val flagSmithApi: FlagsmithApi
    private val analytics: FlagsmithAnalytics?
    private val flagsCache: FlagsCache?

    private var lastEventUpdate: Double = 0.0

    // Guards the sequence counters, [cached] and the [flagsState] write. Never held across network
    // IO, and never while [FlagsCache]'s own lock is taken.
    private val stateMutex = Mutex()
    private var seqCounter = 0L
    private var lastAppliedSeq = 0L

    // Sequence high-water mark at the last real-time event: anything allocated at or below it may
    // carry a pre-event response.
    private var staleAsOfSeq = 0L

    // The document the TTL gate may reuse. Deliberately not [flagsState]: the flow also holds
    // documents with no reusable key, which must never satisfy a gated call. `null` means not yet
    // resolved from [primed]. [lastFlagFetchTime] is the server's timestamp for the SSE stream,
    // never the clock here.
    private var cached: CachedDocument? = null

    /** The one-time priming read, shared by [flagsState] and the TTL gate. */
    private val primed: CachedDocument by lazy {
        val snapshot = flagsCache?.readIfValid()
        CachedDocument(
            flags = snapshot?.flags ?: defaultFlags,
            fetchedAtMillis = snapshot?.savedAtEpochMillis ?: 0L,
            requestKey = snapshot?.requestKey?.let { RequestKey.decode(it) }
        )
    }

    // Lazy: an eager initializer would run before `init` assigns [flagsCache] and prime from defaults.
    private val flagsState: MutableStateFlow<List<Flag>> by lazy { MutableStateFlow(primed.flags) }

    /** The most recently known flags: primed from disk on first access, then updated by every successful [refresh]. */
    internal val flagUpdateFlow: StateFlow<List<Flag>> get() = flagsState

    /**
     * Emits once on collection and again whenever the flags change. Carries no values and counts no
     * evaluation; read with [hasFeatureFlag]/[getValueForFeature] or observe a single flag.
     */
    val flagsChanged: Flow<Unit> get() = flagsState.map { }

    /**
     * This instance's traits: in memory only, never persisted, sent in full on every identity-scoped
     * [refresh]. [getIdentity], [getTraits] and [getTrait] send an empty list instead.
     */
    private val traitState = AtomicReference<Map<String, Trait>>(emptyMap())

    init {
        require(identity != null || !transientIdentity) {
            "transientIdentity requires an identity"
        }
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

    /**
     * Fetches this instance's document and updates the flags served by reads on success - the
     * only call that touches the network for flags. Cheap to call repeatedly: within
     * [FlagsmithCacheConfig.cacheTTL] of a matching fetch it costs nothing. [force] bypasses that.
     */
    suspend fun refresh(force: Boolean = false): Result<Unit> {
        check(!closed) { CLOSED_MESSAGE }

        return refreshInternal(force)
    }

    /**
     * Shared with the SSE event path, which launches it fire-and-forget: a close that lands here
     * must fail the `Result` rather than throw like [refresh], or the exception would propagate
     * into the caller-supplied scope.
     */
    private suspend fun refreshInternal(force: Boolean): Result<Unit> {
        if (closed) {
            return Result.failure(IllegalStateException(CLOSED_MESSAGE))
        }

        val traits = traitState.load().values.toList()
        val key = RequestKey.forRequest(identity, traits, transientIdentity)

        if (!force && withinTtlGate(key)) {
            return Result.success(Unit)
        }

        val seq = beginOperation()
        val result = fetchFlags(traits)

        return result.fold(
            onSuccess = { flags ->
                applyFlags(flags, seq, key)
                Result.success(Unit)
            },
            onFailure = { error -> Result.failure(error) }
        )
    }

    /** Present *and* enabled. Tracks analytics on every call, including reads served from [defaultFlags]. */
    fun hasFeatureFlag(featureId: String): Boolean {
        val found = flagsState.value.any { it.feature.name == featureId && it.enabled }
        analytics?.trackEvent(featureId)
        return found
    }

    /**
     * A flag's value, whether or not it is enabled; `null` only if the flag is absent. `enabled`
     * is a separate switch, read it with [hasFeatureFlag].
     */
    fun getValueForFeature(featureId: String): Any? {
        val flag = flagsState.value.find { it.feature.name == featureId }
        analytics?.trackEvent(featureId)
        return flag?.featureStateValue
    }

    /**
     * [hasFeatureFlag] as a flow: emits on collection and whenever the observed enabled state changes.
     * Counts one evaluation per emission, so a long-lived collector whose value never changes
     * counts once; in a process that outlives the analytics window, read imperatively instead.
     */
    fun observeHasFeatureFlag(featureId: String): Flow<Boolean> =
        observeFeature(featureId) { flag -> flag != null && flag.enabled }

    /**
     * [getValueForFeature] as a flow: emits on collection and whenever the observed value changes.
     * Changing only a flag's `enabled` state does not make this flow emit. Evaluations are counted
     * as in [observeHasFeatureFlag].
     */
    fun observeValueForFeature(featureId: String): Flow<Any?> =
        observeFeature(featureId) { flag -> flag?.featureStateValue }

    private fun <T> observeFeature(featureId: String, project: (Flag?) -> T): Flow<T> =
        flagsState
            .map { flags -> project(flags.find { it.feature.name == featureId }) }
            // After distinctUntilChanged, not before: an evaluation is a value the collector
            // actually received, so a refresh that leaves this flag alone must not count.
            .distinctUntilChanged()
            .onEach { analytics?.trackEvent(featureId) }

    /** A read-only diagnostic of the server's *stored* view of this identity. */
    suspend fun getIdentity(): Result<IdentityFlagsAndTraits> {
        check(!closed) { CLOSED_MESSAGE }
        // An empty trait list keeps the POST read-only: the server modifies nothing.
        return flagSmithApi.postTraits(IdentityAndTraits(requireIdentity(), emptyList(), transientIdentity))
    }

    /** The traits the server currently has stored for this identity. */
    suspend fun getTraits(): Result<List<Trait>> = getIdentity().map { it.traits }

    /** The trait the server currently has stored under [id], or `null` if it has none. */
    suspend fun getTrait(id: String): Result<Trait?> = getTraits().map { traits -> traits.find { it.key == id } }

    /** Upserts [traits] by [Trait.key] into the in-memory trait state sent by [refresh]. */
    fun setTraits(traits: List<Trait>) {
        requireIdentity()
        val upserts = traits.associateBy { it.key }
        traitState.update { it + upserts }
    }

    /** Upserts [trait] into the in-memory trait state sent by [refresh]. */
    fun setTrait(trait: Trait) = setTraits(listOf(trait))

    /** Stops sending [key]; the server keeps whatever value it last stored for it. */
    fun removeTrait(key: String) {
        requireIdentity()
        traitState.update { it - key }
    }

    /**
     * Resets the flags served by reads to [defaultFlags] and deletes the on-disk snapshot, so
     * the next [refresh] hits the network. Does not touch trait state. Still works after [close].
     */
    suspend fun clearCache() {
        val barrier = stateMutex.withLock {
            lastAppliedSeq = seqCounter
            cached = CachedDocument(defaultFlags, fetchedAtMillis = 0L, requestKey = null)
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
        // Cancel first: resubscribing over a live collector would duplicate every event.
        sseUpdatesJob?.cancel()
        sseUpdatesJob = eventService?.subscribeToEvents()
    }

    /**
     * Releases everything this instance owns: the real-time subscription, the analytics flush loop
     * and the HTTP clients. The instance is unusable afterwards; build a new one, which is also how
     * you switch [identity].
     */
    fun close() {
        closed = true
        // First, so no SSE-scheduled refresh can run against the clients released below.
        sseScope?.cancel()
        analytics?.stop()
        flagSmithApi.close()
        eventService?.close()
    }

    private data class CachedDocument(
        val flags: List<Flag>,
        val fetchedAtMillis: Long,
        /**
         * The key of the request that produced this document, or `null` when no fetch did (the
         * defaults seeded by [clearCache], an unkeyable or undecodable snapshot); such a document
         * primes the flow but never satisfies the gate. Stored with the document at response time,
         * under [stateMutex], which is what makes a late response from a superseded request safe:
         * it lands with the key of the request that produced it, and the next call mismatches and
         * refetches.
         */
        val requestKey: RequestKey? = null,
        /**
         * Set when a real-time event said the server changed. The document stays as an offline
         * fallback but must never satisfy a gated call again, or a failed event-triggered refresh
         * would suppress every retry for the rest of the TTL.
         */
        val knownStale: Boolean = false,
    )

    private fun requireIdentity(): String = identity ?: error(IDENTITY_REQUIRED_MESSAGE)

    /**
     * Whether [refresh] for [requestKey] can be answered from memory. `false` sends the caller to
     * the network, including for a `null` key or a document whose key does not [satisfies] it.
     */
    private suspend fun withinTtlGate(requestKey: RequestKey?): Boolean {
        if (!cacheConfig.enableCache || requestKey == null) return false

        return stateMutex.withLock {
            val document = cachedLocked()
            val fetchedAt = document.fetchedAtMillis
            // Deliberately a two-sided window, not a clamped age. A device clock that was ahead
            // stamps `fetchedAt` in the future; clamping the negative age to zero would make the
            // gate hit forever, since only a fetch restamps `fetchedAt`. As written, a clock jump
            // in either direction costs one extra request and then self-heals.
            val age = nowMillis() - fetchedAt
            document.requestKey?.satisfies(requestKey) == true &&
                !document.knownStale &&
                fetchedAt > 0L &&
                age in 0..cacheConfig.cacheTTL.inWholeMilliseconds
        }
    }

    private suspend fun fetchFlags(traits: List<Trait>): Result<List<Flag>> = if (identity == null) {
        flagSmithApi.getFlags()
    } else {
        // Always POST, even with no traits: an empty-trait POST still returns the identity's stored view.
        flagSmithApi.postTraits(IdentityAndTraits(identity, traits, transientIdentity)).map { it.flags }
    }

    /** Allocates the ordering token for a flag-producing operation. */
    private suspend fun beginOperation(): Long = stateMutex.withLock { ++seqCounter }

    /**
     * Applies [flags] unless a newer operation (or [clearCache]) already has. Only a document with a
     * reusable [requestKey] advances the gate and reaches disk; the rest are emitted but never cached.
     */
    private suspend fun applyFlags(flags: List<Flag>, seq: Long, requestKey: RequestKey?) {
        // Stamped once and shared with the disk write, so the in-memory and on-disk clocks cannot drift.
        val fetchedAtMillis = nowMillis()

        val accepted = stateMutex.withLock {
            if (seq <= lastAppliedSeq) {
                false
            } else {
                lastAppliedSeq = seq
                if (requestKey != null) {
                    // An operation allocated before the last real-time event may be answering with
                    // a document generated before that event, so it must not clear the stale mark.
                    cached = CachedDocument(
                        flags,
                        fetchedAtMillis,
                        requestKey,
                        knownStale = seq <= staleAsOfSeq
                    )
                }
                flagsState.value = flags
                true
            }
        }
        if (accepted && requestKey != null) {
            flagsCache?.write(flags, seq, fetchedAtMillis, requestKey.encode())
        }
    }

    /** Call only while holding [stateMutex]. Resolves the primed snapshot on first use. */
    private fun cachedLocked(): CachedDocument = cached ?: primed.also { cached = it }

    /** Retires the cached document from the gate; see [CachedDocument.knownStale]. */
    private suspend fun markCachedDocumentStale() = stateMutex.withLock {
        staleAsOfSeq = seqCounter
        cached = cachedLocked().copy(knownStale = true)
    }

    private fun FlagsmithEventService.subscribeToEvents(): Job {
        val scope = requireNotNull(sseScope)
        return sseEventsFlow
            .onEach { event ->
                lastEventUpdate = event.updatedAt ?: lastEventUpdate

                if (lastEventUpdate > lastFlagFetchTime) {
                    lastFlagFetchTime = lastEventUpdate

                    // Before the refresh allocates its sequence, so its own response is not marked
                    // pre-event; and regardless of the refresh's outcome, since it may fail.
                    markCachedDocumentStale()
                    scope.launch {
                        val res = refreshInternal(force = true)
                        if (res.isFailure && !closed) {
                            // A failure after close is the expected shutdown rejection, not news.
                            println("Error getting flags in SSE stream: ${res.exceptionOrNull()}")
                        }
                    }
                }
            }
            .launchIn(scope)
    }

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
            transientIdentity: Boolean = false,
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
            transientIdentity = transientIdentity,
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
