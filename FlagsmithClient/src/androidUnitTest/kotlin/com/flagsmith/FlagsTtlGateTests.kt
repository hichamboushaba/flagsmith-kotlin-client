package com.flagsmith

import com.flagsmith.entities.FlagEvent
import com.flagsmith.entities.Flag
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
 * fetch, [Flagsmith.refresh] must answer without issuing an HTTP request. Request counts are
 * pinned with MockServer's VerificationTimes.exactly.
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

    private fun gateCacheConfig(acceptStaleCache: Boolean = true) = FlagsmithCacheConfig(
        enableCache = true,
        cacheDirectoryPath = GATE_CACHE_DIR,
        cacheTTL = 3600.seconds,
        acceptStaleCache = acceptStaleCache
    )

    private val baseUrl: String get() = "http://localhost:${mockServer.localPort}"

    private fun List<Flag>.withValueFlag(): Flag? = find { it.feature.name == "with-value" }

    private fun mockUnlimitedIdentitiesResponse() {
        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(MockResponses.getIdentities)
        )
    }

    @Test
    fun `past ttl a trait-less refresh still fetches and overwrites the key`() = runBlocking<Unit> {
        // killed by: gate never hits
        var offset = 0L
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(),
            nowMillis = { getTimeMillis() + offset }
        )
        mockUnlimitedIdentitiesResponse()
        instance.setTraits(listOf(Trait("k", "v")))
        assertTrue(instance.refreshSync().isSuccess)

        // Past the TTL the age check misses regardless of key, so the trait-less refresh fetches,
        // and the overwritten snapshot is keyed by the request that produced it (EMPTY_DIGEST).
        offset += PAST_TTL_OFFSET_MILLIS
        instance.removeTrait("k")
        assertTrue(instance.refreshSync().isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
        val expectedKey = com.flagsmith.internal.RequestKey.Identity(
            transient = false,
            digest = com.flagsmith.internal.RequestKey.EMPTY_DIGEST
        ).encode()
        val snapshotJson = File(GATE_CACHE_DIR).walkTopDown().first { it.extension == "json" }.readText()
        assertTrue(
            "The overwritten snapshot must be keyed by the trait-less request, not the stale traited key",
            snapshotJson.contains(""""requestKey":"$expectedKey"""")
        )
    }

    @Test
    fun `setTraits(emptyList()) is the same request as never calling setTraits`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        mockUnlimitedIdentitiesResponse()

        assertTrue(instance.refreshSync().isSuccess)
        instance.setTraits(emptyList())
        assertTrue(instance.refreshSync().isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `caching disabled never gates`() = runBlocking<Unit> {
        val instance = testFlagsmith(baseUrl, identity = "person")
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.refreshSync().isSuccess)
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.refreshSync().isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `analytics fire on every read, gated or not`() = runBlocking<Unit> {
        val analyticsFactory = RecordingAnalyticsFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(),
            enableAnalytics = true,
            analyticsFactory = analyticsFactory
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.refreshSync().isSuccess)

        val first = instance.hasFeatureFlag("with-value")
        val second = instance.hasFeatureFlag("with-value")

        assertTrue(first)
        assertTrue(second)
        assertEquals(
            "trackEvent must fire on every read",
            2,
            analyticsFactory.analytics.trackEventCount
        )
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `a failed realtime refresh does not leave stale flags gated`() = runBlocking<Unit> {
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(acceptStaleCache = false),
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi
        )

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.refreshSync().isSuccess)

        // The event says the server changed, but the refresh it triggers fails.
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        eventApi.api.events.emit(FlagEvent(updatedAt = 1.0))
        await untilAsserted {
            mockServer.verify(
                request().withPath("/identities/").withMethod("POST"),
                VerificationTimes.exactly(2)
            )
        }

        // Still inside the TTL of the first fetch. The gate must not hand back the document the
        // event already told us is superseded - it has to go to the server again.
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val afterFailedRefresh = instance.refreshSync()

        assertTrue(afterFailedRefresh.isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(3)
        )

        // ...and that successful fetch clears the stale mark, so the gate works again.
        assertTrue(instance.refreshSync().isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(3)
        )
        instance.close()
    }

    @Test
    fun `close releases the http clients`() = runBlocking<Unit> {
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.refreshSync().isSuccess)

        instance.close()

        assertTrue("The event stream's client must be released too", eventApi.api.closed)

        // Reuse is rejected up front rather than only once the TTL happens to expire: with the
        // gate still warm, a closed instance would otherwise keep answering successfully from
        // memory. Called directly (not refreshSync) since this must throw synchronously out of
        // the suspend function, not be caught into a Result.failure by the callback wrapper.
        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { instance.refresh() }
        }
        assertEquals("This Flagsmith instance has been closed", exception.message)
    }

    @Test
    fun `a response predating a realtime event does not clear the stale mark`() = runBlocking<Unit> {
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(acceptStaleCache = false),
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi
        )

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES) // request 1
        assertTrue(instance.refreshSync().isSuccess)

        // Request 2: still in flight when the event arrives, so its response was generated before
        // the change the event announces. Ordered on the server receiving it, not on a sleep.
        mockServer.`when`(request().withPath("/identities/").withMethod("POST"), Times.once())
            .respond(
                response()
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody(MockResponses.getIdentities)
                    .withDelay(TimeUnit.MILLISECONDS, 1500)
            )
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES) // request 3: the event's refresh

        val inFlight = async(Dispatchers.IO) { instance.refreshSync(force = true) }
        await untilAsserted {
            mockServer.verify(
                request().withPath("/identities/").withMethod("POST"),
                VerificationTimes.exactly(2)
            )
        }

        eventApi.api.events.emit(FlagEvent(updatedAt = 1.0))
        await untilAsserted {
            mockServer.verify(
                request().withPath("/identities/").withMethod("POST"),
                VerificationTimes.exactly(3)
            )
        }

        // The in-flight fetch now lands successfully. It predates the event, so it must not
        // restore gate eligibility - otherwise the superseded document serves reads for a TTL.
        assertTrue(inFlight.await().isSuccess)

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES) // request 4
        assertTrue(instance.refreshSync().isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(4)
        )
        instance.close()
    }

    @Test
    fun `a backwards clock correction does not lock the gate`() = runBlocking<Unit> {
        // The device clock starts a year ahead, so the first fetch is stamped in the future.
        var clock = getTimeMillis() + ONE_YEAR_MILLIS
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = gateCacheConfig(),
            nowMillis = { clock }
        )
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.refreshSync().isSuccess)

        // Clock corrected. The gate must treat a future-dated fetch as a miss and refetch. If it
        // clamped the negative age to zero instead, it would serve that document without ever
        // restamping it - suppressing every request until the real clock caught up a year later.
        clock -= ONE_YEAR_MILLIS
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(instance.refreshSync().isSuccess)

        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )

        // ...and the refetch restamped the clock, so the gate is healthy again rather than
        // permanently disabled.
        assertTrue(instance.refreshSync().isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `a transient response advances the gate for identically keyed calls`() = runBlocking<Unit> {
        val transientInstance = testFlagsmith(
            baseUrl,
            identity = "person",
            transientIdentity = true,
            cacheConfig = gateCacheConfig()
        )

        mockServer.mockResponseFor(MockEndpoint.GET_TRANSIENT_IDENTITIES)
        assertTrue(transientInstance.refreshSync().isSuccess)

        // Cached under the "post:transient:<digest>" key, so a second refresh on the same
        // instance is served from memory.
        val again = transientInstance.refreshSync()

        assertTrue(again.isSuccess)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
        )

        // A non-transient instance sharing the same cache scope has a different key and must
        // fetch rather than reuse the transient document.
        val ordinaryInstance = testFlagsmith(baseUrl, identity = "person", cacheConfig = gateCacheConfig())
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val ordinary = ordinaryInstance.refreshSync()

        assertTrue(ordinary.isSuccess)
        assertEquals(756.0, ordinaryInstance.flagUpdateFlow.value.withValueFlag()?.featureStateValue)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
    }
}
