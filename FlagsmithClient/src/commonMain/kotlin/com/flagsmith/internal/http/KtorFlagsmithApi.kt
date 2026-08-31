package com.flagsmith.internal.http

import com.flagsmith.defaultJson
import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.internal.FlagsmithEventTimeTracker
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

internal class KtorFlagsmithApi(
    private val httpClient: HttpClient,
) : FlagsmithApi {
    override suspend fun getIdentityFlagsAndTraits(
        identity: String,
        transient: Boolean?,
    ): Result<IdentityFlagsAndTraits> = runCatching {
        httpClient.get("identities/") {
            parameter("identifier", identity)
            parameter("transient", transient)
        }.body()
    }

    override suspend fun getFlags(): Result<List<Flag>> = runCatching {
        httpClient.get("flags/").body()
    }

    override suspend fun postTraits(identity: IdentityAndTraits): Result<IdentityFlagsAndTraits> = runCatching {
        httpClient.post("identities/") {
            setBody(identity)
        }.body()
    }

    override suspend fun postAnalytics(eventMap: Map<String, Int?>): Result<Unit> = runCatching {
        httpClient.post("analytics/flags/") {
            setBody(eventMap)
        }
    }

    companion object : FlagsmithApi.Factory {
        private const val UPDATED_AT_HEADER = "x-flagsmith-document-updated-at"

        override fun create(
            baseUrl: String,
            environmentKey: String,
            userAgentOverride: String?,
            requestTimeoutSeconds: Long,
            readTimeoutSeconds: Long,
            writeTimeoutSeconds: Long,
            timeTracker: FlagsmithEventTimeTracker,
            json: Json
        ): FlagsmithApi {
            val httpClient = HttpClient {
                install(ContentNegotiation) {
                    json(defaultJson)
                }

                install(Logging) {
                    this.logger = Logger.SIMPLE
                    this.level = LogLevel.INFO // TODO: allow to set log level
                }

                install(HttpTimeout) {
                    this.requestTimeoutMillis = requestTimeoutSeconds * 1000
                    this.socketTimeoutMillis = readTimeoutSeconds * 1000
                }

                userAgentOverride?.let { agent ->
                    install(UserAgent) {
                        this.agent = agent
                    }
                }

                expectSuccess = true

                defaultRequest {
                    url(baseUrl)

                    contentType(ContentType.Application.Json)

                    header("X-Environment-Key", environmentKey)
                }
            }

            httpClient.receivePipeline.intercept(HttpReceivePipeline.Before) { response ->
                val updatedAtString = response.headers[UPDATED_AT_HEADER]
                updatedAtString?.toDoubleOrNull()?.let {
                    timeTracker.lastFlagFetchTime = it
                }
            }

            return KtorFlagsmithApi(httpClient)
        }
    }
}
