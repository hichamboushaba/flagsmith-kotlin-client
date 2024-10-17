package com.flagsmith.internal.http

import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.defaultJson
import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.internal.FlagsmithEventTimeTracker
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.storage.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import io.ktor.client.plugins.cache.HttpCache as KtorHttpCachePlugin

internal class KtorFlagsmithApi(
    private val httpClient: HttpClient
) : FlagsmithApi {
    override suspend fun getIdentityFlagsAndTraits(
        identity: String,
        transient: Boolean
    ): Result<IdentityFlagsAndTraits> = runCatching {
        httpClient.get("identities/") {
            parameter("identifier", identity)
            parameter("transient", transient)
        }.body()
    }

    override suspend fun getFlags(): Result<List<Flag>> = runCatching { httpClient.get("flags/").body() }

    override suspend fun postTraits(identity: IdentityAndTraits): Result<IdentityFlagsAndTraits> {
        return runCatching {
            httpClient.post("identities/") {
                setBody(identity)
            }.body()
        }
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
            cacheConfig: FlagsmithCacheConfig,
            requestTimeoutSeconds: Long,
            readTimeoutSeconds: Long,
            writeTimeoutSeconds: Long,
            timeTracker: FlagsmithEventTimeTracker,
            json: Json
        ): Pair<FlagsmithApi, ClearableHttpCache?> {
            val cache = if (cacheConfig.enableCache) {
                // TODO: provide a persistent cache
                KtorHttpCache(CacheStorage.Unlimited())
            } else null

            val httpClient = HttpClient {
                install(ContentNegotiation) {
                    json(defaultJson)
                }

                install(Logging) {
                    this.logger = Logger.SIMPLE
                    this.level = LogLevel.ALL
                }

                if (cache != null) {
                    install(KtorHttpCachePlugin) {
                        publicStorage(cache.storage)
                    }
                }

                install(HttpTimeout) {
                    this.requestTimeoutMillis = requestTimeoutSeconds * 1000
                    this.socketTimeoutMillis = readTimeoutSeconds * 1000
                }

                expectSuccess = true

                defaultRequest {
                    url(baseUrl)

                    contentType(ContentType.Application.Json)

                    header("X-Environment-Key", environmentKey)
                    if (cacheConfig.enableCache) {
                        header(HttpHeaders.CacheControl, CacheControl.MaxAge(cacheConfig.cacheTTLSeconds.toInt()))
                    }
                }
            }

            httpClient.receivePipeline.intercept(HttpReceivePipeline.Before) { response ->
                if (cacheConfig.enableCache) {
                    proceedWith(ForceCacheResponse(response, cacheConfig.cacheTTLSeconds.toInt()))
                }

                val updatedAtString = response.headers[UPDATED_AT_HEADER]
                updatedAtString?.toDoubleOrNull()?.let {
                    timeTracker.lastFlagFetchTime = it
                }
            }

            return KtorFlagsmithApi(httpClient) to cache
        }
    }
}

private class KtorHttpCache(val storage: CacheStorage) : ClearableHttpCache {
    override fun invalidate() {
        // Ktor does not provide a way to invalidate the cache, TODO: check what can we do here
        error("Not implemented")
    }
}

private class ForceCacheResponse(
    private val originalResponse: HttpResponse,
    private val cacheTTLSeconds: Int
) : HttpResponse() {
    override val call: HttpClientCall by originalResponse::call

    @InternalAPI
    override val content: ByteReadChannel by originalResponse::content
    override val coroutineContext: CoroutineContext by originalResponse::coroutineContext
    override val requestTime: GMTDate by originalResponse::requestTime
    override val responseTime: GMTDate by originalResponse::responseTime
    override val status: HttpStatusCode by originalResponse::status
    override val version: HttpProtocolVersion by originalResponse::version

    override val headers: Headers by lazy {
        headers {
            appendAll(originalResponse.headers)
            remove(HttpHeaders.Pragma)
            remove(HttpHeaders.CacheControl)
            append(HttpHeaders.CacheControl, CacheControl.MaxAge(cacheTTLSeconds).toString())
        }
    }
}
