package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.entities.Trait
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.mockFailureFor
import io.ktor.util.date.getTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.JsonBody
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes
import java.io.File

private const val E2E_CACHE_DIR = "cache-e2e"

/** How far past the 3600s TTL the injected clock is moved when a test needs a gate miss. */
private const val PAST_TTL_OFFSET_MILLIS = 4_000_000L

// Every marker is distinct from every other marker, so no assertion can pass by coincidence:
// the defaults, the snapshot, and each server generation must never share a value.
private const val DEFAULT_MARKER = "default-marker"
private const val SNAPSHOT_MARKER = "snapshot-marker"
private const val SERVER_FIRST_MARKER = "server-first-marker"
private const val SERVER_SECOND_MARKER = "server-second-marker"
private const val TRAITS_A_MARKER = "traits-A-marker"
private const val TRAITS_B_MARKER = "traits-B-marker"

/**
 * End-to-end walks of the sequence a real app performs - setTraits, refresh, synchronous reads -
 * across restarts, offline cold starts and TTL expiry. Every HTTP request count is pinned with
 * MockServer's VerificationTimes.exactly, and every marker value is distinct, so a test can only
 * pass if the whole sequence behaves as claimed.
 */
class EndToEndTests {

    private lateinit var mockServer: ClientAndServer

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
    }

    @After
    fun tearDown() {
        mockServer.stop()
        File(E2E_CACHE_DIR).deleteRecursively()
    }

    private val baseUrl: String get() = "http://localhost:${mockServer.localPort}"

    private val defaultFlags = listOf(
        Flag(
            feature = Feature(id = 1L, name = "with-value", type = "CONFIG"),
            enabled = false,
            featureStateValue = DEFAULT_MARKER
        )
    )

    private fun cacheConfig(dir: String = E2E_CACHE_DIR) = FlagsmithCacheConfig(
        enableCache = true,
        cacheDirectoryPath = dir,
        cacheTTL = 3600.seconds,
        acceptStaleCache = true
    )

    /**
     * Registers a POST /identities/ that answers with a "with-value" of [value]. Unlimited by
     * default, so a wrongly-issued repeat POST succeeds and shows up in the count verification
     * rather than in a 404.
     */
    private fun mockPostResponse(value: String, times: Times = Times.unlimited()) {
        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST"),
            times
        ).respond(successBody(value))
    }

    private fun successBody(value: String) = response()
        .withStatusCode(200)
        .withContentType(MediaType.APPLICATION_JSON)
        .withBody(
            """{"flags": [{"feature_state_value": "$value", """ +
                """"feature": {"type": "STANDARD", "name": "with-value", "id": 35507}, """ +
                """"enabled": true}], "traits": []}"""
        )

    @Test
    fun `A - the app's real sequence - setTraits, refresh, read, refresh again`() = runBlocking<Unit> {
        // killed by: the gate always misses - the repeat refresh() then issues a second POST and
        // the exactly(1) verification below fires.
        mockPostResponse(SERVER_FIRST_MARKER)
        val instance = testFlagsmith(baseUrl, identity = "person", cacheConfig = cacheConfig())

        instance.setTraits(listOf(Trait("k", "v")))
        assertTrue(instance.refreshSync().isSuccess)

        // The reads show exactly what the server answered - not defaults, not a snapshot.
        assertTrue(instance.hasFeatureFlag("with-value"))
        assertEquals(SERVER_FIRST_MARKER, instance.getValueForFeature("with-value"))

        // Same traits, within the TTL: the repeat refresh must cost nothing.
        assertTrue(instance.refreshSync().isSuccess)
        assertEquals(SERVER_FIRST_MARKER, instance.getValueForFeature("with-value"))
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `B - cold start offline with a snapshot serves the snapshot, not the defaults`() = runBlocking<Unit> {
        // killed by: priming always returns defaultFlags - the pre-refresh reads then return
        // DEFAULT_MARKER and this fails.
        mockPostResponse(SNAPSHOT_MARKER, Times.once())
        val first = testFlagsmith(baseUrl, identity = "person", cacheConfig = cacheConfig())
        assertTrue(first.refreshSync().isSuccess)
        first.close()

        // The next POST gets a 500 (mockFailureFor registers a single Times.once() shot), so the
        // refresh genuinely reaches the network and fails.
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        // Past-TTL clock so the gate cannot answer the refresh - it must genuinely try the
        // network and fail, while priming (acceptStaleCache) still serves the snapshot.
        val second = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = cacheConfig(),
            defaultFlags = defaultFlags,
            nowMillis = { getTimeMillis() + PAST_TTL_OFFSET_MILLIS }
        )

        // Synchronous reads, before any refresh: the snapshot's values, not defaultFlags.
        assertTrue("the snapshot must prime hasFeatureFlag", second.hasFeatureFlag("with-value"))
        assertEquals(SNAPSHOT_MARKER, second.getValueForFeature("with-value"))
        assertEquals(SNAPSHOT_MARKER, second.observeValueForFeature("with-value").first())

        // The refresh really was attempted, and failed...
        assertTrue(second.refreshSync().isFailure)
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
        // ...and it must not disturb what priming already served.
        assertTrue(second.hasFeatureFlag("with-value"))
        assertEquals(
            "a failed refresh must leave the snapshot values in place",
            SNAPSHOT_MARKER,
            second.getValueForFeature("with-value")
        )
    }

    @Test
    fun `D - traits change across a restart - the gate misses and the new response lands`() = runBlocking<Unit> {
        // killed by: RequestKey.forRequest ignores the traits (empty digest) - the traits-A
        // snapshot then satisfies the traits-B refresh, zero new POSTs, and the reads stay on
        // the old response.
        mockPostResponse(TRAITS_A_MARKER, Times.once())
        val first = testFlagsmith(baseUrl, identity = "person", cacheConfig = cacheConfig())
        first.setTraits(listOf(Trait("k", "v1")))
        assertTrue(first.refreshSync().isSuccess)
        first.close()

        val second = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = cacheConfig(),
            defaultFlags = defaultFlags
        )
        // Priming still serves the traits-A snapshot before anything else happens.
        assertEquals(TRAITS_A_MARKER, second.getValueForFeature("with-value"))

        // Traits B: the snapshot's key (digest of v1) cannot satisfy this request, so exactly
        // one new POST must be made - and only one carries the v2 body.
        second.setTraits(listOf(Trait("k", "v2")))
        val traitsBBody = JsonBody.json(
            """{"identifier": "person", "traits": [{"trait_key": "k", """ +
                """"trait_value": "v2", "transient": false}], "transient": false}"""
        )
        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST").withBody(traitsBBody),
            Times.once()
        ).respond(successBody(TRAITS_B_MARKER))

        assertTrue(second.refreshSync().isSuccess)
        assertEquals(
            "the reads must show the new response, not the snapshot from traits A",
            TRAITS_B_MARKER,
            second.getValueForFeature("with-value")
        )
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST").withBody(traitsBBody),
            VerificationTimes.exactly(1)
        )
        // Across the two sessions: one POST for the snapshot's own fetch, plus exactly one from
        // the restart.
        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
    }

    @Test
    fun `E - past the ttl an unchanged refresh issues exactly one request`() = runBlocking<Unit> {
        // killed by: the gate's age check dropped (fetchedAt never expires) - the second refresh
        // is then answered from memory and the exactly(2) verification below fires.
        var offset = 0L
        val instance = testFlagsmith(
            baseUrl,
            identity = "person",
            cacheConfig = cacheConfig(),
            nowMillis = { getTimeMillis() + offset }
        )
        mockPostResponse(SERVER_FIRST_MARKER, Times.once())
        instance.setTraits(listOf(Trait("k", "v")))
        assertTrue(instance.refreshSync().isSuccess)
        assertEquals(SERVER_FIRST_MARKER, instance.getValueForFeature("with-value"))

        // Unchanged traits, but the clock has moved past the TTL: exactly one new request.
        offset += PAST_TTL_OFFSET_MILLIS
        mockPostResponse(SERVER_SECOND_MARKER, Times.once())
        assertTrue(instance.refreshSync().isSuccess)
        assertEquals(SERVER_SECOND_MARKER, instance.getValueForFeature("with-value"))

        mockServer.verify(
            request().withPath("/identities/").withMethod("POST"),
            VerificationTimes.exactly(2)
        )
    }
}
