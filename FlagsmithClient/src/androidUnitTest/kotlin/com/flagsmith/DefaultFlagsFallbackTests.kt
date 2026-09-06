package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.MockResponses
import com.flagsmith.mockResponses.mockFailureFor
import com.flagsmith.mockResponses.mockResponseFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import java.io.File

private const val CACHE_DIR = "cache-defaults-fallback"

/**
 * Covers the [FlagsmithCacheConfig] `defaultFlags` fallback: what reads see before anything has
 * ever been fetched, and what they keep showing when a [Flagsmith.refresh] fails. Request-counting
 * and gate behaviour live in [FlagsTtlGateTests]; cold-start priming lives in
 * [FlagsCachePrimingTests].
 */
class DefaultFlagsFallbackTests {

    private lateinit var mockServer: ClientAndServer

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
    }

    @After
    fun tearDown() {
        mockServer.stop()
        File(CACHE_DIR).deleteRecursively()
    }

    private val defaultFlags = listOf(
        Flag(
            feature = Feature(id = 35507L, name = "with-value", type = "STANDARD"),
            enabled = true,
            featureStateValue = "default-value"
        )
    )

    private fun flagsmith(identity: String? = "person") = testFlagsmith(
        baseUrl = "http://localhost:${mockServer.localPort}",
        identity = identity,
        defaultFlags = defaultFlags,
        cacheConfig = FlagsmithCacheConfig(enableCache = true, cacheDirectoryPath = CACHE_DIR)
    )

    @Test
    fun testReadsServeDefaultsWhenNothingWasEverFetched() {
        val instance = flagsmith()

        assertEquals("default-value", instance.getValueForFeature("with-value"))
        assertTrue(instance.hasFeatureFlag("with-value"))
        assertEquals(defaultFlags, instance.flagUpdateFlow.value)
    }

    @Test
    fun testRefreshReturnsFailureAndReadsDoNotChange() {
        // killed by: failure resets reads to defaults
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        val instance = flagsmith()

        val result = runBlocking { instance.refreshSync() }

        assertTrue(result.isFailure)
        assertEquals("default-value", instance.getValueForFeature("with-value"))
        assertEquals(defaultFlags, instance.flagUpdateFlow.value)
    }

    @Test
    fun testRefreshFailureAfterASuccessLeavesTheFetchedValueInPlace() {
        // killed by: failure resets reads to defaults
        // Populated with a value distinct from defaultFlags first, so a broken implementation
        // that resets reads to defaults on a later failure (instead of leaving them untouched) is
        // actually caught - defaultFlags is never what the mock server itself returns.
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val instance = flagsmith()
        assertTrue(runBlocking { instance.refreshSync() }.isSuccess)
        assertEquals(756.0, instance.getValueForFeature("with-value"))

        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)
        val result = runBlocking { instance.refreshSync(force = true) }

        assertTrue(result.isFailure)
        assertEquals(
            "A failed refresh must not reset reads to defaultFlags once something real was fetched",
            756.0,
            instance.getValueForFeature("with-value")
        )
        // The on-disk snapshot must also be untouched by the failed refresh.
        val freshInstance = flagsmith()
        assertEquals(756.0, freshInstance.getValueForFeature("with-value"))
    }

    @Test
    fun testClearCacheResetsReadsToDefaultsAndTheNextRefreshFetches() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val instance = flagsmith()
        assertTrue(runBlocking { instance.refreshSync() }.isSuccess)
        assertEquals(756.0, instance.getValueForFeature("with-value"))

        runBlocking { instance.clearCache() }
        assertEquals("default-value", instance.getValueForFeature("with-value"))

        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        assertTrue(runBlocking { instance.refreshSync() }.isSuccess)
        assertEquals(756.0, instance.getValueForFeature("with-value"))
    }

    @Test
    fun testForceRefreshRequestsWithinTtl() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val instance = flagsmith()
        assertTrue(runBlocking { instance.refreshSync() }.isSuccess)

        mockServer.mockResponseFor(
            path = MockEndpoint.GET_IDENTITIES.path,
            body = MockResponses.getIdentities.replace("\"feature_state_value\": 756", "\"feature_state_value\": 800")
        )
        assertTrue(runBlocking { instance.refreshSync(force = true) }.isSuccess)

        assertEquals(800.0, instance.getValueForFeature("with-value"))
    }
}
