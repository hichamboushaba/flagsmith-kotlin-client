package com.flagsmith.internal

import android.content.Context
import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.entities.FeatureStatePutBody
import kotlinx.serialization.json.Json
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface FlagsmithRetrofitServiceTest {

    @GET("environments/{environmentKey}/featurestates/{featureStateId}/")
    fun getFeatureStates(@Header("authorization") authToken:String,
                         @Path("featureStateId") featureStateId: String,
                         @Path("environmentKey") environmentKey: String,
                         @Query("feature_name") featureName: String) : Call<String>

    @PUT("environments/{environmentKey}/featurestates/{featureStateId}/")
    fun setFeatureStates(@Header("authorization") authToken:String,
                         @Path("featureStateId") featureStateId: String,
                         @Path("environmentKey") environmentKey: String,
                         @Body body: FeatureStatePutBody
    ) : Call<Unit>

    @Suppress("UNCHECKED_CAST")
    companion object {
        fun create(
            baseUrl: String,
            environmentKey: String,
            context: Context?,
            cacheConfig: FlagsmithCacheConfig,
            requestTimeoutSeconds: Long,
            readTimeoutSeconds: Long,
            writeTimeoutSeconds: Long,
            timeTracker: FlagsmithEventTimeTracker,
            json: Json
        ): FlagsmithRetrofitServiceTest {
            val (retrofit, _) = RetrofitBuilder.build(
                baseUrl = baseUrl,
                environmentKey = environmentKey,
                cacheConfig = cacheConfig,
                requestTimeoutSeconds = requestTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds,
                writeTimeoutSeconds = writeTimeoutSeconds,
                timeTracker = timeTracker,
                json = json
            )

            return retrofit.create(FlagsmithRetrofitServiceTest::class.java)
        }
    }
}