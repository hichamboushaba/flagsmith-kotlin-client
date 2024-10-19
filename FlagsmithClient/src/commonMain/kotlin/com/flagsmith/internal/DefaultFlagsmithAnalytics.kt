package com.flagsmith.internal

import com.flagsmith.internal.http.FlagsmithApi
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.Settings
import com.russhwolf.settings.serialization.serializedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class, ExperimentalSettingsApi::class)
internal class DefaultFlagsmithAnalytics(
    settings: Settings,
    private val flagsmithApi: FlagsmithApi,
    private val flushPeriod: Int,
    private val coroutineScope: CoroutineScope
) : FlagsmithAnalytics {
    private var currentEvents: Map<String, Int?> by settings.serializedValue(
        serializer = MapSerializer(String.serializer(), Int.serializer().nullable),
        key = EVENTS_KEY,
        defaultValue = emptyMap()
    )

    init {
        startPeriodicFlush()
    }

    override fun trackEvent(flagName: String) {
        val currentFlagCount = currentEvents[flagName] ?: 0

        // Update events cache
        currentEvents = currentEvents + (flagName to (currentFlagCount + 1))
    }

    private fun resetMap() {
        currentEvents = emptyMap()
    }

    private fun startPeriodicFlush() {
        coroutineScope.launch {
            while (true) {
                if (currentEvents.isNotEmpty()) {
                    flagsmithApi.postAnalytics(currentEvents).let { result ->
                        result.onSuccess { resetMap() }
                            .onFailure { err ->
                                println("Failed posting analytics - ${err.stackTraceToString()}")
                            }
                    }
                }

                ensureActive()
                delay(flushPeriod.seconds)
            }
        }
    }

    companion object : FlagsmithAnalytics.Factory {
        private const val EVENTS_KEY = "events"

        override fun create(
            flagsmithApi: FlagsmithApi,
            flushPeriod: Int,
            coroutineScope: CoroutineScope
        ): FlagsmithAnalytics {
            return DefaultFlagsmithAnalytics(
                settings = createSettings(EVENTS_KEY),
                flagsmithApi = flagsmithApi,
                flushPeriod = flushPeriod,
                coroutineScope = coroutineScope
            )
        }
    }
}
