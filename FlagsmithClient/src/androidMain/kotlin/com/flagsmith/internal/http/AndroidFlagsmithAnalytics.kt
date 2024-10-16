package com.flagsmith.internal.http

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.flagsmith.internal.FlagsmithAnalytics
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

internal class AndroidFlagsmithAnalytics(
    private val storage: FlagsmithAnalytics.Storage,
    private val flagsmithApi: FlagsmithApi,
    private val flushPeriod: Int
): FlagsmithAnalytics {
    private val currentEvents = storage.getEvents().toMutableMap()
    private val timerHandler = Handler(Looper.getMainLooper())

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (currentEvents.isNotEmpty()) {
                // TODO: remove usage of runBlocking
                runBlocking {
                    flagsmithApi.postAnalytics(currentEvents).let { result ->
                        result.onSuccess { resetMap() }
                            .onFailure { err ->
                                Log.e(
                                    "FLAGSMITH",
                                    "Failed posting analytics - ${err.localizedMessage}"
                                )
                            }
                    }
                }
            }
            timerHandler.postDelayed(this, flushPeriod.toLong() * 1000)
        }
    }

    init {
        timerHandler.post(timerRunnable)
    }

    /// Counts the instances of a `Flag` being queried.
    override fun trackEvent(flagName: String) {
        val currentFlagCount = currentEvents[flagName] ?: 0
        currentEvents[flagName] = currentFlagCount + 1

        // Update events cache
        storage.storeEvents(currentEvents)
    }

    private fun resetMap() {
        currentEvents.clear()
        storage.storeEvents(currentEvents)
    }

    companion object: FlagsmithAnalytics.Factory {
        override fun create(storage: FlagsmithAnalytics.Storage, flagsmithApi: FlagsmithApi, flushPeriod: Int): FlagsmithAnalytics {
            return AndroidFlagsmithAnalytics(
                storage = storage,
                flagsmithApi = flagsmithApi,
                flushPeriod = flushPeriod
            )
        }
    }
}

internal class AndroidAnalyticsStorage(context: Context): FlagsmithAnalytics.Storage {
    private val applicationContext = context.applicationContext

    override fun storeEvents(events: Map<String, Int?>) {
        val pSharedPref: SharedPreferences = applicationContext.getSharedPreferences(EVENTS_KEY, Context.MODE_PRIVATE)

        val jsonObject = JSONObject(events)
        val jsonString: String = jsonObject.toString()
        pSharedPref.edit()
            .remove(EVENTS_KEY)
            .putString(EVENTS_KEY, jsonString)
            .apply()
    }

    override fun getEvents(): Map<String, Int?> {
        val outputMap: MutableMap<String, Int?> = HashMap()
        val pSharedPref: SharedPreferences =
            applicationContext.getSharedPreferences(EVENTS_KEY, Context.MODE_PRIVATE)
        try {
            val jsonString = pSharedPref.getString(EVENTS_KEY, JSONObject().toString())
            if (jsonString != null) {
                val jsonObject = JSONObject(jsonString)
                val keysItr = jsonObject.keys()
                while (keysItr.hasNext()) {
                    val key = keysItr.next()
                    val value = jsonObject.getInt(key)
                    outputMap[key] = value
                }
            }
        } catch (e: JSONException) {
            Log.e("FLAGSMITH", "Exception in getMap Analytics - ${e.stackTraceToString()}")
        }
        return outputMap
    }

    companion object {
        private const val EVENTS_KEY = "events"
    }
}
