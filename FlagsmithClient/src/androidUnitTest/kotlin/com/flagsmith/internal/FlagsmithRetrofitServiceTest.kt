package com.flagsmith.internal

import com.flagsmith.defaultJson
import com.flagsmith.entities.FeatureStatePutBody
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*

internal class FlagsmithKtorServiceTest(
    private val baseUrl: String,
    private val environmentKey: String
) {
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(defaultJson)
        }

        install(Logging) {
            this.logger = Logger.SIMPLE
            this.level = LogLevel.ALL
        }

        expectSuccess = true

        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
            header("X-Environment-Key", environmentKey)
        }
    }

    suspend fun getFeatureStates(
        authToken: String,
        featureStateId: String,
        environmentKey: String,
        featureName: String
    ): String {
        return httpClient.get("environments/${environmentKey}/featurestates/${featureStateId}/") {
            header("authorization", authToken)
            parameter("feature_name", featureName)
        }.body()
    }

    suspend fun setFeatureStates(
        authToken: String,
        featureStateId: String,
        environmentKey: String,
        body: FeatureStatePutBody
    ) {
        httpClient.put("environments/${environmentKey}/featurestates/${featureStateId}/") {
            header("authorization", authToken)
            setBody(body)
        }
    }
}