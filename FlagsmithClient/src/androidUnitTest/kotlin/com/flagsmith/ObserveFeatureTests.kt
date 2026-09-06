package com.flagsmith

import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.mockResponseFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType

/**
 * The reactive reads count evaluations the way the imperative ones do: the collector receiving a
 * value is the evaluation, so a refresh that leaves a flag alone must count nothing.
 */
class ObserveFeatureTests {

    private lateinit var mockServer: ClientAndServer
    private lateinit var analyticsFactory: RecordingAnalyticsFactory
    private var collector: Job? = null

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
        analyticsFactory = RecordingAnalyticsFactory()
        Awaitility.setDefaultTimeout(java.time.Duration.ofSeconds(10))
    }

    @After
    fun tearDown() {
        collector?.cancel()
        mockServer.stop()
    }

    private fun flagsmith() = testFlagsmith(
        baseUrl = "http://localhost:${mockServer.localPort}",
        identity = "person",
        enableAnalytics = true,
        analyticsFactory = analyticsFactory
    )

    /**
     * Responds to one POST with `with-value` set to [value], enabled or not, plus an `unrelated`
     * flag carrying [unrelated]. Varying only [unrelated] produces a document the StateFlow sees
     * as changed while the observed flag is untouched — the only shape that can tell tracking
     * before `distinctUntilChanged` apart from tracking after it.
     */
    private fun mockValue(value: Double, enabled: Boolean = true, unrelated: Double = 0.0) {
        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST"),
            Times.once()
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """
                    {"flags":[
                      {"feature":{"id":1,"name":"with-value","type":"STANDARD"},
                       "feature_state_value":$value,"enabled":$enabled},
                      {"feature":{"id":2,"name":"unrelated","type":"STANDARD"},
                       "feature_state_value":$unrelated,"enabled":true}
                    ],"traits":[]}
                    """.trimIndent()
                )
        )
    }

    private fun <T> collect(into: MutableList<T>, flow: kotlinx.coroutines.flow.Flow<T>) {
        collector = CoroutineScope(Dispatchers.Default).launch { flow.collect { into += it } }
    }

    @Test
    fun `a document change that leaves the flag alone emits nothing and counts nothing`() {
        // killed by: tracking before distinctUntilChanged
        val instance = flagsmith()
        val seen = mutableListOf<Any?>()
        mockValue(756.0, unrelated = 1.0)
        runBlocking { instance.refresh() }

        collect(seen, instance.observeValueForFeature("with-value"))
        await untilAsserted { assertEquals(listOf<Any?>(756.0), seen) }

        // Each refresh moves `unrelated`, so the document genuinely changes and the StateFlow
        // emits; `with-value` does not, so the collector is handed nothing and evaluates nothing.
        mockValue(756.0, unrelated = 2.0)
        runBlocking { instance.refresh(force = true) }
        mockValue(756.0, unrelated = 3.0)
        runBlocking { instance.refresh(force = true) }

        assertEquals(listOf<Any?>(756.0), seen)
        assertEquals(1, analyticsFactory.analytics.trackEventCount)
    }

    @Test
    fun `a changed flag emits and counts again`() {
        // killed by: distinctUntilChanged applied to the whole document instead of the projection
        val instance = flagsmith()
        val seen = mutableListOf<Any?>()
        mockValue(756.0)
        runBlocking { instance.refresh() }

        collect(seen, instance.observeValueForFeature("with-value"))
        await untilAsserted { assertEquals(1, seen.size) }

        mockValue(999.0)
        runBlocking { instance.refresh(force = true) }

        await untilAsserted { assertEquals(listOf<Any?>(756.0, 999.0), seen) }
        assertEquals(2, analyticsFactory.analytics.trackEventCount)
    }

    @Test
    fun `disabling a flag changes hasFeatureFlag but not its value`() {
        // killed by: enabled folded back into the value projection
        // The switch and the payload are independent, as in every other Flagsmith SDK: toggling a
        // flag off does not blank its configured value.
        val instance = flagsmith()
        val values = mutableListOf<Any?>()
        val enabled = mutableListOf<Boolean>()
        mockValue(756.0, enabled = true)
        runBlocking { instance.refresh() }

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { instance.observeValueForFeature("with-value").collect { values += it } }
        scope.launch { instance.observeHasFeatureFlag("with-value").collect { enabled += it } }
        await untilAsserted {
            assertEquals(listOf<Any?>(756.0), values)
            assertEquals(listOf(true), enabled)
        }

        mockValue(756.0, enabled = false)
        runBlocking { instance.refresh(force = true) }

        await untilAsserted { assertEquals(listOf(true, false), enabled) }
        assertEquals("the value is unaffected by the switch", listOf<Any?>(756.0), values)
        scope.cancel()
    }

    @Test
    fun `a disabled flag still reports its value`() {
        // killed by: enabled folded back into the value projection
        val instance = flagsmith()
        mockValue(756.0, enabled = false)
        runBlocking { instance.refresh() }

        assertEquals(756.0, instance.getValueForFeature("with-value"))
        assertEquals(false, instance.hasFeatureFlag("with-value"))
    }

    @Test
    fun `flagsChanged notifies without counting an evaluation`() {
        // killed by: flagsChanged tracks
        val instance = flagsmith()
        val ticks = mutableListOf<Unit>()
        mockValue(756.0)
        runBlocking { instance.refresh() }

        collect(ticks, instance.flagsChanged)
        await untilAsserted { assertEquals(1, ticks.size) }

        mockValue(999.0)
        runBlocking { instance.refresh(force = true) }

        await untilAsserted { assertEquals(2, ticks.size) }
        assertEquals(
            "flagsChanged carries no value, so it evaluates nothing",
            0,
            analyticsFactory.analytics.trackEventCount
        )
    }

    @Test
    fun `two collectors of the same flag are two evaluation sites`() {
        // killed by: tracking hoisted above the per-collector operators
        val instance = flagsmith()
        mockValue(756.0)
        runBlocking { instance.refresh() }

        val first = mutableListOf<Any?>()
        val second = mutableListOf<Any?>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { instance.observeValueForFeature("with-value").collect { first += it } }
        scope.launch { instance.observeValueForFeature("with-value").collect { second += it } }

        await untilAsserted {
            assertEquals(1, first.size)
            assertEquals(1, second.size)
            assertEquals(2, analyticsFactory.analytics.trackEventCount)
        }
        scope.cancel()
    }
}
