package com.flagsmith

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Color
import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.internal.appContext
import com.flagsmith.mockResponses.*
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilNotNull
import org.awaitility.kotlin.untilTrue
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockserver.integration.ClientAndServer
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue

private const val CACHE_DIR = "cache"

class FeatureFlagCachingTests {
    private lateinit var mockServer: ClientAndServer
    private lateinit var flagsmithWithCache: Flagsmith
    private lateinit var flagsmithNoCache: Flagsmith

    @Mock
    private lateinit var mockApplicationContext: Context

    @Mock
    private lateinit var mockContextResources: Resources

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
        System.setProperty("mockserver.logLevel", "INFO")
        Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
        setupMocks()
        val defaultFlags = listOf(
            Flag(
                feature = Feature(
                    id = 345345L,
                    name = "Flag 1",
                    createdDate = "2023‐07‐07T09:07:16Z",
                    description = "Flag 1 description",
                    type = "CONFIG",
                    defaultEnabled = true,
                    initialValue = "true"
                ), enabled = true, featureStateValue = "Vanilla Ice"
            ),
            Flag(
                feature = Feature(
                    id = 34345L,
                    name = "Flag 2",
                    createdDate = "2023‐07‐07T09:07:16Z",
                    description = "Flag 2 description",
                    type = "CONFIG",
                    defaultEnabled = true,
                    initialValue = "true"
                ), enabled = true, featureStateValue = "value2"
            ),
        )

        appContext = mockApplicationContext
        flagsmithWithCache = Flagsmith(
            environmentKey = "",
            baseUrl = "http://localhost:${mockServer.localPort}",
            enableAnalytics = true, // Mix up the analytics flag to test initialisation
            defaultFlags = defaultFlags,
            cacheConfig = FlagsmithCacheConfig(
                enableCache = true,
                cacheDirectoryPath = CACHE_DIR
            )
        )

