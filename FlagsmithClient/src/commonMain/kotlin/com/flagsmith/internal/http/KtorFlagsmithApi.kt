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
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import kotlin.coroutines.CoroutineContext
import io.ktor.client.plugins.cache.HttpCache as KtorHttpCachePlugin

internal class KtorFlagsmithApi(
    private val httpClient: HttpClient,
    private val cacheConfig: FlagsmithCacheConfig
) : FlagsmithApi {
    override suspend fun getIdentityFlagsAndTraits(
        identity: String,
        transient: Boolean?,
        forceRefresh: Boolean
    ): Result<IdentityFlagsAndTraits> = httpClient.getWithCacheIfEnabled(
        url = "identities/",
        enableCache = cacheConfig.enableCache,
        acceptStaleCache = cacheConfig.acceptStaleCache,
        forceRefresh = forceRefresh
    ) {
        parameter("identifier", identity)
        parameter("transient", transient)
    }.map { it.body() }

    override suspend fun getFlags(forceRefresh: Boolean): Result<List<Flag>> = httpClient.getWithCacheIfEnabled(
        url = "flags/",
        enableCache = cacheConfig.enableCache,
        acceptStaleCache = cacheConfig.acceptStaleCache,
        forceRefresh = forceRefresh
    ).map { it.body() }

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

    private suspend fun HttpClient.getWithCacheIfEnabled(
        url: String,
        enableCache: Boolean,
        forceRefresh: Boolean,
        acceptStaleCache: Boolean,
        block: HttpRequestBuilder.() -> Unit = {}
    ): Result<HttpResponse> {
        return runCatching {
            get(url) {
                block()
                if (enableCache) {
                    val headerValue = if (forceRefresh) {
                        CacheControl.NoCache(null)
                    } else {
                        CacheControl.MaxAge(cacheConfig.cacheTTLSeconds.toInt())
                    }

                    header(HttpHeaders.CacheControl, headerValue)
                }
            }
        }.recoverCatching {
            if (enableCache && acceptStaleCache) {
                get(url) {
                    block()
                    header(HttpHeaders.CacheControl, "max-stale=${Int.MAX_VALUE}")
                }
            } else {
                throw it
            }
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
                KtorFileCacheStorage(cacheConfig.cacheDirectoryPath.toPath(), cacheConfig.cacheSize)
            } else null

            val httpClient = HttpClient {
                install(ContentNegotiation) {
                    json(defaultJson)
                }

                install(Logging) {
                    this.logger = Logger.SIMPLE
                    this.level = LogLevel.INFO // TODO: allow to set log level
                }

                if (cache != null) {
                    install(KtorHttpCachePlugin) {
                        publicStorage(cache)
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
                }
            }

            httpClient.receivePipeline.intercept(HttpReceivePipeline.Before) { response ->
                if (cacheConfig.enableCache) {
                    proceedWith(ForceCacheResponse(response, cacheConfig.cacheTTLSeconds))
                }

                val updatedAtString = response.headers[UPDATED_AT_HEADER]
                updatedAtString?.toDoubleOrNull()?.let {
                    timeTracker.lastFlagFetchTime = it
                }
            }

            return KtorFlagsmithApi(httpClient, cacheConfig) to cache
        }
    }
}

private class ForceCacheResponse(
    private val originalResponse: HttpResponse,
    private val cacheTTLSeconds: Long
) : HttpResponse() {
    override val call: HttpClientCall by originalResponse::call

    @InternalAPI
    override val rawContent: ByteReadChannel by originalResponse::rawContent
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
            append(HttpHeaders.CacheControl, CacheControl.MaxAge(cacheTTLSeconds.toInt()).toString())
        }
    }
}
