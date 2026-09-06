package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.internal.DefaultFlagsmithAnalytics
import com.flagsmith.internal.http.FlagsmithApi
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.awaitility.Awaitility
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * [AnalyticsTest] runs on one thread, so it cannot exercise the CAS loop. Evaluations are counted
 * on whichever thread read the flag — several flow collectors on `Dispatchers.Default`, or the
 * main thread — so the race is real and only real threads reproduce it.
 */
class AnalyticsConcurrencyTests {

    @Before
    fun setup() {
        Awaitility.setDefaultTimeout(java.time.Duration.ofSeconds(30))
    }

    @Test
    fun `concurrent tracking loses no increments`() {
        // killed by: trackEvent uses a plain read-modify-write instead of a CAS loop
        val posted = ConcurrentLinkedQueue<Map<String, Int?>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val analytics = DefaultFlagsmithAnalytics(
            settings = MapSettings(),
            flagsmithApi = object : FlagsmithApi {
                override suspend fun postAnalytics(eventMap: Map<String, Int?>): Result<Unit> {
                    posted += eventMap
                    return Result.success(Unit)
                }

                override suspend fun getFlags(): Result<List<Flag>> = error("unused")
                override suspend fun postTraits(identity: IdentityAndTraits): Result<IdentityFlagsAndTraits> =
                    error("unused")
                override fun close() = Unit
            },
            flushPeriod = 1,
            coroutineScope = scope
        )

        val threads = 8
        val perThread = 2_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                repeat(perThread) { analytics.trackEvent("flag") }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()

        // Counts stay in the counter until a post succeeds, so they can arrive across several
        // posts; a lost increment means the counter is not atomic.
        await untilAsserted {
            assertEquals(
                "a lost increment means the counter is not atomic",
                threads * perThread,
                posted.sumOf { batch -> batch["flag"] ?: 0 }
            )
        }

        analytics.stop()
        scope.cancel()
    }
}
