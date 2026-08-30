package com.flagsmith

import com.flagsmith.entities.*
import com.flagsmith.internal.FlagsmithAnalytics
import com.flagsmith.internal.FlagsmithEventService
import com.flagsmith.internal.FlagsmithEventTimeTracker
import com.flagsmith.internal.FlagsCache
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
    internal val nowMillis: () -> Long = ::getTimeMillis,
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

    private val flagSmithApi: FlagsmithApi
    private val analytics: FlagsmithAnalytics?

    // The last time we got an event from the SSE stream or via the API
    private var lastEventUpdate: Double = 0.0

    /**
     * Stores the flags most recently emitted to [flagUpdateFlow] so they can survive a cold start.
     * `null` when caching is disabled.
     */
    init {
        // Declared before the store property so the guard runs before `cacheDirectoryPath` is
        // consumed by its initialiser.
        require(!cacheConfig.enableCache || cacheConfig.cacheDirectoryPath.isNotEmpty()) {
            "Cache directory path must be provided when cache is enabled"
        }
    }

    private val flagsCache: FlagsCache? =
        if (cacheConfig.enableCache) {
            FlagsCache(
                baseDirectory = cacheConfig.cacheDirectoryPath.toPath(),
                scope = FlagsCache.Scope(baseUrl, environmentKey, identity),
                ttl = cacheConfig.cacheTTL,
                acceptStale = cacheConfig.acceptStaleCache,
                maxFileBytes = cacheConfig.maxSnapshotSizeBytes,
                nowMillis = nowMillis,
            )
        } else null

    /**
     * Result of the one-time priming read: the flags that [flagsState] starts with, and the
     * timestamp (epoch millis) the snapshot was written at — `0L` when there was no valid
     * snapshot. Used to seed [lastSuccessfulFetchAtMillis] so the TTL gate is warm after process
     * death, exactly as it was warm from the previous session's last fetch.
     */
    private class Primed(val flags: List<Flag>, val fetchedAtMillis: Long)

    private val primed: Primed by lazy {
        flagsCache?.readIfValid()
            .let { Primed(it?.flags ?: defaultFlags, it?.savedAtEpochSeconds?.times(1000) ?: 0L) }
    }

    /**
     * Backing state of [flagUpdateFlow]. Primed lazily on first access: either from the
     * last-known-flags snapshot or, when there is none, from [defaultFlags]. `by lazy` guarantees
     * the priming read happens exactly once, happens-before any observer, and before any write
     * that goes through [flagsState].
     */
    private val flagsState: MutableStateFlow<List<Flag>> by lazy {
        MutableStateFlow(primed.flags)
    }

    /** The most recently known flags: primed from disk on first access, then updated by every successful fetch. */
    val flagUpdateFlow: StateFlow<List<Flag>> get() = flagsState

    // Guards [seqCounter], [lastAppliedSeq], [lastSuccessfulFetchAtMillis] and the [flagsState]
    // write. Never held across IO.
    private val stateMutex = Mutex()
    private var seqCounter = 0L // guarded by stateMutex
    private var lastAppliedSeq = 0L // guarded by stateMutex

    // Local fetch clock for the TTL gate: when we last applied a successful flag document.
    // Three states: null = not yet resolved (falls back to the primed snapshot time),
    // 0L = known-none (cleared), >0 = known. Never use [lastFlagFetchTime] here — that is the
    // server's document timestamp and keeps SSE state.
    private var lastSuccessfulFetchAtMillis: Long? = null // guarded by stateMutex

    /** Call only while holding [stateMutex]. */
    private fun fetchedAtMillisLocked(): Long =
        lastSuccessfulFetchAtMillis ?: primed.fetchedAtMillis.also { lastSuccessfulFetchAtMillis = it }

    /** Allocates the ordering token for a flag-producing operation. Called at operation entry. */
    private suspend fun beginOperation(): Long = stateMutex.withLock { ++seqCounter }

    /**
     * Applies [flags] to [flagsState] unless a newer operation has already applied (or
     * [clearCache] has invalidated everything up to the current sequence). On success and when
     * [persist] is set, the flags are also written to the last-known-flags store.
     */
    private suspend fun applyFlags(flags: List<Flag>, seq: Long, persist: Boolean) {
        val accepted = stateMutex.withLock {
            if (seq <= lastAppliedSeq) {
                false
            } else {
                lastAppliedSeq = seq
                lastSuccessfulFetchAtMillis = nowMillis()
                flagsState.value = flags
                true
            }
        }
        if (accepted && persist) {
            flagsCache?.write(flags, seq)
        }
    }

    init {
        if (enableRealtimeUpdates && flagsmithEventApiFactory == null) {
            error("Real-time updates are enabled but no event API factory was provided")
        }

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
        // In-memory TTL gate: within [FlagsmithCacheConfig.cacheTTL] of the last successful
        // fetch, answer from [flagsState] without touching Ktor at all. The exclusions are
        // load-bearing: `traits != null` is a POST (a write), `transient = true` is a distinct
        // server-side semantic that must never be served from persisted state, and
        // `enableCache = false` must keep meaning "always fetch".
        if (cacheConfig.enableCache && !forceRefresh && traits == null && !transient) {
            stateMutex.withLock {
                val fetchedAt = fetchedAtMillisLocked()
                val age = nowMillis() - fetchedAt
                if (fetchedAt > 0L && age in 0..cacheConfig.cacheTTL.inWholeMilliseconds) {
                    flagsState.value
                } else {
                    null
                }
            }?.let { return Result.success(it) }
        }

        val seq = beginOperation()

        val result = if (identity != null) {
            if (traits != null) {
                flagSmithApi.postTraits(IdentityAndTraits(identity, traits, transient))
                    .map { it.flags }
            } else {
                // Pass transient flag only if it's true
                // TODO: revisit this when https://github.com/Flagsmith/flagsmith/issues/5260 is resolved
                flagSmithApi.getIdentityFlagsAndTraits(identity, transient.takeIf { it })
                    .map { it.flags }
            }
        } else {
            if (traits != null) {
                // Only reachable in environment mode - see [requireIdentity].
                error(IDENTITY_REQUIRED_MESSAGE)
            } else {
                flagSmithApi.getFlags()
            }
        }

        // Emit and persist BEFORE falling back: a defaults fallback must never
        // overwrite the last-known flags in the flow, nor reach the disk snapshot.
        if (result.isSuccess) {
            applyFlags(result.getOrThrow(), seq, persist = !transient)
        }

        return result.recoverCatching { error ->
            val known = stateMutex.withLock { fetchedAtMillisLocked() > 0L }
            if (cacheConfig.acceptStaleCache && known) {
                // Serve the last-known document, even when it is legitimately empty.
                flagsState.value
            } else {
                defaultFlags.ifEmpty { throw error }
            }
        }
    }

    suspend fun hasFeatureFlag(
        featureId: String
    ): Result<Boolean> {
        return getFeatureFlag(featureId).map { flag -> flag != null }
    }

    suspend fun getValueForFeature(
        featureId: String
    ) = getFeatureFlag(featureId).map { flag -> flag?.featureStateValue }

    suspend fun getTrait(id: String): Result<Trait?> {
        return flagSmithApi.getIdentityFlagsAndTraits(requireIdentity())
            .map { value -> value.traits.find { it.key == id } }
    }

    suspend fun getTraits(): Result<List<Trait>> {
        return flagSmithApi.getIdentityFlagsAndTraits(requireIdentity())
            .map { value -> value.traits }
    }

    suspend fun setTrait(trait: Trait): Result<TraitWithIdentity> {
        val id = requireIdentity()
        val seq = beginOperation()
        val result = flagSmithApi.postTraits(IdentityAndTraits(id, listOf(trait)))
        if (result.isSuccess) {
            applyFlags(result.getOrThrow().flags, seq, persist = true)
        }
        return result.map { response ->
            TraitWithIdentity(
                key = response.traits.first().key,
                traitValue = response.traits.first().traitValue,
                identity = Identity(id)
            )
        }
    }

    suspend fun setTraits(traits: List<Trait>): Result<List<TraitWithIdentity>> {
        val id = requireIdentity()
        val seq = beginOperation()
        val result = flagSmithApi.postTraits(IdentityAndTraits(id, traits))
        if (result.isSuccess) {
            applyFlags(result.getOrThrow().flags, seq, persist = true)
        }
        return result.map { response ->
            response.traits.map { trait ->
                TraitWithIdentity(
                    key = trait.key,
                    traitValue = trait.traitValue,
                    identity = Identity(id)
                )
            }
        }
    }

    suspend fun getIdentity(transient: Boolean = false): Result<IdentityFlagsAndTraits> =
        flagSmithApi.getIdentityFlagsAndTraits(requireIdentity(), transient)

    suspend fun clearCache() {
        // Barrier: anything started before this call is superseded and can neither reach the flow
        // nor the disk snapshot afterwards. The flow and the TTL clock are reset too: once
        // stale-serve reads [flagsState], leaving it populated would mean "clear the cache"
        // doesn't clear.
        val barrier = stateMutex.withLock {
            lastAppliedSeq = seqCounter
            lastSuccessfulFetchAtMillis = 0L
            flagsState.value = defaultFlags
            seqCounter
        }
        try {
            flagsCache?.clear(barrier)
        } catch (e: Exception) {
            println("Error clearing last-known flags, ${e.stackTraceToString()}")
        }
    }

    fun restartRealtimeUpdates() {
        if (!enableRealtimeUpdates) {
            error("Real-time updates are not enabled for this instance")
        }
        if (!coroutineScope.isActive) {
            error("The SSE updates scope has been canceled")
        }
        sseUpdatesJob = eventService?.subscribeToEvents()
    }

    /**
     * Used to stop real-time updates and Analytics periodic updates, to restart call [restartRealtimeUpdates]
     */
    fun close() {
        sseUpdatesJob?.cancel()
        analytics?.stop()
    }

    private fun requireIdentity(): String = identity ?: error(IDENTITY_REQUIRED_MESSAGE)

    private suspend fun getFeatureFlag(
        featureId: String,
    ) = getFeatureFlags().map { flags ->
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

                // Now we can get the new values, which will automatically be emitted to the
                // flagUpdateFlow. forceRefresh bypasses the TTL gate: an SSE event means the
                // server state changed, so the cached document must not be served.
                getFeatureFlags(forceRefresh = true) { res ->
                    if (res.isFailure) {
                        // TODO: provide a logging mechanism
                        println("Error getting flags in SSE stream: ${res.exceptionOrNull()}")
                    } else {
                        println("Got flags due to SSE event: $event")
                    }
                }
            }
        }
        .launchIn(coroutineScope)

    companion object {
        const val DEFAULT_ENABLE_ANALYTICS = true
        const val DEFAULT_ANALYTICS_FLUSH_PERIOD_SECONDS = 10

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
