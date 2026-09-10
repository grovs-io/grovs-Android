package io.grovs.handlers

import io.grovs.model.BatchEventsResponse
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.events.PaymentEvent
import io.grovs.service.IGrovsService
import io.grovs.storage.IEventsStorage
import io.grovs.storage.ILocalCache
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SessionTest {

    /**
     * Stateful stand-in for EventsStorage: relaxed mockk mocks don't retain state across
     * calls, but this test needs a queued event to survive between a failed send and a retry.
     */
    private class InMemoryEventsStorage : IEventsStorage {
        val storedEvents = mutableListOf<Event>()

        override suspend fun addOrReplaceEvents(events: List<Event>) {
            events.forEach { event ->
                val index = storedEvents.indexOf(event)
                if (index >= 0) storedEvents[index] = event else storedEvents.add(event)
            }
        }

        override suspend fun addEvent(event: Event) {
            storedEvents.add(event)
        }

        override suspend fun addPaymentEvent(event: PaymentEvent) {}

        override suspend fun markTimeSpentNode(startingNode: Boolean, endingNode: Boolean, link: String?, sessionId: String?) {}

        override suspend fun removeEvent(event: Event) {
            storedEvents.remove(event)
        }

        override suspend fun removePaymentEvent(event: PaymentEvent) {}

        override suspend fun replacePaymentEvents(events: List<PaymentEvent>) {}

        override suspend fun getEvents(): List<Event> = storedEvents.toList()

        override suspend fun getPaymentEvents(): List<PaymentEvent> = emptyList()

        override suspend fun hasEmptyTimeSpentEvent(): Boolean = false
    }

    /**
     * Regression test: an event's sessionId must be stamped once at creation and never
     * overwritten at send time, or a queued event that outlives a session rotation gets
     * relabeled with the new session when it's resent.
     */
    /** Backdates backgroundedAt so the session timeout can be tested against real time. */
    private fun backdateBackgrounded(context: GrovsContext, minutes: Long) {
        GrovsContext::class.java.getDeclaredField("backgroundedAt").apply {
            isAccessible = true
            set(context, InstantCompat.now().minusMillis(minutes * 60 * 1000))
        }
    }

    @Test
    fun `event created before a session rotation keeps its original session id when resent`() = runTest {
        val grovsContext = GrovsContext()
        val sessionA = grovsContext.sessionId

        val storage = InMemoryEventsStorage()
        val grovsService = mockk<IGrovsService>(relaxed = true)
        val localCache = mockk<ILocalCache>(relaxed = true)
        every { localCache.numberOfOpens } returns 1 // skip install/reinstall event creation
        every { localCache.lastStartTimestamp } returns null // skip reactivation event creation

        val eventsManager = EventsManager(
            context = RuntimeEnvironment.getApplication(),
            grovsContext = grovsContext,
            apiKey = "test-api-key",
            grovsService = grovsService,
            eventsStorage = storage,
            localCache = localCache
        )
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        // Create the APP_OPEN event while still in session A.
        eventsManager.logAppLaunchEvents()
        val queuedEvent = storage.storedEvents.single { it.event == EventType.APP_OPEN }
        assertEquals(sessionA, queuedEvent.sessionId)

        // First send attempt fails (e.g. no network) - the event stays queued.
        coEvery { grovsService.addEvents(any()) } returns LSResult.Error(IOException("no network"))
        eventsManager.onAppForegrounded()
        assertEquals("event must still be queued after a failed send", 1,
            storage.storedEvents.count { it.event == EventType.APP_OPEN })

        // App backgrounds for over 30 minutes and comes back - the session rotates.
        grovsContext.markBackgrounded()
        backdateBackgrounded(grovsContext, 31)
        grovsContext.rotateSessionIfNeeded()
        val sessionB = grovsContext.sessionId
        assertNotEquals(sessionA, sessionB)

        // Network is back; the flush loop resends the still-queued event.
        val sentEvents = mutableListOf<Event>()
        coEvery { grovsService.addEvents(any()) } answers {
            val sent = firstArg<List<Event>>()
            sentEvents.addAll(sent)
            LSResult.Success(BatchEventsResponse(accepted = sent.size, rejected = 0))
        }
        eventsManager.onAppForegrounded()

        val resentEvent = sentEvents.first { it.event == EventType.APP_OPEN }
        assertEquals("resent event must keep the session it was created in", sessionA, resentEvent.sessionId)
        assertNotEquals("resent event must NOT be relabeled with the session active at send time", sessionB, resentEvent.sessionId)
    }

    @Test
    fun `session id is stable while the app stays in the foreground`() {
        val context = GrovsContext()
        val first = context.sessionId

        context.rotateSessionIfNeeded()

        assertEquals(first, context.sessionId)
    }

    @Test
    fun `session rotates after more than 30 minutes backgrounded`() {
        val context = GrovsContext()
        val first = context.sessionId

        context.markBackgrounded()
        backdateBackgrounded(context, 31)
        context.rotateSessionIfNeeded()

        assertNotEquals(first, context.sessionId)
    }

    @Test
    fun `session does not rotate under 30 minutes backgrounded`() {
        val context = GrovsContext()
        val first = context.sessionId

        context.markBackgrounded()
        backdateBackgrounded(context, 29)
        context.rotateSessionIfNeeded()

        assertEquals(first, context.sessionId)
    }

    @Test
    fun `backgroundedAt is cleared once the session check runs`() {
        val context = GrovsContext()

        context.markBackgrounded()
        backdateBackgrounded(context, 31)
        context.rotateSessionIfNeeded()

        // A second foreground with no intervening background must not rotate again,
        // even after more than the timeout passes.
        val afterRotation = context.sessionId
        context.rotateSessionIfNeeded()

        assertEquals(afterRotation, context.sessionId)
    }
}
