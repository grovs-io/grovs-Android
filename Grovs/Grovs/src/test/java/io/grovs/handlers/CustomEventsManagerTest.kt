package io.grovs.handlers

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.model.CustomEvent
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
import io.grovs.service.IGrovsService
import io.grovs.storage.ICustomEventsStorage
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
        grovsContext.grovsId = "test-grovs-id"

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
        coEvery { service.addCustomEvent(any()) } returns LSResult.Success(true)

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
        coVerify(exactly = 1) { service.addCustomEvent(any()) }

        timedManager.track("after_close", null, null)
        timedManager.close()
        timedManager.close()
        advanceTimeBy(100L)
        runCurrent()

        coVerify(exactly = 1) { service.addCustomEvent(any()) }
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
        manager.setLinkForFutureEvents("https://test.link/abc")
        manager.track("checkout", null, null)

        assertEquals("https://test.link/abc", stored.single().link)
    }

    @Test
    fun `flush sends stored events and removes them on success`() = runTest {
        manager.track("a", null, null)
        manager.track("b", null, null)

        manager.flush()

        coVerify(exactly = 2) { service.addCustomEvent(any()) }
        assertEquals(0, stored.size)
    }

    @Test
    fun `flush drops events the server terminally rejects`() = runTest {
        coEvery { service.addCustomEvent(any()) } returns LSResult.Error(
            GrovsException("rejected", GrovsErrorCode.EVENT_DISPATCH_ERROR)
        )

        manager.track("a", null, null)
        manager.flush()

        // Terminal rejection: the event must NOT stay in storage retrying forever.
        assertEquals(0, stored.size)
    }

    @Test
    fun `flush keeps events on a transient error`() = runTest {
        coEvery { service.addCustomEvent(any()) } returns LSResult.Error(
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
        coVerify(exactly = 0) { service.addCustomEvent(any()) }
    }

    @Test
    fun `flush sends at most BATCH_SIZE events per cycle`() = runTest {
        repeat(CustomEventsManager.BATCH_SIZE + 10) { manager.track("e$it", null, null) }

        manager.flush()

        coVerify(exactly = CustomEventsManager.BATCH_SIZE) { service.addCustomEvent(any()) }
        assertEquals(10, stored.size)
    }

    @Test
    fun `flush survives an exception and continues functioning`() = runTest {
        var callCount = 0
        coEvery { service.addCustomEvent(any()) } answers {
            callCount++
            if (callCount == 1) {
                throw RuntimeException("Service error on first call")
            } else {
                LSResult.Success(true)
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
        coVerify(exactly = 2) { service.addCustomEvent(any()) }
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

        manager.setLinkForFutureEvents("https://grovs.io/abc")
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

        manager.setLinkForFutureEvents("https://grovs.io/abc")
        advanceUntilIdle()

        assertEquals(null, stored.first { it.eventName == "previous_session" }.link)
        assertEquals(
            "https://grovs.io/original",
            stored.first { it.eventName == "already_attributed" }.link
        )
        manager.close()
    }

    @Test
    fun `clearing the link does not touch stored events`() = runTest {
        val manager = backfillManager()
        manager.track("checkout_started", null, null)

        manager.setLinkForFutureEvents(null)
        advanceUntilIdle()

        coVerify(exactly = 0) { storage.updateEvents(any()) }
        assertEquals(null, stored.first { it.eventName == "checkout_started" }.link)
        manager.close()
    }
}
