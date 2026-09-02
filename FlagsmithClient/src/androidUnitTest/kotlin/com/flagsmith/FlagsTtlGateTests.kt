package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.entities.FlagEvent
import com.flagsmith.entities.Trait
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.MockResponses
import com.flagsmith.mockResponses.mockFailureFor
import com.flagsmith.mockResponses.mockResponseFor
import io.ktor.util.date.getTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.matchers.Times
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes
import java.io.File
import java.util.concurrent.TimeUnit

private const val GATE_CACHE_DIR = "cache-ttl-gate"

/** How far past the 3600s TTL the injected clock is moved when a test needs a gate miss. */
private const val PAST_TTL_OFFSET_MILLIS = 4_000_000L

/** A device clock that is wrong by a year, to exercise clock corrections. */
private const val ONE_YEAR_MILLIS = 365L * 24 * 3600 * 1000

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
        // A successful response with zero flags is a known-good document. Times.once() matters:
        // an unlimited expectation is matched ahead of the failure registered below, which would
        // make the "failed" fetch of the second call succeed and pin nothing.
        mockServer.`when`(
            request().withPath("/identities/").withMethod("GET"),
            Times.once()
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
        // Proves the second call really did go to the server and really did fail, so the
        // assertion above came from the stale fallback rather than from a second success.
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
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

    @Test
    fun `g17 - a failed realtime refresh does not leave stale flags gated`() = runBlocking<Unit> {
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(acceptStaleCache = false),
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi
        )

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        // The event says the server changed, but the refresh it triggers fails.
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        eventApi.api.events.emit(FlagEvent(updatedAt = 1.0))
        await untilAsserted {
            mockServer.verify(
                request().withPath("/identities/").withMethod("GET"),
                VerificationTimes.exactly(2)
            )
        }

        // Still inside the TTL of the first fetch. The gate must not hand back the document the
        // event already told us is superseded - it has to go to the server again.
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val afterFailedRefresh = instance.getFeatureFlags()

        assertTrue(afterFailedRefresh.isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(3)
        )

        // ...and that successful fetch clears the stale mark, so the gate works again.
        assertTrue(instance.getFeatureFlags().isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(3)
        )
        instance.close()
    }

    @Test
    fun `g18 - close releases the http clients`() = runBlocking<Unit> {
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        instance.close()

        assertTrue("The event stream's client must be released too", eventApi.api.closed)

        // Reuse is rejected up front rather than only once the TTL happens to expire: with the
        // gate still warm, a closed instance would otherwise keep answering successfully from
        // memory. The callback wrappers turn this into Result.failure.
        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { instance.getFeatureFlags() }
        }
        assertEquals("This Flagsmith instance has been closed", exception.message)
    }

    @Test
    fun `g19 - a response predating a realtime event does not clear the stale mark`() = runBlocking<Unit> {
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(acceptStaleCache = false),
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi
        )

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES) // request 1
        assertTrue(instance.getFeatureFlags().isSuccess)

        // Request 2: still in flight when the event arrives, so its response was generated before
        // the change the event announces. Ordered on the server receiving it, not on a sleep.
        mockServer.`when`(request().withPath("/identities/").withMethod("GET"), Times.once())
            .respond(
                response()
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody(MockResponses.getIdentities)
                    .withDelay(TimeUnit.MILLISECONDS, 1500)
            )
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES) // request 3: the event's refresh

        val inFlight = async(Dispatchers.IO) { instance.getFeatureFlags(forceRefresh = true) }
        await untilAsserted {
            mockServer.verify(
                request().withPath("/identities/").withMethod("GET"),
                VerificationTimes.exactly(2)
            )
        }

        eventApi.api.events.emit(FlagEvent(updatedAt = 1.0))
        await untilAsserted {
            mockServer.verify(
                request().withPath("/identities/").withMethod("GET"),
                VerificationTimes.exactly(3)
            )
        }

        // The in-flight fetch now lands successfully. It predates the event, so it must not
        // restore gate eligibility - otherwise the superseded document serves reads for a TTL.
        assertTrue(inFlight.await().isSuccess)

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES) // request 4
        assertTrue(instance.getFeatureFlags().isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(4)
        )
        instance.close()
    }

    @Test
    fun `g13 - a backwards clock correction does not lock the gate`() = runBlocking<Unit> {
        // The device clock starts a year ahead, so the first fetch is stamped in the future.
        var clock = getTimeMillis() + ONE_YEAR_MILLIS
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(),
            nowMillis = { clock }
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        // Clock corrected. The gate must treat a future-dated fetch as a miss and refetch. If it
        // clamped the negative age to zero instead, it would serve that document without ever
        // restamping it - suppressing every request until the real clock caught up a year later.
        clock -= ONE_YEAR_MILLIS
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )

        // ...and the refetch restamped the clock, so the gate is healthy again rather than
        // permanently disabled.
        assertTrue(instance.getFeatureFlags().isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `g14 - a transient response does not advance the gate`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())

        mockServer.mockResponseFor(MockEndpoint.GET_TRANSIENT_IDENTITIES)
        assertTrue(instance.getFeatureFlags(transient = true).isSuccess)

        // A transient identity is not this identity's stored state, so the next ordinary call
        // must still go to the server rather than be gated on it.
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val ordinary = instance.getFeatureFlags()

        assertTrue(ordinary.isSuccess)
        assertEquals(756.0, ordinary.getOrThrow().withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `g16 - a transient response is never served by a later gated call`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        // Still within the TTL of the ordinary fetch above. This overwrites flagUpdateFlow with a
        // transient document, but must not become the document the gate hands out.
        mockServer.mockResponseFor(MockEndpoint.GET_TRANSIENT_IDENTITIES)
        val transient = instance.getFeatureFlags(transient = true)
        assertTrue(transient.isSuccess)
        assertNull(transient.getOrThrow().withValueFlag())

        val gated = instance.getFeatureFlags()

        assertTrue(gated.isSuccess)
        assertEquals(
            "The gate must serve the last cacheable document, not the transient one",
            756.0,
            gated.getOrThrow().withValueFlag()?.featureStateValue
        )
        mockServer.verify(
            request().withPath("/identities/").withMethod("GET"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `g15 - caching disabled also disables stale serving`() = runBlocking<Unit> {
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = FlagsmithCacheConfig(enableCache = false, acceptStaleCache = true),
            defaultFlags = defaultFlags
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.getFeatureFlags().isSuccess)

        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        val result = instance.getFeatureFlags()

        assertTrue(result.isSuccess)
        assertEquals(
            "acceptStaleCache must not resurrect in-memory flags when the cache is disabled",
            "default",
            result.getOrThrow().first().featureStateValue
        )
    }
}
