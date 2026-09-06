package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.entities.FlagEvent
import com.flagsmith.entities.Feature
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.entities.Trait
import com.flagsmith.internal.FlagsmithEventTimeTracker
import com.flagsmith.internal.http.FlagsmithApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shutdown of the SSE machinery. The event collector and the forced refreshes it schedules are
 * the client's own work: close() must cancel them outright, so nothing launched for the stream
 * can run against the released HTTP clients or throw into the caller-supplied scope.
 */
class FlagsmithShutdownTests {

    private class ShutdownTestApi : FlagsmithApi {
        val flagRequests = AtomicInteger(0)

        /** Parks the caller before the request is counted: a cancelled coroutine never reaches it. */
        val hold = CompletableDeferred<Unit>()

        /** Parks the caller mid-request, after the count: the request is in flight. */
        val gate = CompletableDeferred<Unit>()

        override suspend fun getFlags(): Result<List<Flag>> = error("unused: identity mode always POSTs")

        override suspend fun postTraits(identity: IdentityAndTraits): Result<IdentityFlagsAndTraits> {
            hold.await()
            flagRequests.incrementAndGet()
            gate.await()
            return Result.success(
                IdentityFlagsAndTraits(
                    flags = listOf(
                        Flag(
                            feature = Feature(id = 1L, name = "from-server", type = "CONFIG"),
                            enabled = true,
                            featureStateValue = "server"
                        )
                    ),
                    traits = emptyList()
                )
            )
        }

        override suspend fun postAnalytics(eventMap: Map<String, Int?>): Result<Unit> = error("unused")
        override fun close() = Unit
    }

    private class ShutdownTestApiFactory(val api: ShutdownTestApi = ShutdownTestApi()) : FlagsmithApi.Factory {
        override fun create(
            baseUrl: String,
            environmentKey: String,
            userAgentOverride: String?,
            requestTimeoutSeconds: Long,
            readTimeoutSeconds: Long,
            writeTimeoutSeconds: Long,
            timeTracker: FlagsmithEventTimeTracker,
            json: Json
        ): FlagsmithApi = api
    }

    private val defaultFlags = listOf(
        Flag(
            feature = Feature(id = 2L, name = "default-flag", type = "CONFIG"),
            enabled = false,
            featureStateValue = "default"
        )
    )

    /**
     * A caller-supplied scope on the test scheduler whose job and uncaught exceptions the test
     * can observe: a refresh failure escaping into this scope cancels the job and lands in the
     * handler, which is exactly the damage the closed-shutdown race must be proven against.
     */
    private fun callerScopeOn(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        uncaught: MutableList<Throwable>
    ): Pair<CoroutineScope, Job> {
        val job = Job()
        val scope = CoroutineScope(
            job + StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, throwable -> uncaught += throwable }
        )
        return scope to job
    }

    @Test
    fun `an event refresh at close is cancelled before it requests`() = runTest(StandardTestDispatcher()) {
        val uncaught = mutableListOf<Throwable>()
        val (callerScope, callerJob) = callerScopeOn(testScheduler, uncaught)
        val apiFactory = ShutdownTestApiFactory()
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl = "http://shutdown.test/api/v1/",
            identity = "person",
            defaultFlags = defaultFlags,
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi,
            apiFactory = apiFactory,
            coroutineScope = callerScope
        )

        runCurrent() // the event collector subscribes

        // Two events before anything else runs: the collector retires the document once per
        // event and schedules two forced refreshes, both parked before their request is counted.
        eventApi.api.events.emit(FlagEvent(updatedAt = 1.0))
        eventApi.api.events.emit(FlagEvent(updatedAt = 2.0))
        runCurrent()
        assertEquals(0, apiFactory.api.flagRequests.get())

        instance.close() // must cancel the parked refreshes
        apiFactory.api.hold.complete(Unit) // releasing them must find nobody waiting
        advanceUntilIdle()

        assertTrue(
            "parked SSE refreshes must be cancelled by close, not run and failed: $uncaught",
            uncaught.isEmpty()
        )
        assertTrue("the caller-supplied scope must survive close", callerJob.isActive)
        assertEquals("no flags request may be made after close", 0, apiFactory.api.flagRequests.get())
        assertEquals(
            "no stranded response may reach the reads",
            "default",
            instance.flagUpdateFlow.value.single().featureStateValue
        )

        // Existing supported behaviour: reads and trait setters keep working after close.
        assertEquals("default", instance.getValueForFeature("default-flag"))
        instance.setTraits(listOf(Trait(key = "plan", value = "pro")))
    }

    @Test
    fun `an in-flight event refresh is cancelled by close`() = runTest(StandardTestDispatcher()) {
        val uncaught = mutableListOf<Throwable>()
        val (callerScope, callerJob) = callerScopeOn(testScheduler, uncaught)
        val apiFactory = ShutdownTestApiFactory()
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl = "http://shutdown.test/api/v1/",
            identity = "person",
            defaultFlags = defaultFlags,
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi,
            apiFactory = apiFactory,
            coroutineScope = callerScope
        )

        runCurrent() // the event collector subscribes
        eventApi.api.events.emit(FlagEvent(updatedAt = 1.0))
        runCurrent() // the collector schedules the refresh; it parks before the request
        apiFactory.api.hold.complete(Unit)
        runCurrent() // the request is counted and parks mid-flight
        assertEquals(1, apiFactory.api.flagRequests.get())

        instance.close() // must cancel the in-flight request
        apiFactory.api.gate.complete(Unit) // a late response must find nobody waiting
        advanceUntilIdle()

        assertTrue("cancelling the in-flight refresh must not fail anything: $uncaught", uncaught.isEmpty())
        assertTrue("the caller-supplied scope must survive close", callerJob.isActive)
        assertEquals("close must not trigger further requests", 1, apiFactory.api.flagRequests.get())
        assertEquals(
            "a response stranded by close must not be applied to the reads",
            "default",
            instance.flagUpdateFlow.value.single().featureStateValue
        )
    }

    @Test
    fun `restartRealtimeUpdates resubscribes and later events still refresh`() = runTest(StandardTestDispatcher()) {
        val uncaught = mutableListOf<Throwable>()
        val (callerScope, callerJob) = callerScopeOn(testScheduler, uncaught)
        val apiFactory = ShutdownTestApiFactory()
        val eventApi = FakeEventApiFactory()
        val instance = testFlagsmith(
            baseUrl = "http://shutdown.test/api/v1/",
            identity = "person",
            defaultFlags = defaultFlags,
            enableRealtimeUpdates = true,
            eventApiFactory = eventApi,
            apiFactory = apiFactory,
            coroutineScope = callerScope
        )

        runCurrent() // the first collector subscribes
        instance.restartRealtimeUpdates()
        runCurrent() // the replacement collector subscribes

        eventApi.api.events.emit(FlagEvent(updatedAt = 1.0))
        runCurrent() // the event-triggered refresh parks before the request
        apiFactory.api.hold.complete(Unit)
        runCurrent() // the request is counted

        assertEquals("the resubscribed collector must still trigger refreshes", 1, apiFactory.api.flagRequests.get())
        assertTrue(uncaught.isEmpty())
        assertTrue(callerJob.isActive)

        instance.close()
        apiFactory.api.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(uncaught.isEmpty())
        assertTrue(callerJob.isActive)
        assertEquals("close must not trigger further requests", 1, apiFactory.api.flagRequests.get())
    }
}
