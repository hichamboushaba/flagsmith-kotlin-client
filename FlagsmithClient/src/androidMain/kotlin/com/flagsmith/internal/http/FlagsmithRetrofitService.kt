package com.flagsmith.internal.http;

import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.internal.FlagsmithEventTimeTracker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Interceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
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
            val (retrofit, cache) = RetrofitBuilder.build(
                baseUrl = baseUrl,
                environmentKey = environmentKey,
                cacheConfig = cacheConfig,
                requestTimeoutSeconds = requestTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds,
                writeTimeoutSeconds = writeTimeoutSeconds,
                timeTracker = timeTracker,
                json = json
            )

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

private class RetrofitHttpCache(private val cache: Cache) : ClearableHttpCache {
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


