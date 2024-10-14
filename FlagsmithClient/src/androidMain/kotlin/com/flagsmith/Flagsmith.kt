package com.flagsmith

import android.content.Context
import android.util.Log
import com.flagsmith.entities.*
import com.flagsmith.internal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Flagsmith
 *
 * The main interface to all of the Flagsmith functionality
 *
 * @property environmentKey Take this API key from the Flagsmith dashboard and pass here
 * @property baseUrl By default we'll connect to the Flagsmith backend, but if you self-host you can configure here
 * @property context The current context is required to use the Flagsmith Analytics functionality
 * @property enableAnalytics Enable analytics - default true
 * @property analyticsFlushPeriod The period in seconds between attempts by the Flagsmith SDK to push analytic events to the server
 * @constructor Create empty Flagsmith
 */
class Flagsmith internal constructor(
    private val environmentKey: String,
    private val baseUrl: String = "https://edge.api.flagsmith.com/api/v1/",
    private val eventSourceBaseUrl: String = "https://realtime.flagsmith.com/",
    private val context: Context? = null,
    private val enableAnalytics: Boolean = DEFAULT_ENABLE_ANALYTICS,
    private val enableRealtimeUpdates: Boolean = false,
    private val analyticsFlushPeriod: Int = DEFAULT_ANALYTICS_FLUSH_PERIOD_SECONDS,
    private val cacheConfig: FlagsmithCacheConfig = FlagsmithCacheConfig(),
    private val defaultFlags: List<Flag> = emptyList(),
    private val requestTimeoutSeconds: Long = 4L,
    private val readTimeoutSeconds: Long = 6L,
    private val writeTimeoutSeconds: Long = 6L,
    override var lastFlagFetchTime: Double = 0.0, // from FlagsmithEventTimeTracker
    private val flagsmithApiFactory: FlagsmithApiFactory
) : FlagsmithEventTimeTracker {
    private val eventService: FlagsmithEventService? = if (!enableRealtimeUpdates) null else FlagsmithEventService(
        eventSourceBaseUrl = eventSourceBaseUrl,
        json = defaultJson,
        environmentKey = environmentKey
    ) { event ->
        if (event.isSuccess) {
            lastEventUpdate = event.getOrNull()?.updatedAt ?: lastEventUpdate

            // Check whether this event is anything new
            if (lastEventUpdate > lastFlagFetchTime) {
                // First evict the cache otherwise we'll be stuck with the old values
                cache?.invalidate()
                lastFlagFetchTime = lastEventUpdate

                // Now we can get the new values, which will automatically be emitted to the flagUpdateFlow
                getFeatureFlags(lastUsedIdentity) { res ->
                    if (res.isFailure) {
                        Log.e(
                            "Flagsmith",
                            "Error getting flags in SSE stream: ${res.exceptionOrNull()}"
                        )
                    } else {
                        Log.i("Flagsmith", "Got flags due to SSE event: $event")
                    }
                }
            }
        }
    }

    private val flagSmithApi: FlagsmithApi
    private val cache: HttpCache?
    private val analytics: FlagsmithAnalytics?

    private var lastUsedIdentity: String? = null

    // The last time we got an event from the SSE stream or via the API
    private var lastEventUpdate: Double = 0.0

    /** Stream of flag updates from the SSE stream if enabled */
    val flagUpdateFlow = MutableStateFlow<List<Flag>>(listOf())

    init {
        if (cacheConfig.enableCache && context == null) {
            throw IllegalArgumentException("Flagsmith requires a context to use the cache feature")
        }
        flagsmithApiFactory.create(
            baseUrl = baseUrl,
            environmentKey = environmentKey,
            cacheConfig = cacheConfig,
            requestTimeoutSeconds = requestTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds,
            writeTimeoutSeconds = writeTimeoutSeconds,
            timeTracker = this,
            json = defaultJson
        ).let { (api, cache) ->
            flagSmithApi = api
            this.cache = cache
        }

        analytics = if (enableAnalytics) {
            if (context == null || context.applicationContext == null) {
                throw IllegalArgumentException("Flagsmith requires a context to use the analytics feature")
            }
            FlagsmithAnalytics(context, flagSmithApi, analyticsFlushPeriod)
        } else {
            null
        }
    }

    companion object {
        const val DEFAULT_ENABLE_ANALYTICS = true
        const val DEFAULT_ANALYTICS_FLUSH_PERIOD_SECONDS = 10
    }

    suspend fun getFeatureFlags(
        identity: String? = null,
        traits: List<Trait>? = null,
        transient: Boolean = false
    ): Result<List<Flag>> {
        // Save the last used identity as we'll refresh with this if we get update events
        lastUsedIdentity = identity

        return if (identity != null) {
            if (traits != null) {
                flagSmithApi.postTraits(IdentityAndTraits(identity, traits, transient))
                    .map { it.flags }
                    .also { lastUsedIdentity = identity }
            } else {
                flagSmithApi.getIdentityFlagsAndTraits(identity, transient)
                    .map { it.flags }
                    .also { flagUpdateFlow.tryEmit(it.getOrNull() ?: emptyList()) }
            }
        } else {
            if (traits != null) {
                throw IllegalArgumentException("Cannot set traits without an identity");
            } else {
                flagSmithApi.getFlags()
                    .recover { defaultFlags }
                    .also { res ->
                        flagUpdateFlow.tryEmit(res.getOrNull() ?: emptyList())
                    }
            }
        }
    }

    fun getFeatureFlags(
        identity: String? = null,
        traits: List<Trait>? = null,
        transient: Boolean = false,
        result: (Result<List<Flag>>) -> Unit
    ) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(getFeatureFlags(identity, traits, transient))
        }
    }

    suspend fun hasFeatureFlag(
        featureId: String,
        identity: String? = null
    ): Result<Boolean> {
        return getFeatureFlag(featureId, identity).map { flag -> flag != null }
    }

    fun hasFeatureFlag(
        featureId: String,
        identity: String? = null,
        result: (Result<Boolean>) -> Unit
    ) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(hasFeatureFlag(featureId, identity))
        }
    }

    suspend fun getValueForFeature(
        featureId: String,
        identity: String? = null
    ) = getFeatureFlag(featureId, identity).map { flag -> flag?.featureStateValue }

    fun getValueForFeature(
        featureId: String,
        identity: String? = null,
        result: (Result<Any?>) -> Unit
    ) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(getValueForFeature(featureId, identity))
        }
    }

    suspend fun getTrait(id: String, identity: String): Result<Trait?> {
        return flagSmithApi.getIdentityFlagsAndTraits(identity)
            .map { value -> value.traits.find { it.key == id } }
            .also { lastUsedIdentity = identity }
    }

    fun getTrait(id: String, identity: String, result: (Result<Trait?>) -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(getTrait(id, identity))
        }
    }

    suspend fun getTraits(identity: String): Result<List<Trait>> {
        return flagSmithApi.getIdentityFlagsAndTraits(identity)
            .map { value -> value.traits }
            .also { lastUsedIdentity = identity }
    }

    fun getTraits(identity: String, result: (Result<List<Trait>>) -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(getTraits(identity))
        }
    }

    suspend fun setTrait(trait: Trait, identity: String): Result<TraitWithIdentity> {
        return flagSmithApi.postTraits(IdentityAndTraits(identity, listOf(trait)))
            .map { response ->
                TraitWithIdentity(
                    key = response.traits.first().key,
                    traitValue = response.traits.first().traitValue,
                    identity = Identity(identity)
                )
            }
    }

    fun setTrait(trait: Trait, identity: String, result: (Result<TraitWithIdentity>) -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(setTrait(trait, identity))
        }
    }

    suspend fun setTraits(traits: List<Trait>, identity: String): Result<List<TraitWithIdentity>> {
        return flagSmithApi.postTraits(IdentityAndTraits(identity, traits))
            .map { response ->
                response.traits.map { trait ->
                    TraitWithIdentity(
                        key = trait.key,
                        traitValue = trait.traitValue,
                        identity = Identity(identity)
                    )
                }
            }
    }

    fun setTraits(traits: List<Trait>, identity: String, result: (Result<List<TraitWithIdentity>>) -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(setTraits(traits, identity))
        }
    }

    suspend fun getIdentity(identity: String, transient: Boolean = false): Result<IdentityFlagsAndTraits> =
        flagSmithApi.getIdentityFlagsAndTraits(identity, transient)
            .also { lastUsedIdentity = identity }

    fun getIdentity(identity: String, transient: Boolean = false, result: (Result<IdentityFlagsAndTraits>) -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            result(getIdentity(identity, transient))
        }
    }

    fun clearCache() {
        try {
            cache?.invalidate()
        } catch (e: IOException) {
            Log.e("Flagsmith", "Error clearing cache", e)
        }
    }

    private suspend fun getFeatureFlag(
        featureId: String,
        identity: String?,
    ) = getFeatureFlags(identity).map { flags ->
        val foundFlag = flags.find { flag -> flag.feature.name == featureId && flag.enabled }
        analytics?.trackEvent(featureId)
        foundFlag
    }.also { lastUsedIdentity = identity }
}
