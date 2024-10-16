package com.flagsmith.internal

import com.flagsmith.internal.http.FlagsmithEventApi

internal class FlagsmithEventService(
    eventSourceBaseUrl: String?,
    environmentKey: String,
    flagsmithEventApiFactory: FlagsmithEventApi.Factory
) {
    private val completeEventSourceUrl: String = eventSourceBaseUrl + "sse/environments/" + environmentKey + "/stream"
    private val flagsmithEventApi = flagsmithEventApiFactory.create(completeEventSourceUrl, environmentKey)

    val sseEventsFlow = flagsmithEventApi.observeEvents()
}
