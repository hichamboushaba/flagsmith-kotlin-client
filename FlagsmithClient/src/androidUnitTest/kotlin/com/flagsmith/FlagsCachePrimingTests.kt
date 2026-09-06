package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.entities.Trait
import com.flagsmith.internal.FlagsCache
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.MockResponses
import com.flagsmith.mockResponses.mockFailureFor
import com.flagsmith.mockResponses.mockResponseFor
import io.ktor.util.date.getTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes
import java.io.File

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
        transientIdentity: Boolean = false,
        defaultFlags: List<Flag> = emptyList(),
        acceptStaleCache: Boolean = true,
        nowMillis: () -> Long = ::getTimeMillis
    ) = testFlagsmith(
        baseUrl = "http://localhost:${mockServer.localPort}",
        identity = identity,
        transientIdentity = transientIdentity,
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
        val result = runBlocking { flagsmith().refreshSync() }
        assertTrue(result.isSuccess)
    }

    private fun List<Flag>.withValueFlag(): Flag? = find { it.feature.name == "with-value" }

    @Test
    fun testOfflineWithoutStaleAcceptFallsBackToDefaults() {
        populateSnapshot()
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        // With acceptStaleCache = false the expired snapshot is rejected at prime time, so the
        // flow starts at defaultFlags; the failing refresh() then simply fails, leaving reads on
        // defaultFlags rather than degrading to them itself.
        val offlineInstance = flagsmith(
            defaultFlags = defaultFlags,
            acceptStaleCache = false,
            nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS }
        )

        assertNull(offlineInstance.flagUpdateFlow.value.withValueFlag())
        assertEquals("default-flag", offlineInstance.flagUpdateFlow.value.first().feature.name)

        val result = runBlocking { offlineInstance.refreshSync() }
        assertTrue(result.isFailure)
        assertEquals("default-flag", offlineInstance.flagUpdateFlow.value.first().feature.name)
    }

    @Test
    fun testSnapshotIgnoredForDifferentIdentity() {
        populateSnapshot()

        val otherIdentityInstance = flagsmith(identity = "other", defaultFlags = defaultFlags)

        assertNull(otherIdentityInstance.flagUpdateFlow.value.withValueFlag())
        assertEquals("default-flag", otherIdentityInstance.flagUpdateFlow.value.first().feature.name)
    }

    @Test
    fun testTransientSnapshotIsPersistedButNotServedToANonTransientCall() {
        mockServer.mockResponseFor(MockEndpoint.GET_TRANSIENT_IDENTITIES)
        val transientInstance = flagsmith(transientIdentity = true)
        val result = runBlocking { transientInstance.refreshSync() }
        assertTrue(result.isSuccess)

        // Transient-derived documents are persisted like any other, so the flow primes with the
        // transient snapshot on a fresh, non-transient instance too - priming never filters by key...
        val freshInstance = flagsmith()
        assertEquals(1, freshInstance.flagUpdateFlow.value.size)
        assertEquals(
            "The flow must prime from the transient snapshot - transient is part of the request key",
            "no-value",
            freshInstance.flagUpdateFlow.value.first().feature.name
        )
        // ...but under the "post:transient:<digest>" key: a non-transient instance must not reuse
        // it and has to fetch the identity's real flags.
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val nonTransient = runBlocking { freshInstance.refreshSync() }

        assertTrue(nonTransient.isSuccess)
        assertEquals(756.0, freshInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun testWarmColdStartDominatesBeforeAnySetTraits() {
        // killed by: dominance inverted
        // Populate the snapshot via the POST path (setTraits + refresh) so it is keyed by a real
        // trait digest, not EMPTY_DIGEST.
        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """{"flags": [{"feature_state_value": "warm-value", """ +
                        """"feature": {"type": "STANDARD", "name": "with-value", "id": 35507}, """ +
                        """"enabled": true}], "traits": []}"""
                )
        )
        val instance = flagsmith()
        instance.setTraits(listOf(Trait("k", "v")))
        assertTrue(runBlocking { instance.refreshSync() }.isSuccess)

        // Fresh instance, same scope, clock within TTL, refresh() called before any setTraits at
        // all: dominance means the trait-less request still reuses the traited snapshot rather
        // than transiently fetching (and caching) a trait-less document ahead of the app
        // re-establishing its trait projection.
        val freshInstance = flagsmith()
        val result = runBlocking { freshInstance.refreshSync() }

        assertTrue(result.isSuccess)
        assertEquals("warm-value", freshInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `cold start - traits matching the snapshot make zero requests`() {
        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(MockResponses.getIdentities)
        )
        val traits = listOf(Trait("k", "v"))
        val instance = flagsmith()
        instance.setTraits(traits)
        val result = runBlocking { instance.refreshSync() }
        assertTrue(result.isSuccess)

        // Fresh instance, same scope, POST-derived snapshot, clock within TTL, same traits set:
        // the refresh is answered from the snapshot without any request.
        val freshInstance = flagsmith()
        freshInstance.setTraits(traits)
        val second = runBlocking { freshInstance.refreshSync() }

        assertTrue(second.isSuccess)
        assertEquals(756.0, freshInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
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
    fun testUndecodableKeyedSnapshotPrimesButNeverGates() {
        // killed by: gate always hits
        // A snapshot whose key the current build cannot parse must still be usable for priming
        // without ever satisfying the gate. Written straight through FlagsCache, since nothing in
        // the public surface can produce such a key.
        val staleFlags = listOf(
            Flag(
                feature = Feature(id = 1L, name = "with-value", type = "STANDARD"),
                enabled = true,
                featureStateValue = 999.0
            )
        )
        val staleCache = FlagsCache(
            baseDirectory = FLAGS_CACHE_DIR.toPath(),
            scope = FlagsCache.Scope(
                baseUrl = "http://localhost:${mockServer.localPort}",
                environmentKey = "",
                identity = "person"
            ),
            ttl = 3600.seconds,
            acceptStale = true,
        )
        runBlocking { staleCache.write(staleFlags, seq = 1, fetchedAtMillis = getTimeMillis(), requestKey = "get") }

        // The undecodable key still primes the flow synchronously...
        val instance = flagsmith()
        assertEquals(999.0, instance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)

        // ...but an unparseable key can never satisfy a gated call: the first call must
        // still fetch, exactly once.
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val result = runBlocking { instance.refreshSync() }

        assertTrue(result.isSuccess)
        assertEquals(756.0, instance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
        )
    }
}
