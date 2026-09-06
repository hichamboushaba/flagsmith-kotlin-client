package com.flagsmith

import com.flagsmith.entities.Trait
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.MockResponses
import com.flagsmith.mockResponses.mockResponseFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.JsonBody.json
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes

class FeatureFlagTests {

    private lateinit var mockServer: ClientAndServer

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
    }

    @After
    fun tearDown() {
        mockServer.stop()
    }

    private fun flagsmith(identity: String? = null, transientIdentity: Boolean = false) = Flagsmith(
        environmentKey = "",
        identity = identity,
        transientIdentity = transientIdentity,
        baseUrl = "http://localhost:${mockServer.localPort}",
        enableAnalytics = false,
        cacheConfig = FlagsmithCacheConfig(enableCache = false)
    )

    @Test
    fun testHasFeatureFlagWithFlag() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        val instance = flagsmith()
        runBlocking { assertTrue(instance.refreshSync().isSuccess) }
        assertTrue(instance.hasFeatureFlag("no-value"))
    }

    @Test
    fun testHasFeatureFlagWithoutFlag() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        val instance = flagsmith()
        runBlocking { assertTrue(instance.refreshSync().isSuccess) }
        assertFalse(instance.hasFeatureFlag("doesnt-exist"))
    }

    @Test
    fun testGetFeatureFlags() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        val instance = flagsmith()
        runBlocking { assertTrue(instance.refreshSync().isSuccess) }

        val found = instance.flagUpdateFlow.value.find { flag -> flag.feature.name == "with-value" }
        assertNotNull(found)
        assertEquals(7.0, found?.featureStateValue)
        assertEquals(7.0, instance.getValueForFeature("with-value"))
    }

    @Test
    fun testGetValueForFeatureNotExisting() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        val instance = flagsmith()
        runBlocking { assertTrue(instance.refreshSync().isSuccess) }
        assertNull(instance.getValueForFeature("not-existing"))
    }

    @Test
    fun testHasFeatureForNoIdentity() {
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        val instance = flagsmith()
        runBlocking { assertTrue(instance.refreshSync().isSuccess) }
        assertFalse(instance.hasFeatureFlag("with-value-just-person-enabled"))
    }

    @Test
    fun testHasFeatureWithIdentity() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val instance = flagsmith("person")
        runBlocking { assertTrue(instance.refreshSync().isSuccess) }
        assertTrue(instance.hasFeatureFlag("with-value-just-person-enabled"))
    }

    @Test
    fun testThrowsExceptionWhenCreatingAnalyticsWithoutAContext() {
        val exception = assertThrows(IllegalStateException::class.java) {
            Flagsmith(
                environmentKey = "",
                baseUrl = "http://localhost:${mockServer.localPort}",
                enableAnalytics = true
            )
        }
        assertEquals("App context not initialized, is the ContextInitializer disabled?", exception.message)
    }

    @Test
    fun testConstructingTransientIdentityWithoutIdentityThrows() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            flagsmith(identity = null, transientIdentity = true)
        }
        assertEquals("transientIdentity requires an identity", exception.message)
    }

    @Test
    fun testThrowsWhenIdentityScopedApiUsedWithoutIdentity() {
        // The suspend methods throw synchronously, before any network work, so the exception
        // propagates out of runBlocking. The callback wrappers deliver it as Result.failure
        // instead - see testIdentityScopedCallbackDeliversFailureWithoutIdentity.
        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { flagsmith().getTraits() }
        }
        assertEquals(
            "This Flagsmith instance was created without an identity. " +
                "Pass `identity` to the Flagsmith factory to use identity-scoped APIs.",
            exception.message
        )
    }

    @Test
    fun testIdentityScopedCallbackDeliversFailureWithoutIdentity() {
        var result: Result<List<Trait>>? = null
        flagsmith().getTraits { result = it }

        // Unconfined dispatch means the callback has already run by the time we get here.
        assertNotNull("The callback must be invoked, not swallowed by the launched coroutine", result)
        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun testSetTraitsThrowsWithoutIdentity() {
        // Environment mode: setTraits/setTrait/removeTrait reject synchronously, before any
        // trait ever enters the in-memory state - which is what keeps RequestKey.forRequest's
        // `require(traits.isEmpty())` for environment mode unreachable in practice.
        val exception = assertThrows(IllegalStateException::class.java) {
            flagsmith().setTraits(listOf())
        }
        assertEquals(
            "This Flagsmith instance was created without an identity. " +
                "Pass `identity` to the Flagsmith factory to use identity-scoped APIs.",
            exception.message
        )
    }

    @Test
    fun testEnvironmentModeRefreshUsesGetNotPost() {
        // An environment-scoped instance must never touch /identities/, whatever traits a prior
        // (rejected) setTraits attempt might have tried to add.
        mockServer.mockResponseFor(MockEndpoint.GET_FLAGS)
        val instance = flagsmith()

        val result = runBlocking { instance.refreshSync() }

        assertTrue(result.isSuccess)
        mockServer.verify(request().withPath("/flags/").withMethod("GET"), VerificationTimes.exactly(1))
        mockServer.verify(request().withPath("/identities/"), VerificationTimes.exactly(0))
    }

    @Test
    fun testGetFeatureFlagsWithIdentityAndTraits() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        val instance = flagsmith("person")
        instance.setTraits(listOf())
        runBlocking { assertTrue(instance.refreshSync().isSuccess) }

        val found = instance.flagUpdateFlow.value.find { flag -> flag.feature.name == "with-value" }
        assertNotNull(found)
        assertEquals(756.0, found?.featureStateValue)
    }

    @Test
    fun testGetFeatureFlagsWithTransientTraits() {
        // Per-trait transient (Trait.transient, whether the server persists that one value) is
        // independent of the identity-level transientIdentity - this instance is not transient.
        mockServer.`when`(
            request()
                .withPath("/identities/")
                .withMethod("POST")
                .withBody(
                    json(
                        """
                            {
                              "identifier": "identity",
                              "traits": [
                                {
                                  "trait_key": "transient-trait",
                                  "trait_value": "value",
                                  "transient": true
                                },
                                {
                                  "trait_key": "persisted-trait",
                                  "trait_value": "value",
                                  "transient": false
                                }
                              ],
                              "transient": false
                            }
                        """.trimIndent()
                    )
                )
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody(MockResponses.getTransientIdentities)
            )

        val instance = flagsmith("identity")
        instance.setTraits(
            listOf(Trait("transient-trait", "value", true), Trait("persisted-trait", "value", false))
        )
        val result = runBlocking { instance.refreshSync() }

        assertTrue(result.isSuccess)
    }

    @Test
    fun testGetFeatureFlagsWithTransientIdentity() {
        mockServer.`when`(
            request()
                .withPath("/identities/")
                .withMethod("POST")
                .withBody(
                    json(
                        """
                            {
                              "identifier": "identity",
                              "traits": [],
                              "transient": true
                            }
                        """.trimIndent()
                    )
                )
        )
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody(MockResponses.getTransientIdentities)
            )

        val instance = flagsmith("identity", transientIdentity = true)
        val result = runBlocking { instance.refreshSync() }

        assertTrue(result.isSuccess)
    }
}