        flagsmithNoCache = Flagsmith(
            environmentKey = "",
            baseUrl = "http://localhost:${mockServer.localPort}",
            enableAnalytics = false,
            cacheConfig = FlagsmithCacheConfig(enableCache = false),
            defaultFlags = defaultFlags
        )
    }

    private fun setupMocks() {
        MockitoAnnotations.initMocks(this)

        `when`(mockApplicationContext.getResources()).thenReturn(mockContextResources)
        `when`(mockApplicationContext.getSharedPreferences(anyString(), anyInt())).thenReturn(
            mockSharedPreferences
        )

        `when`(mockContextResources.getString(anyInt())).thenReturn("mocked string")
        `when`(mockContextResources.getStringArray(anyInt())).thenReturn(
            arrayOf(
                "mocked string 1",
                "mocked string 2"
            )
        )
        `when`(mockContextResources.getColor(anyInt())).thenReturn(Color.BLACK)
        `when`(mockContextResources.getBoolean(anyInt())).thenReturn(false)
        `when`(mockContextResources.getDimension(anyInt())).thenReturn(100f)
        `when`(mockContextResources.getIntArray(anyInt())).thenReturn(intArrayOf(1, 2, 3))
        `when`(mockApplicationContext.applicationContext).thenReturn(mockApplicationContext)
    }

    @After
    fun tearDown() {
        mockServer.stop()
        File(CACHE_DIR).delete()
        appContext = null
    }

    @Test
    fun testGetFeatureFlagsWithIdentityUsesCachedResponseOnSecondRequestFailure() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        // First time around we should be successful and cache the response
        var foundFromServer: Flag? = null
        flagsmithWithCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromServer }
        Assert.assertNotNull(foundFromServer)
        Assert.assertEquals(756.0, foundFromServer?.featureStateValue)

        // Now we mock the failure and expect the cached response to be returned
        var foundFromCache: Flag? = null
        flagsmithWithCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromCache =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromCache }
        Assert.assertNotNull(foundFromCache)
        Assert.assertEquals(756.0, foundFromCache?.featureStateValue)
    }

    @Test
    fun testGetFeatureFlagsWithIdentityUsesCachedResponseOnSecondRequestTimeout() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        mockServer.mockDelayFor(MockEndpoint.GET_IDENTITIES)

        // First time around we should be successful and cache the response
        var foundFromServer: Flag? = null
        flagsmithWithCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
            Assert.assertNotNull(foundFromServer)
            Assert.assertEquals(756.0, foundFromServer?.featureStateValue)
        }

        await untilNotNull { foundFromServer }

        // Now we mock the failure and expect the cached response to be returned
        var foundFromCache: Flag? = null
        flagsmithWithCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromCache =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromCache }
        Assert.assertNotNull(foundFromCache)
        Assert.assertEquals(756.0, foundFromCache?.featureStateValue)
    }

    @Test
    fun testGetFeatureFlagsNoIdentityUsesCachedResponseOnSecondRequestFailure() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        mockServer.mockFailureFor(MockEndpoint.GET_FLAGS)

        // First time around we should be successful and cache the response
        var foundFromServer: Flag? = null
        flagsmithWithCache.getFeatureFlags() { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromServer }
        Assert.assertNotNull(foundFromServer)
        Assert.assertEquals(7.0, foundFromServer?.featureStateValue)

        // Now we mock the failure and expect the cached response to be returned
        var foundFromCache: Flag? = null
        flagsmithWithCache.getFeatureFlags { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromCache =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromCache }
        Assert.assertNotNull(foundFromCache)
        Assert.assertEquals(7.0, foundFromCache?.featureStateValue)
    }

    @Test
    fun testGetFlagsWithFailingRequestShouldGetDefaults() {
        mockServer.mockFailureFor(MockEndpoint.GET_FLAGS)
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)

        // First time around we should fail and fall back to the defaults
        var foundFromCache: Flag? = null
        flagsmithWithCache.getFeatureFlags() { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromCache =
                result.getOrThrow().find { flag -> flag.feature.name == "Flag 1" }
        }

        await untilNotNull { foundFromCache }
        Assert.assertNotNull(foundFromCache)

        // Now we mock the server and expect the server response to be returned
        var foundFromServer: Flag? = null
        flagsmithWithCache.getFeatureFlags() { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromServer }
        Assert.assertNotNull(foundFromServer)
        Assert.assertEquals(7.0, foundFromServer?.featureStateValue)
    }

    @Test
    fun testGetFlagsWithTimeoutRequestShouldGetDefaults() {
        mockServer.mockDelayFor(MockEndpoint.GET_FLAGS)
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)

        // First time around we should get the default flag values
        var foundFromCache: Flag? = null
        flagsmithWithCache.getFeatureFlags() { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromCache =
                result.getOrThrow().find { flag -> flag.feature.name == "Flag 1" }
        }

        await untilNotNull { foundFromCache }
        Assert.assertNotNull(foundFromCache)
        Assert.assertEquals("Vanilla Ice", foundFromCache?.featureStateValue)

        // Now we mock the successful request and expect the server values
        var foundFromServer: Flag? = null
        flagsmithWithCache.getFeatureFlags() { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromServer }
        Assert.assertNotNull(foundFromServer)
        Assert.assertEquals(7.0, foundFromServer?.featureStateValue)
    }

    @Test
    fun testGetFeatureFlagsWithNewCachedFlagsmithGetsCachedValue() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        // First time around we should be successful and cache the response
        var foundFromServer: Flag? = null
        runBlocking { flagsmithWithCache.clearCache() }
        flagsmithWithCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromServer }
        Assert.assertNotNull(foundFromServer)
        Assert.assertEquals(756.0, foundFromServer?.featureStateValue)

        // Now get a new Flagsmith instance with the same cache and expect the cached response to be returned
        val newFlagsmithWithCache = Flagsmith(
            environmentKey = "",
            baseUrl = "http://localhost:${mockServer.localPort}",
            enableAnalytics = false,
            cacheConfig = FlagsmithCacheConfig(
                enableCache = true,
                cacheDirectoryPath = CACHE_DIR
            )
        )

        // Now we mock the failure and expect the cached response to be returned from the new flagsmith instance
        var foundFromCache: Flag? = null
        newFlagsmithWithCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertTrue(
                "The request will fail but we should be successful as we fall back on the cache",
                result.isSuccess
            )

            foundFromCache =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromCache }
        Assert.assertNotNull(foundFromCache)
        Assert.assertEquals(756.0, foundFromCache?.featureStateValue)
    }

    @Test
    fun testGetFeatureFlagsWithNewCachedFlagsmithDoesntGetCachedValueWhenWeClearTheCache() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        mockServer.mockFailureFor(MockEndpoint.GET_IDENTITIES)

        // First time around we should be successful and cache the response
        var foundFromServer: Flag? = null
        runBlocking { flagsmithWithCache.clearCache() }
        flagsmithWithCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer =
                result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromServer }
        Assert.assertNotNull(foundFromServer)
        Assert.assertEquals(756.0, foundFromServer?.featureStateValue)

        // Now get a new Flagsmith instance with the same cache and evict the cache straight away
        val newFlagsmithWithClearedCache = Flagsmith(
            environmentKey = "",
            baseUrl = "http://localhost:${mockServer.localPort}",
            enableAnalytics = false,
            cacheConfig = FlagsmithCacheConfig(
                enableCache = true,
                cacheDirectoryPath = CACHE_DIR
            )
        )
        runBlocking { newFlagsmithWithClearedCache.clearCache() }

        // Now we mock the failure and expect the get to fail as we don't have the cache to fall back on
        var foundFromCache: Flag? = null
        val hasFinishedGetRequest = AtomicBoolean(false)
        newFlagsmithWithClearedCache.getFeatureFlags(identity = "person") { result ->
            Assert.assertFalse("This un-cached response should fail", result.isSuccess)

            foundFromCache =
                result.getOrNull()?.find { flag -> flag.feature.name == "with-value" }
            hasFinishedGetRequest.set(true)
        }

        await untilTrue (hasFinishedGetRequest)
        Assert.assertNull("Shouldn't get any data back as we don't have a cache", foundFromCache)
    }

    @Test
    fun testReturnsStaleCacheWhenEnabled() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        mockServer.mockFailureFor(MockEndpoint.GET_FLAGS)

        val flagsmithWithCache = Flagsmith(
            environmentKey = "",
            baseUrl = "http://localhost:${mockServer.localPort}",
            enableAnalytics = false,
            cacheConfig = FlagsmithCacheConfig(
                enableCache = true,
                cacheDirectoryPath = CACHE_DIR,
                cacheTTLSeconds = 10,
                acceptStaleCache = true
            )
        )

        var foundFromServer: Flag? = null
        runBlocking { flagsmithWithCache.clearCache() }
        flagsmithWithCache.getFeatureFlags { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromServer = result.getOrThrow().first()
        }

        await untilNotNull { foundFromServer }
        Assert.assertNotNull(foundFromServer)

        Thread.sleep(12_000)

        var foundFromCache: Flag? = null
        flagsmithWithCache.getFeatureFlags { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromCache = result.getOrThrow().first()
        }
        await untilNotNull { foundFromCache }
        Assert.assertNotNull(foundFromCache)
    }

    @Test
    fun testSkipsCacheWhenRefreshing() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        mockServer.mockResponseFor(
            path = MockEndpoint.GET_FLAGS.path,
            body = MockResponses.getFlags.replace("\"feature_state_value\": 7", "\"feature_state_value\": 8")
        )
        mockServer.mockFailureFor(MockEndpoint.GET_FLAGS)

        var initialValue: Flag? = null

        flagsmithWithCache.getFeatureFlags { result ->
            Assert.assertTrue(result.isSuccess)

            initialValue = result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { initialValue }
        assertTrue(initialValue?.featureStateValue == 7.0)

        var updatedValue: Flag? = null
        flagsmithWithCache.getFeatureFlags(forceRefresh = true) { result ->
            Assert.assertTrue(result.isSuccess)

            updatedValue = result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { updatedValue }
        assertTrue(updatedValue?.featureStateValue == 8.0)

        var foundFromCache: Flag? = null
        flagsmithWithCache.getFeatureFlags { result ->
            Assert.assertTrue(result.isSuccess)

            foundFromCache = result.getOrThrow().find { flag -> flag.feature.name == "with-value" }
        }

        await untilNotNull { foundFromCache }
        assertTrue(foundFromCache?.featureStateValue == 8.0)
    }
}