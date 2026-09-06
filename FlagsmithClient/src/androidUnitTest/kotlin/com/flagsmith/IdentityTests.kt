package com.flagsmith

import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.mockResponseFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.JsonBody.json

class IdentityTests {

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
    fun testGetIdentity() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        runBlocking {
            val result = flagsmith("person").getIdentitySync()

            mockServer.verify(
                request()
                    .withPath("/identities/")
                    .withMethod("POST")
                    .withBody(json("""{"identifier": "person", "traits": [], "transient": false}"""))
            )

            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow().traits.isNotEmpty())
            assertTrue(result.getOrThrow().flags.isNotEmpty())
            assertEquals(
                "electric pink",
                result.getOrThrow().traits.find { trait -> trait.key == "favourite-colour" }?.stringValue
            )
        }
    }

    @Test
    fun testGetTransientIdentity() {
        mockServer.mockResponseFor(MockEndpoint.GET_TRANSIENT_IDENTITIES)
        runBlocking {
            val result = flagsmith("transient-identity", transientIdentity = true).getIdentitySync()

            mockServer.verify(
                request()
                    .withPath("/identities/")
                    .withMethod("POST")
                    .withBody(
                        json(
                            """{"identifier": "transient-identity", "traits": [], "transient": true}"""
                        )
                    )
            )

            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow().traits.isEmpty())
            assertTrue(result.getOrThrow().flags.isNotEmpty())
        }
    }
}
