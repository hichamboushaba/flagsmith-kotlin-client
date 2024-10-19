package com.flagsmith.internal.http

import com.flagsmith.defaultJson
import com.flagsmith.entities.FlagEvent
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.date.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlin.time.Duration.Companion.minutes

/**
 * Ktor implementation of [FlagsmithEventApi].
 *
 * Important: This implementation is not yet tested against real Flagsmith server given that SSE in Flagsmith requires
 * an enterprise subscription. However, it was just tested using https://sse.dev/test
 *
 */
internal class KtorFlagsmithEventApi(
    private val httpClient: HttpClient,
    private val sseUrl: String,
) : FlagsmithEventApi {

    override fun observeEvents(): Flow<FlagEvent> {
        val sseUrl = """https://sse.dev/test?jsonobj={"updated_at":${GMTDate().timestamp}}"""
        return flow {
            httpClient.serverSentEvents(sseUrl) {
                emitAll(
                    incoming.mapNotNull { event ->
                        val data = event.data ?: return@mapNotNull null

                        defaultJson.decodeFromString<FlagEvent>(data)
                    }
                )
            }
        }
    }

    companion object : FlagsmithEventApi.Factory {
        override fun create(sseUrl: String, environmentKey: String): FlagsmithEventApi {
            val httpClient = HttpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = 6000
                    requestTimeoutMillis = 10.minutes.inWholeMilliseconds
                    socketTimeoutMillis = 10.minutes.inWholeMilliseconds
                }

                install(SSE)

                install(ContentNegotiation) {
                    json(defaultJson)
                }

                defaultRequest {
                    header("X-Environment-Key", environmentKey)
                }
            }

            return KtorFlagsmithEventApi(httpClient, sseUrl)
        }
    }
}
