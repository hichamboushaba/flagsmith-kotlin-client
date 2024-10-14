package com.flagsmith.mockResponses.endpoints

import com.flagsmith.defaultJson
import kotlinx.serialization.encodeToString

data class AnalyticsEndpoint(private val eventMap: Map<String, Int?>) :
    PostEndpoint<Unit>(
        path = "/analytics/flags/",
        body = defaultJson.encodeToString(eventMap),
    )