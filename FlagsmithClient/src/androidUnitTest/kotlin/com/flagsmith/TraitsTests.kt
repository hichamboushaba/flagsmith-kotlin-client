package com.flagsmith

import com.flagsmith.entities.Trait
import com.flagsmith.mockResponses.MockEndpoint
import com.flagsmith.mockResponses.MockResponses
import com.flagsmith.mockResponses.mockResponseFor
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
import org.mockserver.model.JsonBody.json
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes

class TraitsTests {

    private lateinit var mockServer: ClientAndServer
    private lateinit var flagsmith: Flagsmith

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
        flagsmith = Flagsmith(
            environmentKey = "",
            identity = "person",
            baseUrl = "http://localhost:${mockServer.localPort}",
            enableAnalytics = false,
            cacheConfig = FlagsmithCacheConfig(enableCache = false)
        )
    }

    @After
    fun tearDown() {
        mockServer.stop()
    }

    /** Registers a POST expectation matched on [expectedBody], responding with the mocked identity echo. */
    private fun mockEcho(expectedBody: String) {
        mockServer.`when`(
            request().withPath("/identities/").withMethod("POST").withBody(json(expectedBody))
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(MockResponses.getIdentities)
        )
    }

    @Test
    fun testSetTraitsIssuesNoRequest() {
        // killed by: setTraits no-op (paired with testSetTraitSendsItOnRefresh's body matcher)
        flagsmith.setTraits(listOf(Trait(key = "favourite-colour", value = "electric pink")))

        mockServer.verify(request().withPath("/identities/"), VerificationTimes.exactly(0))
    }

    @Test
    fun testSetTraitSendsItOnRefresh() {
        // killed by: setTraits no-op
        mockEcho(
            """{"identifier": "person", "traits": [{"trait_key": "set-from-client", "trait_value": "12345", "transient": false}], "transient": false}"""
        )

        flagsmith.setTrait(Trait(key = "set-from-client", value = "12345"))
        val result = runBlocking { flagsmith.refreshSync() }

        assertTrue(result.isSuccess)
    }

    @Test
    fun testSetTraitsSendsAllUpsertedTraitsOnRefresh() {
        mockEcho(
            """
                {
                  "identifier": "person",
                  "traits": [
                    {"trait_key": "set-from-client", "trait_value": "12345", "transient": false},
                    {"trait_key": "favourite-colour", "trait_value": "electric pink", "transient": false}
                  ],
                  "transient": false
                }
                """.trimIndent()
        )

        flagsmith.setTraits(
            listOf(
                Trait(key = "set-from-client", value = "12345"),
                Trait(key = "favourite-colour", value = "electric pink")
            )
        )
        val result = runBlocking { flagsmith.refreshSync() }

        assertTrue(result.isSuccess)
    }

    @Test
    fun testSetTraitInteger() {
        mockEcho(
            """{"identifier": "person", "traits": [{"trait_key": "set-from-client", "trait_value": 5, "transient": false}], "transient": false}"""
        )

        flagsmith.setTrait(Trait(key = "set-from-client", value = 5))
        val result = runBlocking { flagsmith.refreshSync() }

        assertTrue(result.isSuccess)
    }

    @Test
    fun testSetTraitDouble() {
        mockEcho(
            """{"identifier": "person", "traits": [{"trait_key": "set-from-client", "trait_value": 0.5, "transient": false}], "transient": false}"""
        )

        flagsmith.setTrait(Trait(key = "set-from-client", value = 0.5))
        val result = runBlocking { flagsmith.refreshSync() }

        assertTrue(result.isSuccess)
    }

    @Test
    fun testSetTraitBoolean() {
        mockEcho(
            """{"identifier": "person", "traits": [{"trait_key": "set-from-client", "trait_value": true, "transient": false}], "transient": false}"""
        )

        flagsmith.setTrait(Trait(key = "set-from-client", value = true))
        val result = runBlocking { flagsmith.refreshSync() }

        assertTrue(result.isSuccess)
    }

    @Test
    fun testRemoveTraitStopsSendingIt() {
        mockEcho(
            """{"identifier": "person", "traits": [{"trait_key": "b", "trait_value": "2", "transient": false}], "transient": false}"""
        )

        flagsmith.setTraits(listOf(Trait(key = "a", value = "1"), Trait(key = "b", value = "2")))
        flagsmith.removeTrait("a")
        val result = runBlocking { flagsmith.refreshSync() }

        assertTrue(result.isSuccess)
    }

    @Test
    fun testGetTraitsDefinedForPerson() {
        // getIdentity/getTraits/getTrait are a read-only diagnostic of the server's stored view:
        // they always POST an empty trait list, never the locally set-but-not-yet-refreshed state,
        // so this is genuinely an echo of the mock response, not of anything set above.
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        runBlocking {
            val result = flagsmith.getTraitsSync()
            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow().isNotEmpty())
            assertEquals(
                "electric pink",
                result.getOrThrow().find { trait -> trait.key == "favourite-colour" }?.stringValue
            )
        }
    }

    @Test
    fun testGetTraitById() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        runBlocking {
            val result = flagsmith.getTraitSync("favourite-colour")
            assertTrue(result.isSuccess)
            assertEquals("electric pink", result.getOrThrow()?.stringValue)
        }
    }

    @Test
    fun testGetUndefinedTraitById() {
        mockServer.mockResponseFor(MockEndpoint.GET_IDENTITIES)
        runBlocking {
            val result = flagsmith.getTraitSync("favourite-cricketer")
            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow())
        }
    }
}
