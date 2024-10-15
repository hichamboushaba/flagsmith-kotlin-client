package com.flagsmith.internal

internal class FlagsmithEventService(
    eventSourceBaseUrl: String?,
    environmentKey: String,
    flagsmithEventApiFactory: FlagsmithEventApiFactory
) {
    private val completeEventSourceUrl: String = eventSourceBaseUrl + "sse/environments/" + environmentKey + "/stream"
    private val flagsmithEventApi = flagsmithEventApiFactory.create(completeEventSourceUrl, environmentKey)

    val sseEventsFlow = flagsmithEventApi.observeEvents()
}
