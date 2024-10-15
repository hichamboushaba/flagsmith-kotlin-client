package com.flagsmith.internal;

import android.util.Log
import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.*
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import kotlin.coroutines.resume

internal class RetrofitFlagsmithApi(private val service: FlagsmithRetrofitService) : FlagsmithApi {
    override suspend fun getIdentityFlagsAndTraits(
        identity: String,
        transient: Boolean
    ): Result<IdentityFlagsAndTraits> = service.executeAsResult {
        getIdentityFlagsAndTraits(identity, transient)
    }

    override suspend fun getFlags(): Result<List<Flag>> = service.executeAsResult {
        getFlags()
    }

    override suspend fun postTraits(identity: IdentityAndTraits) = service.executeAsResult {
        postTraits(identity)
    }

    override suspend fun postAnalytics(eventMap: Map<String, Int?>) = service.executeAsResult {
        postAnalytics(eventMap)
    }


    private suspend fun <T> FlagsmithRetrofitService.executeAsResult(
        block: FlagsmithRetrofitService.() -> Call<T>
    ): Result<T> {
        return suspendCancellableCoroutine { continuation ->
            val call = block()
            call.enqueue(object : Callback<T> {
                override fun onResponse(call: Call<T>, response: Response<T>) {
                    if (response.isSuccessful && response.body() != null) {
                        continuation.resume(Result.success(response.body()!!))
                    } else {
                        onFailure(call, HttpException(response))
                    }
                }

                override fun onFailure(call: Call<T>, t: Throwable) {
                    continuation.resume(Result.failure(t))
                }
            })

            continuation.invokeOnCancellation {
                call.cancel()
            }
        }
    }

    companion object : FlagsmithApi.Factory {
        private const val UPDATED_AT_HEADER = "x-flagsmith-document-updated-at"
        private const val ACCEPT_HEADER_VALUE = "application/json"
        private const val CONTENT_TYPE_HEADER_VALUE = "application/json; charset=utf-8"

        override fun create(
            baseUrl: String,
            environmentKey: String,
            cacheConfig: FlagsmithCacheConfig,
            requestTimeoutSeconds: Long,
            readTimeoutSeconds: Long,
            writeTimeoutSeconds: Long,
            timeTracker: FlagsmithEventTimeTracker,
            json: Json
        ): Pair<FlagsmithApi, HttpCache?> {
            fun cacheControlInterceptor(): Interceptor {
                return Interceptor { chain ->
                    val response = chain.proceed(chain.request())
                    response.newBuilder()
                        .header("Cache-Control", "public, max-age=${cacheConfig.cacheTTLSeconds}")
                        .removeHeader("Pragma")
                        .build()
                }
            }

            fun jsonContentTypeInterceptor(): Interceptor {
                return Interceptor { chain ->
                    val request = chain.request()
                    if (chain.request().method == "POST" || chain.request().method == "PUT" || chain.request().method == "PATCH") {
                        val newRequest = request.newBuilder()
                            .header("Content-Type", CONTENT_TYPE_HEADER_VALUE)
                            .header("Accept", ACCEPT_HEADER_VALUE)
                            .build()
                        chain.proceed(newRequest)
                    } else {
                        chain.proceed(request)
                    }
                }
            }

            fun updatedAtInterceptor(tracker: FlagsmithEventTimeTracker): Interceptor {
                return Interceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val updatedAtString = response.header(UPDATED_AT_HEADER)
                    Log.i("Flagsmith", "updatedAt: $updatedAtString")

                    // Update in the tracker (Flagsmith class) if we got a new value
                    tracker.lastFlagFetchTime = updatedAtString?.toDoubleOrNull() ?: tracker.lastFlagFetchTime
                    return@Interceptor response
                }
            }

            val cache = if (cacheConfig.enableCache) {
                Cache(File(cacheConfig.cacheDirectoryPath), cacheConfig.cacheSize)
            } else {
                null
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(envKeyInterceptor(environmentKey))
                .addInterceptor(updatedAtInterceptor(timeTracker))
                .addInterceptor(jsonContentTypeInterceptor())
                .let { if (cacheConfig.enableCache) it.addNetworkInterceptor(cacheControlInterceptor()) else it }
                .callTimeout(requestTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .cache(cache)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(
                    json.asConverterFactory("application/json; charset=UTF8".toMediaType())
                )
                .client(client)
                .build()

            val service = retrofit.create(FlagsmithRetrofitService::class.java)
            return Pair(RetrofitFlagsmithApi(service), cache?.let { RetrofitHttpCache(it) })
        }

        // This is used by both the FlagsmithRetrofitService and the FlagsmithEventService
        fun envKeyInterceptor(environmentKey: String): Interceptor {
            return Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-environment-key", environmentKey)
                    .build()
                chain.proceed(request)
            }
        }
    }
}

private class RetrofitHttpCache(private val cache: Cache) : HttpCache {
    override fun invalidate() {
        cache.evictAll()
    }
}

interface FlagsmithRetrofitService {
    @GET("identities/")
    fun getIdentityFlagsAndTraits(
        @Query("identifier") identity: String,
        @Query("transient") transient: Boolean = false
    ): Call<IdentityFlagsAndTraits>

    @GET("flags/")
    fun getFlags(): Call<List<Flag>>

    // todo: rename this function
    @POST("identities/")
    fun postTraits(@Body identity: IdentityAndTraits): Call<IdentityFlagsAndTraits>

    @POST("analytics/flags/")
    fun postAnalytics(@Body eventMap: Map<String, Int?>): Call<Unit>
}

