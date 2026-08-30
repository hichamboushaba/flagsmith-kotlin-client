package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.mockDelayFor
import com.flagsmith.mockResponses.mockFailureFor
import com.flagsmith.mockResponses.mockResponseFor
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

private const val LAST_KNOWN_CACHE_DIR = "cache-last-known"

/**
 * Tests the cold-start priming behaviour: the last-known-flags snapshot must populate
 * [Flagsmith.flagUpdateFlow] synchronously (before any network resolution) on a fresh instance.
 */
class LastKnownFlagsTests {

    private lateinit var mockServer: ClientAndServer

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
        Awaitility.setDefaultTimeout(java.time.Duration.ofSeconds(30))
    }

    @After
    fun tearDown() {
        mockServer.stop()
        File(LAST_KNOWN_CACHE_DIR).deleteRecursively()
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
        acceptStaleCache: Boolean = true
    ) = Flagsmith(
        environmentKey = "",
        identity = identity,
        baseUrl = "http://localhost:${mockServer.localPort}",
        enableAnalytics = false,
        defaultFlags = defaultFlags,
        cacheConfig = FlagsmithCacheConfig(
            enableCache = true,
            cacheDirectoryPath = LAST_KNOWN_CACHE_DIR,
            cacheTTLSeconds = 3600,
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
        // Remove the Ktor HTTP cache so the request genuinely reaches the delayed mock and can
        // only succeed after the client's 4s timeout - the synchronous value below can then only
        // come from the last-known-flags snapshot.
        File("$LAST_KNOWN_CACHE_DIR/flagsmith").deleteRecursively()

        // The next response is delayed well beyond the client's 4s timeout, so it can never have
        // arrived by the time we do the synchronous assertion below.
        mockServer.mockDelayFor(MockEndpoint.GET_IDENTITIES)

        val freshInstance = flagsmith()
        val finished = AtomicBoolean(false)
        freshInstance.getFeatureFlags { finished.set(true) }

        val primed = freshInstance.flagUpdateFlow.value.withValueFlag()
        assertEquals(756.0, primed?.featureStateValue)

        await untilTrue finished
    }

    @Test
    fun testFlagUpdateFlowKeepsSnapshotWhenOffline() {
        populateSnapshot()
        // Remove ONLY the Ktor HTTP cache directory, leaving the last-known-flags snapshot (its
        // sibling) intact - otherwise the still-valid HTTP cache would serve the request and the
        // fetch would never fail.
        File("$LAST_KNOWN_CACHE_DIR/flagsmith").deleteRecursively()
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        val offlineInstance = flagsmith(defaultFlags = defaultFlags)

        // Primed synchronously from the snapshot before any call is made.
        assertEquals(756.0, offlineInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)

        // The fetch fails; the Result falls back to defaults, but the flow must keep the
        // last-known snapshot - defaults must never overwrite it.
        val result = runBlocking { offlineInstance.getFeatureFlagsSync() }
        assertTrue(result.isSuccess)
        assertEquals("default", result.getOrThrow().first().featureStateValue)

        assertEquals(756.0, offlineInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
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

        val freshInstance = flagsmith(defaultFlags = defaultFlags)
        assertNull(freshInstance.flagUpdateFlow.value.withValueFlag())
        assertEquals(1, freshInstance.flagUpdateFlow.value.size)
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
        // Remove the Ktor HTTP cache so the next fetch genuinely fails (see
        // testFlagUpdateFlowKeepsSnapshotWhenOffline) and the defaults fallback is exercised.
        File("$LAST_KNOWN_CACHE_DIR/flagsmith").deleteRecursively()
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        val failingInstance = flagsmith(defaultFlags = defaultFlags)
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
