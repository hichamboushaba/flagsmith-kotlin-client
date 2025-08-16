package com.flagsmith.internal

import com.flagsmith.internal.http.FlagsmithEventApi

internal class FlagsmithEventService(
    eventSourceBaseUrl: String,
    environmentKey: String,
    userAgentOverride: String?,
    flagsmithEventApiFactory: FlagsmithEventApi.Factory
) {
    private val completeEventSourceUrl: String = eventSourceBaseUrl + "sse/environments/" + environmentKey + "/stream"
    private val flagsmithEventApi = flagsmithEventApiFactory.create(
        sseUrl = completeEventSourceUrl,
        environmentKey = environmentKey,
        userAgentOverride = userAgentOverride
    )

    val sseEventsFlow = flagsmithEventApi.observeEvents()
}
