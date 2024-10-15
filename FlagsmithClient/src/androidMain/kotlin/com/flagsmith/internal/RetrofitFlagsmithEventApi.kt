package com.flagsmith.internal

import android.util.Log
import com.flagsmith.defaultJson
import com.flagsmith.entities.FlagEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

private const val TAG = "FlagsmithEventApi"

internal class RetrofitFlagsmithEventApi(
    sseUrl: String,
    environmentKey: String,
) : FlagsmithEventApi {
    private val sseClient = OkHttpClient.Builder()
        .addInterceptor(RetrofitFlagsmithApi.envKeyInterceptor(environmentKey))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private val sseRequest = Request.Builder()
        .url(sseUrl)
        .header("Accept", "application/json")
        .addHeader("Accept", "text/event-stream")
        .build()


    override fun observeEvents(): Flow<FlagEvent> {
        var eventSource: EventSource? = null

        fun initEventSource(listener: EventSourceListener) {
            eventSource?.cancel()
            eventSource = EventSources.createFactory(sseClient)
                .newEventSource(request = sseRequest, listener = listener)
        }

        return callbackFlow {
            val sseEventSourceListener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    Log.d(TAG, "onEvent: $data")
                    if (type != null && type == "environment_updated" && data.isNotEmpty()) {
                        val flagEvent = defaultJson.decodeFromString<FlagEvent>(data)
                        trySend(flagEvent)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    super.onClosed(eventSource)
                    Log.d(TAG, "onClosed: $eventSource")

                    // This isn't uncommon and is the nature of HTTP requests, so just reconnect
                    initEventSource(this)
                }

            }
            EventSources.createFactory(sseClient)
                .newEventSource(request = sseRequest, listener = sseEventSourceListener)

            awaitClose {
                eventSource?.cancel()
            }
        }
    }

    companion object : FlagsmithEventApiFactory {
        override fun create(sseUrl: String, environmentKey: String): FlagsmithEventApi {
            return RetrofitFlagsmithEventApi(sseUrl, environmentKey)
        }
    }
}
