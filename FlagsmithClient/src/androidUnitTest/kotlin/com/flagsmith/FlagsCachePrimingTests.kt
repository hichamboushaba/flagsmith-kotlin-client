package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.mockDelayFor
import com.flagsmith.mockResponses.mockFailureFor
import com.flagsmith.mockResponses.mockResponseFor
import io.ktor.util.date.getTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilTrue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val FLAGS_CACHE_DIR = "cache-priming"

/** How far past the 3600s TTL the injected clock is moved when a test needs a gate miss. */
private const val PAST_TTL_OFFSET_MILLIS = 4_000_000L

/**
 * Tests the cold-start priming behaviour: the last-known-flags snapshot must populate
 * [Flagsmith.flagUpdateFlow] synchronously (before any network resolution) on a fresh instance.
 */
class FlagsCachePrimingTests {

    private lateinit var mockServer: ClientAndServer

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
        Awaitility.setDefaultTimeout(java.time.Duration.ofSeconds(30))
    }

    @After
    fun tearDown() {
        mockServer.stop()
        File(FLAGS_CACHE_DIR).deleteRecursively()
    }

    private val defaultFlags = listOf(
        Flag(
            feature = Feature(id = 1L, name = "default-flag", type = "CONFIG"),
            enabled = false,
            featureStateValue = "default"
        )
    )

    private fun flagsmith(
        identity: String? = "person",
        defaultFlags: List<Flag> = emptyList(),
        acceptStaleCache: Boolean = true,
        nowMillis: () -> Long = ::getTimeMillis
    ) = testFlagsmith(
        baseUrl = "http://localhost:${mockServer.localPort}",
        identity = identity,
        defaultFlags = defaultFlags,
        nowMillis = nowMillis,
        cacheConfig = FlagsmithCacheConfig(
            enableCache = true,
            cacheDirectoryPath = FLAGS_CACHE_DIR,
            cacheTTL = 3600.seconds,
            acceptStaleCache = acceptStaleCache
        )
    )

    /** Drives a successful fetch against the mock server so the real write path populates the snapshot. */
    private fun populateSnapshot() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val result = runBlocking { flagsmith().getFeatureFlagsSync() }
        assertTrue(result.isSuccess)
        // getFeatureFlagsSync only resumes after applyFlags has completed, and applyFlags awaits
        // the disk write, so the snapshot is durably populated at this point.
    }

    private fun List<Flag>.withValueFlag(): Flag? = find { it.feature.name == "with-value" }

    @Test
    fun testFlagUpdateFlowIsPopulatedBeforeDelayedResponseArrives() {
        populateSnapshot()
        // The response is delayed well beyond the client's 4s timeout, and the fresh instance
        // runs on a clock past the TTL so the gate cannot answer either. The value read
        // synchronously below can therefore only have come from the snapshot.
        mockServer.mockDelayFor(MockEndpoint.GET_IDENTITIES)

        val freshInstance = flagsmith(nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS })
        val finished = AtomicBoolean(false)
        freshInstance.getFeatureFlags { finished.set(true) }

        val primed = freshInstance.flagUpdateFlow.value.withValueFlag()
        assertEquals(756.0, primed?.featureStateValue)

        await untilTrue finished
    }

    @Test
    fun testStaleServeKeepsSnapshotWhenOffline() {
        populateSnapshot()
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        // Clock past the TTL so the gate misses and the fetch is genuinely attempted; with
        // acceptStaleCache = true the failing fetch must return the snapshot as a success.
        val offlineInstance = flagsmith(
            defaultFlags = defaultFlags,
            nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS }
        )

        // Primed synchronously from the snapshot before any call is made.
        assertEquals(756.0, offlineInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)

        val result = runBlocking { offlineInstance.getFeatureFlagsSync() }
        assertTrue(result.isSuccess)
        assertEquals(
            "Stale-serve must return the last-known flags, not the defaults fallback",
            756.0,
            result.getOrThrow().withValueFlag()?.featureStateValue
        )

        assertEquals(756.0, offlineInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
    }

    @Test
    fun testOfflineWithoutStaleAcceptFallsBackToDefaults() {
        populateSnapshot()
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        // With acceptStaleCache = false the expired snapshot is rejected at prime time, so the
        // flow starts at defaultFlags and the failing fetch degrades to defaultFlags.
        val offlineInstance = flagsmith(
            defaultFlags = defaultFlags,
            acceptStaleCache = false,
            nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS }
        )

        assertNull(offlineInstance.flagUpdateFlow.value.withValueFlag())
        assertEquals("default-flag", offlineInstance.flagUpdateFlow.value.first().feature.name)

        val result = runBlocking { offlineInstance.getFeatureFlagsSync() }
        assertTrue(result.isSuccess)
        assertEquals("default", result.getOrThrow().first().featureStateValue)
    }

    @Test
    fun testDefaultsFallbackDoesNotOverwriteAPrimedFlow() {
        populateSnapshot()
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        // The snapshot is still within its TTL, so the flow primes with the server flags. Force
        // past the gate so the fetch is actually attempted, and fail it with stale-serve off:
        // the caller gets defaultFlags while the flow must keep the primed document.
        val instance = flagsmith(defaultFlags = defaultFlags, acceptStaleCache = false)
        assertEquals(756.0, instance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)

        val result = runBlocking { instance.getFeatureFlags(forceRefresh = true) }

        assertTrue(result.isSuccess)
        assertEquals("default", result.getOrThrow().first().featureStateValue)
        assertEquals(
            "A defaults fallback must not overwrite the flags already in the flow",
            756.0,
            instance.flagUpdateFlow.value.withValueFlag()?.featureStateValue
        )
    }

    @Test
    fun testFlowSeededWithDefaultFlagsWhenNoSnapshot() {
        val instance = flagsmith(defaultFlags = defaultFlags)

        assertEquals(1, instance.flagUpdateFlow.value.size)
        assertEquals("default-flag", instance.flagUpdateFlow.value.first().feature.name)
    }

    @Test
    fun testSnapshotIgnoredForDifferentIdentity() {
        populateSnapshot()

        val otherIdentityInstance = flagsmith(identity = "other", defaultFlags = defaultFlags)

        assertNull(otherIdentityInstance.flagUpdateFlow.value.withValueFlag())
        assertEquals("default-flag", otherIdentityInstance.flagUpdateFlow.value.first().feature.name)
    }

    @Test
    fun testSnapshotIgnoredForEnvironmentScopeAfterIdentityFetch() {
        populateSnapshot()

        val environmentInstance = flagsmith(identity = null, defaultFlags = defaultFlags)

        assertNull(environmentInstance.flagUpdateFlow.value.withValueFlag())
        assertEquals("default-flag", environmentInstance.flagUpdateFlow.value.first().feature.name)
    }

    @Test
    fun testTransientRequestIsNotPersisted() {
        mockServer.mockResponseFor(MockEndpoint.GET_TRANSIENT_IDENTITIES)
        val result = runBlocking { flagsmith().getFeatureFlagsSync(transient = true) }
        assertTrue(result.isSuccess)

        // Assert on the identity of the flag, not just its absence: the transient document also
        // holds exactly one flag with no "with-value" entry, so a size check alone would pass
        // even if the transient response had been persisted.
        val freshInstance = flagsmith(defaultFlags = defaultFlags)
        assertEquals(1, freshInstance.flagUpdateFlow.value.size)
        assertEquals(
            "The flow must prime from defaultFlags, not from the transient document",
            "default-flag",
            freshInstance.flagUpdateFlow.value.first().feature.name
        )
    }

    @Test
    fun testClearCacheWipesSnapshot() {
        populateSnapshot()

        val instance = flagsmith()
        runBlocking { instance.clearCache() }

        val freshInstance = flagsmith()
        assertTrue(freshInstance.flagUpdateFlow.value.isEmpty())
    }

    @Test
    fun testDefaultsFallbackDoesNotOverwriteSnapshotOnDisk() {
        populateSnapshot()
        // The failing instance runs past the TTL with acceptStaleCache = false, so neither the
        // gate nor stale-serve can short-circuit the defaults path this test exists to cover.
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        val failingInstance = flagsmith(
            defaultFlags = defaultFlags,
            acceptStaleCache = false,
            nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS }
        )
        val result = runBlocking { failingInstance.getFeatureFlagsSync() }
        assertTrue(result.isSuccess)

        val freshInstance = flagsmith(defaultFlags = defaultFlags)
        assertEquals(756.0, freshInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
        assertEquals(
            "Defaults must not reach the disk snapshot - the primed flow holds the server flags, not the fallback",
            3,
            freshInstance.flagUpdateFlow.value.size
        )
    }
}
