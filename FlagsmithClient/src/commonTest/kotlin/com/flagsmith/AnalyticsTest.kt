package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.internal.DefaultFlagsmithAnalytics
import com.flagsmith.internal.http.FlagsmithApi
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Evaluations are counted on whatever thread read the flag — the main thread for a synchronous
 * read at startup — so the counter must not touch disk or lose increments under concurrency.
 */
class AnalyticsTest {

    // An anonymous object rather than a named class: a named class in commonTest with no @Test
    // methods is picked up by the JVM runner and reported as an initialization error.
    private val posted = mutableListOf<Map<String, Int?>>()
    private var failNext = false
    private var gate: CompletableDeferred<Unit>? = null

    private val api = object : FlagsmithApi {
        override suspend fun postAnalytics(eventMap: Map<String, Int?>): Result<Unit> {
            gate?.await()
            posted += eventMap
            return if (failNext) Result.failure(RuntimeException("offline")) else Result.success(Unit)
        }

        override suspend fun getFlags(): Result<List<Flag>> = error("unused")
        override suspend fun postTraits(identity: IdentityAndTraits): Result<IdentityFlagsAndTraits> =
            error("unused")
        override fun close() = Unit
    }

    private fun analytics(scope: TestScope, settings: Settings = MapSettings()) =
        DefaultFlagsmithAnalytics(
            settings = settings,
            flagsmithApi = api,
            flushPeriod = 10,
            coroutineScope = scope
        )

    @Test
    fun trackEventDoesNotWriteBeforeTheWriterRuns() = runTest(StandardTestDispatcher()) {
        // killed by: trackEvent calls persist() directly instead of signalling the writer
        // The write is what made this unsafe on the main thread. trackEvent only signals the
        // writer coroutine, so nothing is written until that coroutine actually gets to run -
        // which it hasn't, since no time has been advanced yet.
        val settings = MapSettings()
        val instance = analytics(this, settings)

        repeat(50) { instance.trackEvent("flag") }

        assertTrue(settings.keys.isEmpty(), "no write should happen before the writer runs")
        instance.stop()
    }

    @Test
    fun rapidTrackingCollapsesIntoFewWrites() = runTest(StandardTestDispatcher()) {
        // killed by: the writer persists on every signal instead of throttling to ~1/s
        var writeCount = 0
        val backing = MapSettings()
        val settings = object : Settings by backing {
            override fun putInt(key: String, value: Int) { writeCount++; backing.putInt(key, value) }
            override fun putLong(key: String, value: Long) { writeCount++; backing.putLong(key, value) }
            override fun putString(key: String, value: String) { writeCount++; backing.putString(key, value) }
            override fun putFloat(key: String, value: Float) { writeCount++; backing.putFloat(key, value) }
            override fun putDouble(key: String, value: Double) { writeCount++; backing.putDouble(key, value) }
            override fun putBoolean(key: String, value: Boolean) { writeCount++; backing.putBoolean(key, value) }
            override fun remove(key: String) { writeCount++; backing.remove(key) }
        }
        val instance = analytics(this, settings)

        val eventCount = 200
        repeat(eventCount) {
            instance.trackEvent("flag")
            advanceTimeBy(50) // let the writer react, well short of its 1s throttle window
        }
        advanceTimeBy(2_000) // drain any write still pending behind the throttle

        // Each persist() call fans out into several underlying puts (map size, key, null marker,
        // value), so this bounds against a multiple of that rather than 1:1 against event count.
        assertTrue(
            writeCount in 1 until eventCount / 2,
            "expected far fewer writes than events, got $writeCount writes for $eventCount events"
        )
        instance.stop()
    }

    @Test
    fun countsSurviveAcrossInstances() = runTest(StandardTestDispatcher()) {
        // killed by: the writer never calls persist, or persists the map from before tracking
        // This is the point of the change: counts must reach disk well before the network flush,
        // so a second instance built over the same Settings - as after a process restart - posts
        // them on its very first flush.
        //
        // first's own periodic flush also runs immediately once anything is tracked (it flushes
        // before its first delay), which would post the tracked counts itself and pass this test
        // whether or not the writer ever touched disk. A gate blocks that flush mid-post so it
        // never completes; first.stop() then cancels it, leaving second's flush as the only one
        // that can land in `posted`.
        val settings = MapSettings()
        gate = CompletableDeferred()
        val first = analytics(this, settings)

        first.trackEvent("flag")
        first.trackEvent("flag")
        advanceTimeBy(1_500) // writer persists to disk; first's own flush is stuck on the gate
        first.stop()
        gate = null

        val second = analytics(this, settings)
        advanceTimeBy(11_000) // second's first flush

        assertEquals<Map<String, Int?>>(mapOf("flag" to 2), posted.single())
        second.stop()
    }

