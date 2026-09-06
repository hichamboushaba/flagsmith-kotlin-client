@file:OptIn(ExperimentalAtomicApi::class)

package com.flagsmith.internal

import com.flagsmith.defaultJson
import com.flagsmith.internal.http.FlagsmithApi
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

/**
 * Counts flag evaluations in memory, mirrors them to disk roughly once a second, and posts them
 * on a timer.
 *
 * Counting happens on whatever thread read the flag — the main thread, for a synchronous
 * `hasFeatureFlag` during startup — so it must not touch disk or take a lock. It signals a single
 * writer coroutine instead, which is the only thing that ever writes [persistedEvents]. Persistence
 * normally lags counting by ~1s; a process kill can lose counts still waiting for that write.
 */
internal class DefaultFlagsmithAnalytics(
    private val settings: Settings,
    private val flagsmithApi: FlagsmithApi,
    private val flushPeriod: Int,
    private val coroutineScope: CoroutineScope
) : FlagsmithAnalytics {
    // One JSON value under a single key, instead of the settings serializer's compound keys
    // ("events.size"/"events.0"/...), which needed nullable counts, a shared mutable encoder and
    // a special empty-map removal. An unparseable value reads as empty: counts are lossy anyway.
    private val eventsSerializer = MapSerializer(String.serializer(), Int.serializer())

    private var persistedEvents: Map<String, Int>
        get() {
            val stored = settings.getString(EVENTS_KEY, "")
            if (stored.isEmpty()) return emptyMap()
            return runCatching { defaultJson.decodeFromString(eventsSerializer, stored) }.getOrNull() ?: emptyMap()
        }
        set(value) {
            if (value.isEmpty()) settings.remove(EVENTS_KEY)
            else settings.putString(EVENTS_KEY, defaultJson.encodeToString(eventsSerializer, value))
        }

    // The only writer is the writer coroutine (see [startWriter]): Settings itself is thread-safe,
    // but a single writer is what keeps the disk picture consistent with the counter's timeline -
    // two concurrent persists could still land an older snapshot last.

    private val events = AtomicReference(persistedEvents)

    // Conflated: a burst of trackEvent calls must wake the writer once, not queue a signal per
    // call. A Channel rather than a SharedFlow because it buffers a pending signal even before the
    // writer's `for` loop has started collecting - a SharedFlow with replay = 0 would drop a signal
    // sent in that window, since a subscriber only sees values emitted after it subscribes.
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)

    private var flushJob: Job? = null

    @Volatile
    private var stopped = false
    private var writerJob: Job? = null

    init {
        writerJob = startWriter()
        flushJob = startPeriodicFlush()
    }

    override fun trackEvent(flagName: String) {
        // Reads keep working after close(), so without this every later read would grow the map
        // and enqueue into a channel nothing drains again.
        if (stopped) return
        events.update { it + (flagName to ((it[flagName] ?: 0) + 1)) }
        writeSignal.trySend(Unit)
    }

    override fun stop() {
        stopped = true
        flushJob?.cancel()
        flushJob = null
        writerJob?.cancel()
        writerJob = null
    }

    /**
     * Posts the current counts without removing them first. Previously persisted, unacknowledged
     * counts survive a restart during the POST; the writer's normal ~1s persistence lag remains.
     * On success, subtracts the exact batch per key, preserving evaluations counted while the
     * request was in flight. A crash between the server's acceptance and persistence of the
     * subtraction can resend acknowledged counts after a restart: an accepted bounded overcount.
     *
     * Only ever called from the periodic-flush coroutine (see [startPeriodicFlush]), which makes
     * the snapshot-then-subtract above safe: it is the only subtractor of [events], and counts
     * otherwise only grow, so each key retains at least its count from the batch snapshot.
     */
    private suspend fun flush() {
        val batch = events.load()
        if (batch.isEmpty()) return

        flagsmithApi.postAnalytics(batch)
            .onSuccess {
                // Subtracts the exact batch per key; evaluations counted while the request was in
                // flight survive, and update's transform may rerun harmlessly on a spurious CAS
                // failure since it derives the result purely from the current map.
                events.update { current ->
                    val reduced = current.toMutableMap()
                    batch.forEach { (flag, count) ->
                        val remaining = (reduced[flag] ?: 0) - count
                        if (remaining > 0) reduced[flag] = remaining else reduced.remove(flag)
                    }
                    reduced
                }
                writeSignal.trySend(Unit)
            }
            .onFailure { error ->
                // Nothing to restore: the batch was never removed. Persistence uses the
                // writer's normal ~1s lag.
                println("Failed posting analytics - ${error.stackTraceToString()}")
            }
    }

    /**
     * The only writer of [persistedEvents] - see its doc for why that must stay true. Persists as
     * soon as a signal arrives, then holds off for a second so any signals that land during that
     * second collapse into the single write that follows it, instead of one write each.
     */
    private fun startWriter(): Job = coroutineScope.launch {
        for (signal in writeSignal) {
            persistedEvents = events.load()
            delay(1.seconds)
        }
    }

    private fun startPeriodicFlush(): Job {
        return coroutineScope.launch {
            while (true) {
                flush()
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
