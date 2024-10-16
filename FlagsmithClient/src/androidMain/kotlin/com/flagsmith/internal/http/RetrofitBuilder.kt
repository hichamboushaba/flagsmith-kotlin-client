package com.flagsmith.internal.http

import android.util.Log
import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.internal.FlagsmithEventTimeTracker
import com.flagsmith.internal.http.RetrofitFlagsmithApi.Companion.envKeyInterceptor
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

object RetrofitBuilder {
    private const val UPDATED_AT_HEADER = "x-flagsmith-document-updated-at"
    private const val ACCEPT_HEADER_VALUE = "application/json"
    private const val CONTENT_TYPE_HEADER_VALUE = "application/json; charset=utf-8"

    fun build(
        baseUrl: String,
        environmentKey: String,
        cacheConfig: FlagsmithCacheConfig,
        requestTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long,
        timeTracker: FlagsmithEventTimeTracker,
        json: Json
    ): Pair<Retrofit, Cache?> {
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

        return Pair(
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(
                    json.asConverterFactory("application/json; charset=UTF8".toMediaType())
                )
                .client(client)
                .build(),
            cache
        )
    }
}