    @Test
    fun aSuccessfulPostClearsThePersistedCountsToo() = runTest(StandardTestDispatcher()) {
        // killed by: persist() clears via settings.remove(EVENTS_KEY) instead of removeValue()
        // A plain remove(key) is a no-op against the serializer's compound keys, so a restart
        // right after a successful post would reload and resend a batch the server already has.
        val settings = MapSettings()
        val first = analytics(this, settings)

        first.trackEvent("flag")
        advanceTimeBy(11_000) // flush posts and succeeds
        advanceTimeBy(1_500)  // writer clears the now-empty counts from disk
        first.stop()
        posted.clear()

        val second = analytics(this, settings)
        advanceTimeBy(11_000) // second's first flush should have nothing to send

        assertTrue(posted.isEmpty(), "a successfully posted batch must not be resent after a restart")
        second.stop()
    }

    @Test
    fun countsAccumulateAndPostAsOneBatch() = runTest(StandardTestDispatcher()) {
        // killed by: trackEvent overwrites instead of incrementing
        // Sequential only - runTest is single-threaded, so the concurrent case that actually
        // needs the CAS lives in AnalyticsConcurrencyTests, where real threads exist.
        val instance = analytics(this)

        repeat(500) { instance.trackEvent("flag") }
        advanceTimeBy(11_000)

        assertEquals<Map<String, Int?>>(mapOf("flag" to 500), posted.single())
        instance.stop()
    }

    @Test
    fun evaluationsDuringAPostAreNotLost() = runTest(StandardTestDispatcher()) {
        // killed by: flush cleared the counter before posting and reset it after, discarding
        // anything counted while the request was in flight.
        val blocker = CompletableDeferred<Unit>()
        gate = blocker
        val instance = analytics(this)

        instance.trackEvent("flag")
        advanceTimeBy(11_000)          // flush starts and blocks on the gate
        instance.trackEvent("flag")    // counted while the post is in flight
        blocker.complete(Unit)
        gate = null
        advanceTimeBy(11_000)          // next flush

        assertEquals(
            listOf<Map<String, Int?>>(mapOf("flag" to 1), mapOf("flag" to 1)),
            posted.toList(),
            "the evaluation counted mid-post must appear in a later batch"
        )
        instance.stop()
    }

    @Test
    fun aBatchWaitingForServerAckSurvivesARestart() = runTest(StandardTestDispatcher()) {
        // killed by: flush removed the batch from the counter before the POST resolved, so the
        // writer's next persist replaced the batch on disk with only the newer counts - a process
        // death before the server accepted the POST lost everything the batch had accumulated.
        // The batch must stay on disk until the server has accepted it.
        val settings = MapSettings()
        gate = CompletableDeferred()
        val first = analytics(this, settings)

        first.trackEvent("flag")
        advanceTimeBy(11_000)          // flush starts, blocked mid-POST; writer has persisted
        first.trackEvent("flag")       // counted while the first POST is still in flight
        advanceTimeBy(1_500)           // writer persists both counts
        first.stop()                   // process death before the server accepted anything
        gate = null

        val second = analytics(this, settings)
        advanceTimeBy(11_000)          // second's first flush resends the whole batch

        assertEquals<Map<String, Int?>>(mapOf("flag" to 2), posted.single())
        second.stop()
    }

    @Test
    fun aFailedPostKeepsTheCountsForTheNextFlush() = runTest(StandardTestDispatcher()) {
        // killed by: flush drops the batch when the post fails
        failNext = true
        val instance = analytics(this)

        instance.trackEvent("flag")
        advanceTimeBy(11_000)          // fails
        failNext = false
        instance.trackEvent("flag")
        advanceTimeBy(11_000)          // retries, carrying both

        assertEquals<Map<String, Int?>>(mapOf("flag" to 2), posted.last())
        instance.stop()
    }
}
