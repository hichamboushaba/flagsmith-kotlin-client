package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.entities.Trait
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.MockResponses
import com.flagsmith.mockResponses.mockFailureFor
import com.flagsmith.mockResponses.mockResponseFor
import io.ktor.util.date.getTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
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

private const val GATE_CACHE_DIR = "cache-ttl-gate"

/** How far past the 3600s TTL the injected clock is moved when a test needs a gate miss. */
private const val PAST_TTL_OFFSET_MILLIS = 4_000_000L

/**
 * Tests the in-memory TTL gate: within [FlagsmithCacheConfig.cacheTTL] of the last successful
 * fetch, `getFeatureFlags` must answer from memory without issuing an HTTP request. Request counts
 * are pinned with MockServer's VerificationTimes.exactly.
 */
class FlagsTtlGateTests {

    private lateinit var mockServer: ClientAndServer

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
    }

    @After
    fun tearDown() {
        mockServer.stop()
        File(GATE_CACHE_DIR).deleteRecursively()
    }

    private val defaultFlags = listOf(
        Flag(
            feature = Feature(id = 1L, name = "default-flag", type = "CONFIG"),
            enabled = false,
            featureStateValue = "default"
        )
    )

    private fun gateCacheConfig(acceptStaleCache: Boolean = true) = FlagsmithCacheConfig(
        enableCache = true,
        cacheDirectoryPath = GATE_CACHE_DIR,
        cacheTTL = 3600.seconds,
        acceptStaleCache = acceptStaleCache
    )

    private val baseUrl: String get() = "http://localhost:${mockServer.localPort}"

    private fun List<Flag>.withValueFlag(): Flag? = find { it.feature.name == "with-value" }

    @Test
    fun `g1 - second call within ttl is served from memory with exactly one request`() = runBlocking<Unit> {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())

        val first = instance.getFeatureFlags()
        val second = instance.getFeatureFlags()

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(756.0, second.getOrThrow().withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `g2 - gate expires past ttl and a real request is made`() = runBlocking<Unit> {
        var offset = 0L
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(),
            nowMillis = { getTimeMillis() + offset }
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        offset += PAST_TTL_OFFSET_MILLIS
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `g3 - forceRefresh bypasses the gate`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags(forceRefresh = true).isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `g4 - traits request bypasses the gate`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(MockResponses.setTraits)
        )
        assertTrue(instance.getFeatureFlags(traits = listOf(Trait("k", "v"))).isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(1)
        )
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `g5 - transient request bypasses the gate`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        mockServer.mockResponseFor(MockEndpoint.GET_TRANSIENT_IDENTITIES)
        assertTrue(instance.getFeatureFlags(transient = true).isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `g6 - caching disabled never gates`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person")
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `g7 - analytics still fire on gated calls`() = runBlocking<Unit> {
        val analyticsFactory = RecordingAnalyticsFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(),
            enableAnalytics = true,
            analyticsFactory = analyticsFactory
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)

        val first = instance.hasFeatureFlagSync("with-value")
        val second = instance.hasFeatureFlagSync("with-value")

        assertTrue(first.getOrThrow())
        assertTrue(second.getOrThrow())
        assertEquals(
            "trackEvent must fire on every call, gated or not",
            2,
            analyticsFactory.analytics.trackEventCount
        )
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `g8 - cold start within ttl serves the snapshot with zero requests`() = runBlocking<Unit> {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val first = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        assertTrue(first.getFeatureFlags().isSuccess)

        // Fresh instance, same scope, clock within TTL: primed from the snapshot and served by
        // the gate without any request.
        val second = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        val result = second.getFeatureFlags()

        assertTrue(result.isSuccess)
        assertEquals(756.0, result.getOrThrow().withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `g9 - stale serve on failure with acceptStaleCache`() = runBlocking<Unit> {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val first = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        assertTrue(first.getFeatureFlags().isSuccess)

        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        val second = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(acceptStaleCache = true),
            nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS }
        )

        val result = second.getFeatureFlags()
        assertTrue(result.isSuccess)
        assertEquals(756.0, result.getOrThrow().withValueFlag()?.featureStateValue)
    }

    @Test
    fun `g10 - an empty known-good document is stale-served as success, not defaults`() = runBlocking<Unit> {
        var offset = 0L
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(acceptStaleCache = true),
            defaultFlags = defaultFlags,
            nowMillis = { getTimeMillis() + offset }
        )
        // A successful response with zero flags is a known-good document.
        mockServer.`when`(
            request().withPath("/identities/").withMethod("GET")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"flags": [], "traits": []}""")
        )
        val first = instance.getFeatureFlags()
        assertTrue(first.isSuccess)
        assertTrue(first.getOrThrow().isEmpty())

        offset += PAST_TTL_OFFSET_MILLIS
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        val second = instance.getFeatureFlags()
        assertTrue(second.isSuccess)
        assertTrue(
            "An empty environment is a fact - it must not be answered with defaultFlags",
            second.getOrThrow().isEmpty()
        )
    }

    @Test
    fun `g11 - no stale serve when acceptStaleCache is disabled`() = runBlocking<Unit> {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val first = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        assertTrue(first.getFeatureFlags().isSuccess)

        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        val second = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(acceptStaleCache = false),
            defaultFlags = defaultFlags,
            nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS }
        )

        val result = second.getFeatureFlags()
        assertTrue(result.isSuccess)
        assertEquals(
            "Without acceptStaleCache the failure falls back to defaultFlags",
            "default",
            result.getOrThrow().first().featureStateValue
        )
    }

    @Test
    fun `g12 - clearCache resets the gate and the flow`() = runBlocking<Unit> {
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(),
            defaultFlags = defaultFlags
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        instance.clearCache()

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val result = instance.getFeatureFlags()

        assertTrue(result.isSuccess)
        assertEquals(756.0, result.getOrThrow().withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }
}
