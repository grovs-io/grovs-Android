package io.grovs.handlers

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.TestFixtures
import io.grovs.model.BatchEventError
import io.grovs.model.BatchEventsResponse
import io.grovs.model.CustomEvent
import io.grovs.service.IGrovsService
import io.grovs.storage.ICustomEventsStorage
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class CustomEventsManagerTest {

    private lateinit var context: Context
    private lateinit var service: IGrovsService
    private lateinit var storage: ICustomEventsStorage
    private lateinit var grovsContext: GrovsContext
    private lateinit var manager: CustomEventsManager

    private val stored = mutableListOf<CustomEvent>()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        context = RuntimeEnvironment.getApplication()
        service = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        grovsContext = GrovsContext()
        // Most flush() tests exercise the authenticated path; the unauthenticated case has its own
        // dedicated test below.
        grovsContext.markAuthenticated("test-grovs-id", grovsContext.consent.currentConfiguration)

        stored.clear()
        coEvery { storage.addEvent(any()) } answers { stored.add(firstArg()); Unit }
        coEvery { storage.getEvents() } answers { stored.toList() }
        coEvery { storage.removeEvents(any()) } answers {
            val doomed = firstArg<List<CustomEvent>>().map { it.eventId }.toSet()
            stored.removeAll { doomed.contains(it.eventId) }
            Unit
        }
        coEvery { storage.updateEvents(any()) } answers {
            val transform = firstArg<(CustomEvent) -> CustomEvent>()
            val updated = stored.map(transform)
            stored.clear()
            stored.addAll(updated)
            Unit
        }
        coEvery { service.addCustomEvents(any()) } answers {
            LSResult.Success(BatchEventsResponse(accepted = firstArg<List<CustomEvent>>().size, rejected = 0))
        }

        manager = CustomEventsManager(
            context = context,
            grovsContext = grovsContext,
            grovsService = service,
            customEventsStorage = storage,
            startFlushTimer = false,
        )
    }

    @After
    fun tearDown() {
        manager.close()
    }

    @Test
    fun `close prevents future periodic flushes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val timedManager = CustomEventsManager(
            context = context,
            grovsContext = grovsContext,
            grovsService = service,
            customEventsStorage = storage,
            timerDispatcher = dispatcher,
            flushIntervalMs = 100L,
            startFlushTimer = true,
        )

        timedManager.track("before_close", null, null)
        advanceTimeBy(100L)
        runCurrent()
        coVerify(exactly = 1) { service.addCustomEvents(any()) }

        timedManager.track("after_close", null, null)
        timedManager.close()
        timedManager.close()
        advanceTimeBy(100L)
        runCurrent()

        coVerify(exactly = 1) { service.addCustomEvents(any()) }
    }

    @Test
    fun `track persists a valid event`() = runTest {
        manager.track("checkout_completed", properties = mapOf("sku" to "abc"), tags = listOf("shop"))

        coVerify(exactly = 1) { storage.addEvent(any()) }
        assertEquals("checkout_completed", stored.single().eventName)
        assertEquals("abc", stored.single().properties!!["sku"])
    }

    @Test
    fun `global tags are merged onto tracked events`() = runTest {
        manager.setGlobalTags(listOf("android", "prod"))
        manager.track("checkout", null, tags = listOf("shop"))

        assertEquals(listOf("shop", "android", "prod"), stored.single().tags)
    }

    @Test
    fun `tracked events carry the current session id`() = runTest {
        manager.track("checkout", null, null)

        assertEquals(grovsContext.sessionId, stored.single().sessionId)
    }

    @Test
    fun `tracked events carry the current link`() = runTest {
        manager.setLinkForFutureEvents("https://test.link/abc", grovsContext.sessionId)
        manager.track("checkout", null, null)

        assertEquals("https://test.link/abc", stored.single().link)
    }

    @Test
    fun `a link tagged with an earlier session cannot attribute the current session`() = runTest {
        val sessionA = grovsContext.sessionId
        val campaignA = "https://test.link/campaign-a"
        manager.setLinkForFutureEvents(campaignA, sessionA)
        manager.track("session_a", null, null)

        TestFixtures.startNewSession(grovsContext)
        val sessionB = grovsContext.sessionId
        assertNotEquals(sessionA, sessionB)
        // The supplied ownership must be respected even if the context changed before the setter.
        manager.setLinkForFutureEvents(campaignA, sessionA)
        manager.track("session_b", null, null)

        assertEquals(listOf(sessionA, sessionB), stored.map { it.sessionId })
        assertEquals(listOf(campaignA, null), stored.map { it.link })
        coVerify(exactly = 0) { storage.updateEvents(any()) }

        manager.flush()
        coVerify(exactly = 1) {
            service.addCustomEvents(match { events ->
                events.map { it.sessionId to it.link } == listOf(sessionA to campaignA, sessionB to null)
            })
        }
    }

    @Test
    fun `regranting consent after session rotation does not revive an old campaign`() = runTest {
        val sessionA = grovsContext.sessionId
        val campaignA = "https://test.link/campaign-a"
        manager.setLinkForFutureEvents(campaignA, sessionA)
        manager.track("before_disable", null, null)

        grovsContext.settings.sdkEnabled = false
        TestFixtures.startNewSession(grovsContext)
        assertNotEquals(sessionA, grovsContext.sessionId)
        manager.track("while_disabled", null, null)
        grovsContext.settings.sdkEnabled = true
        manager.track("after_enable", null, null)

        assertEquals(listOf("before_disable", "after_enable"), stored.map { it.eventName })
        assertEquals(listOf(sessionA, grovsContext.sessionId), stored.map { it.sessionId })
        assertEquals(listOf(campaignA, null), stored.map { it.link })
    }

    @Test
    fun `flush sends stored events and removes them on success`() = runTest {
        manager.track("a", null, null)
        manager.track("b", null, null)

        manager.flush()

        coVerify(exactly = 1) { service.addCustomEvents(match { it.size == 2 }) }
        assertEquals(0, stored.size)
    }

    @Test
    fun `flush sends one batch of at most BATCH_SIZE and removes it on success`() = runTest {
        val events = List(CustomEventsManager.BATCH_SIZE + 5) {
            CustomEvent(eventName = "e$it", createdAt = InstantCompat.ofEpochMilli(1_000L + it), sessionId = "s")
        }
        coEvery { storage.getEvents() } returns events
        coEvery { service.addCustomEvents(any()) } answers {
            LSResult.Success(BatchEventsResponse(accepted = firstArg<List<CustomEvent>>().size, rejected = 0))
        }

        manager.flush()

        coVerify(exactly = 1) { service.addCustomEvents(events.take(CustomEventsManager.BATCH_SIZE)) }
        coVerify(exactly = 1) { storage.removeEvents(events.take(CustomEventsManager.BATCH_SIZE)) }
    }

    @Test
    fun `a failed batch is kept for the next tick`() = runTest {
        val events = listOf(CustomEvent(eventName = "a", createdAt = InstantCompat.now(), sessionId = "s"))
        coEvery { storage.getEvents() } returns events
        coEvery { service.addCustomEvents(any()) } returns LSResult.Error(java.io.IOException("down"))

        manager.flush()

        coVerify(exactly = 0) { storage.removeEvents(any()) }
    }

    @Test
    fun `a batch the backend consumed with rejections is removed`() = runTest {
        val events = listOf(
            CustomEvent(eventName = "ok", createdAt = InstantCompat.now(), sessionId = "s"),
            CustomEvent(eventName = "install", createdAt = InstantCompat.now(), sessionId = "s"),
        )
        coEvery { storage.getEvents() } returns events
        coEvery { service.addCustomEvents(any()) } returns LSResult.Success(
            BatchEventsResponse(accepted = 1, rejected = 1, rawErrors = listOf(BatchEventError(1, "event_name 'install' is reserved")))
        )

        manager.flush()

        coVerify(exactly = 1) { storage.removeEvents(events) }
    }

    @Test
    fun `flush keeps events on a transient error`() = runTest {
        coEvery { service.addCustomEvents(any()) } returns LSResult.Error(
            java.io.IOException("network down")
        )

        manager.track("a", null, null)
        manager.flush()

        // Transient: keep it for the next flush cycle.
        assertEquals(1, stored.size)
    }

    @Test
    fun `flush is a no-op while unauthenticated and does not delete stored events`() = runTest {
        // Regression test: an unauthenticated flush() must leave storage untouched rather than
        // sending and having the event terminally rejected/deleted for a missing device header.
        grovsContext.grovsId = null

        manager.track("checkout_completed", null, null)
        assertEquals(1, stored.size)

        manager.flush()

        assertEquals(1, stored.size)
        coVerify(exactly = 0) { service.addCustomEvents(any()) }
    }

    @Test
    fun `flush sends nothing while the SDK is disabled`() = runTest {
        manager.track("checkout_completed", null, null)
        assertEquals(1, stored.size)

        // grovsId is set so the disabled gate, not the unauthenticated gate, is what stops this flush.
        grovsContext.settings.sdkEnabled = false
        manager.flush()

        coVerify(exactly = 0) { service.addCustomEvents(any()) }
        assertEquals(1, stored.size)
    }

    @Test
    fun `flush sends nothing while events are held`() = runTest {
        manager.track("checkout_completed", null, null)
        assertEquals(1, stored.size)

        manager.setEventsHeld(true)
        manager.flush()

        coVerify(exactly = 0) { service.addCustomEvents(any()) }
        assertEquals(1, stored.size)
    }

    @Test
    fun `flush sends at most BATCH_SIZE events per cycle`() = runTest {
        repeat(CustomEventsManager.BATCH_SIZE + 10) { manager.track("e$it", null, null) }

        manager.flush()

        coVerify(exactly = 1) { service.addCustomEvents(match { it.size == CustomEventsManager.BATCH_SIZE }) }
        assertEquals(10, stored.size)
    }

    @Test
    fun `flush survives an exception and continues functioning`() = runTest {
        var callCount = 0
        coEvery { service.addCustomEvents(any()) } answers {
            callCount++
            if (callCount == 1) {
                throw RuntimeException("Service error on first call")
            } else {
                LSResult.Success(BatchEventsResponse(accepted = firstArg<List<CustomEvent>>().size, rejected = 0))
            }
        }

        manager.track("event1", null, null)
        assertEquals(1, stored.size)

        // First flush throws — event stays in storage
        try {
            manager.flush()
            throw AssertionError("Expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("Service error on first call", e.message)
        }

        assertEquals(1, stored.size)

        // Second flush succeeds — event is sent and removed
        manager.flush()

        assertEquals(0, stored.size)
        coVerify(exactly = 2) { service.addCustomEvents(any()) }
    }

    /** A manager whose backfill coroutine runs on the test scheduler rather than a real dispatcher. */
    private fun TestScope.backfillManager() = CustomEventsManager(
        context = context,
        grovsContext = grovsContext,
        grovsService = service,
        customEventsStorage = storage,
        timerDispatcher = StandardTestDispatcher(testScheduler),
        startFlushTimer = false,
    )

    @Test
    fun `a resolved link backfills events tracked earlier in the same session`() = runTest {
        val manager = backfillManager()
        manager.track("checkout_started", null, null)

        manager.attributePendingEvents("https://grovs.io/abc", grovsContext.sessionId)
        advanceUntilIdle()

        assertEquals(
            "https://grovs.io/abc",
            stored.first { it.eventName == "checkout_started" }.link
        )
        manager.close()
    }

    @Test
    fun `the backfill leaves earlier sessions and already-attributed events alone`() = runTest {
        val manager = backfillManager()
        stored.add(
            CustomEvent(
                eventName = "previous_session",
                sessionId = "a-previous-session",
                createdAt = InstantCompat.now(),
            )
        )
        stored.add(
            CustomEvent(
                eventName = "already_attributed",
                sessionId = grovsContext.sessionId,
                link = "https://grovs.io/original",
                createdAt = InstantCompat.now(),
            )
        )

        manager.attributePendingEvents("https://grovs.io/abc", grovsContext.sessionId)
        advanceUntilIdle()

        assertEquals(null, stored.first { it.eventName == "previous_session" }.link)
        assertEquals(
            "https://grovs.io/original",
            stored.first { it.eventName == "already_attributed" }.link
        )
        manager.close()
    }

    @Test
    fun `setting a future link does not backfill queued events`() = runTest {
        manager.track("before_link", null, null)
        manager.setLinkForFutureEvents("https://grovs.io/future", grovsContext.sessionId)
        manager.track("after_link", null, null)

        assertEquals(listOf(null, "https://grovs.io/future"), stored.map { it.link })
        coVerify(exactly = 0) { storage.updateEvents(any()) }
    }

    @Test
    fun `clearing the link does not touch stored events`() = runTest {
        val campaign = "https://grovs.io/future"
        manager.setLinkForFutureEvents(campaign, grovsContext.sessionId)
        manager.track("before_clear", null, null)

        manager.setLinkForFutureEvents(null, grovsContext.sessionId)
        manager.track("after_clear", null, null)

        coVerify(exactly = 0) { storage.updateEvents(any()) }
        assertEquals(listOf(campaign, null), stored.map { it.link })
    }

    /** A minimal real (non-mock) storage so removal genuinely happens between overlapping flushes. */
    private class FakeCustomEventsStorage : ICustomEventsStorage {
        private val events = mutableListOf<CustomEvent>()

        override suspend fun addEvent(event: CustomEvent) {
            events.add(event)
        }

        override suspend fun removeEvents(events: List<CustomEvent>) {
            val doomed = events.map { it.eventId }.toSet()
            this.events.removeAll { doomed.contains(it.eventId) }
        }

        override suspend fun getEvents(): List<CustomEvent> = events.toList()

        override suspend fun updateEvents(transform: (CustomEvent) -> CustomEvent) {
            val updated = events.map(transform)
            events.clear()
            events.addAll(updated)
        }
    }

    @Test
    fun `overlapping flushes post each custom event exactly once`() = runTest {
        val fakeStorage = FakeCustomEventsStorage()
        val overlappingService = mockk<IGrovsService>(relaxed = true)
        val postedEventIds = mutableListOf<String>()
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        coEvery { overlappingService.addCustomEvents(any()) } coAnswers {
            val batch = firstArg<List<CustomEvent>>()
            if (callCount.getAndIncrement() == 0) {
                // Only the first caller waits: this is what lets a second, concurrent flush() race
                // it and read the same still-unremoved events if the mutex is missing.
                gate.await()
            }
            postedEventIds.addAll(batch.map { it.eventId })
            LSResult.Success(BatchEventsResponse(accepted = batch.size, rejected = 0))
        }

        val overlappingManager = CustomEventsManager(
            context = context,
            grovsContext = grovsContext,
            grovsService = overlappingService,
            customEventsStorage = fakeStorage,
            startFlushTimer = false,
        )
        repeat(5) { overlappingManager.track("e$it", null, null) }
        val allEventIds = fakeStorage.getEvents().map { it.eventId }

        val job1 = launch { overlappingManager.flush() }
        val job2 = launch { overlappingManager.flush() }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        job1.join()
        job2.join()

        assertEquals(1, callCount.get())
        assertEquals(allEventIds.sorted(), postedEventIds.sorted())
        overlappingManager.close()
    }
}
