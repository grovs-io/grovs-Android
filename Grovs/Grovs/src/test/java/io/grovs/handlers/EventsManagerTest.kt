package io.grovs.handlers

import android.app.Application
import android.content.Context
import io.grovs.TestAssertions.assertAllowedToSendToBackend
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertNullWithContext
import io.grovs.TestAssertions.assertEventStored
import io.grovs.model.BatchEventError
import io.grovs.model.BatchEventsResponse
import io.grovs.model.DebugLogger
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.LogLevel
import io.grovs.service.IGrovsService
import io.grovs.storage.IEventsStorage
import io.grovs.storage.ILocalCache
import io.grovs.model.events.PaymentEvent
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Core unit tests for EventsManager.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EventsManagerTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var grovsContext: GrovsContext
    private lateinit var mockGrovsService: IGrovsService
    private lateinit var mockEventsStorage: IEventsStorage
    private lateinit var mockLocalCache: ILocalCache
    private lateinit var eventsManager: EventsManager

    private val testApiKey = "test-api-key-123"

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)

        context = RuntimeEnvironment.getApplication()
        application = RuntimeEnvironment.getApplication()

        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true

        mockGrovsService = mockk(relaxed = true)
        mockEventsStorage = mockk(relaxed = true)
        mockLocalCache = mockk(relaxed = true)

        coEvery { mockEventsStorage.getEvents() } returns emptyList()
        coEvery { mockEventsStorage.hasEmptyTimeSpentEvent() } returns false
        every { mockLocalCache.numberOfOpens } returns 0
        every { mockLocalCache.resignTimestamp } returns null
        every { mockLocalCache.lastStartTimestamp } returns null

        DebugLogger.instance.logLevel = LogLevel.INFO

        eventsManager = EventsManager(
            context = context,
            grovsContext = grovsContext,
            apiKey = testApiKey,
            grovsService = mockGrovsService,
            eventsStorage = mockEventsStorage,
            localCache = mockLocalCache
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Constructor Tests ====================

    @Test
    fun `EventsManager constructor initializes with provided context`() {
        assertNotNullWithContext(
            eventsManager.context,
            "context",
            "after construction with test context"
        )
        assertEqualsWithContext(
            context,
            eventsManager.context,
            "context",
            "after construction with test context"
        )
    }

    @Test
    fun `EventsManager allowedToSendToBackend is false when newly constructed`() {
        assertAllowedToSendToBackend(
            eventsManager,
            expected = false,
            context = "after construction with default settings"
        )
    }

    // ==================== App Lifecycle Tests ====================

    @Test
    fun `EventsManager onAppForegrounded sends queued events to backend when sending allowed`() = runTest {
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        val testEvent = Event(EventType.APP_OPEN, InstantCompat.now())
        coEvery { mockEventsStorage.getEvents() } returns listOf(testEvent)
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))
        coEvery { mockEventsStorage.removeEvents(any()) } returns Unit

        eventsManager.onAppForegrounded()

        coVerify { mockEventsStorage.getEvents() }
        coVerify(timeout = 1000) { mockGrovsService.addEvents(match { l -> l.any { it == testEvent } }) }
    }

    @Test
    fun `EventsManager onAppBackgrounded sets resignTimestamp in localCache`() {
        eventsManager.onAppBackgrounded()

        verify(timeout = 1_000) { mockLocalCache.resignTimestamp = any() }
    }

    @Test
    fun `EventsManager onAppBackgrounded clears linkForFutureActions`() {
        eventsManager.linkForFutureActions = "https://test.link"

        eventsManager.onAppBackgrounded()

        assertNullWithContext(
            eventsManager.linkForFutureActions,
            "linkForFutureActions",
            "after onAppBackgrounded() with link previously set to 'https://test.link'"
        )
    }

    // ==================== Launch Events Tests ====================

    @Test
    fun `EventsManager logAppLaunchEvents stores INSTALL event when numberOfOpens is zero`() = runTest {
        every { mockLocalCache.numberOfOpens } returns 0

        eventsManager.logAppLaunchEvents()

        assertEventStored(
            eventType = EventType.INSTALL,
            mockStorage = mockEventsStorage,
            context = "after logAppLaunchEvents() with numberOfOpens=0 (first launch)"
        )
    }

    @Test
    fun `EventsManager logAppLaunchEvents stores REINSTALL event when lastSeen exists on first launch`() = runTest {
        every { mockLocalCache.numberOfOpens } returns 0
        grovsContext.lastSeen = InstantCompat.now()

        eventsManager.logAppLaunchEvents()

        assertEventStored(
            eventType = EventType.REINSTALL,
            mockStorage = mockEventsStorage,
            context = "after logAppLaunchEvents() with numberOfOpens=0 and lastSeen set"
        )
    }

    @Test
    fun `EventsManager logAppLaunchEvents stores REACTIVATION event when inactive for 7+ days`() = runTest {
        every { mockLocalCache.numberOfOpens } returns 5
        val eightDaysAgo = InstantCompat.now().minusMillis(8L * 24 * 60 * 60 * 1000)
        every { mockLocalCache.lastStartTimestamp } returns eightDaysAgo

        eventsManager.logAppLaunchEvents()

        assertEventStored(
            eventType = EventType.REACTIVATION,
            mockStorage = mockEventsStorage,
            context = "after logAppLaunchEvents() with lastStartTimestamp 8 days ago"
        )
    }

    @Test
    fun `EventsManager logAppLaunchEvents stores APP_OPEN event on subsequent launches`() = runTest {
        every { mockLocalCache.numberOfOpens } returns 1

        eventsManager.logAppLaunchEvents()

        assertEventStored(
            eventType = EventType.APP_OPEN,
            mockStorage = mockEventsStorage,
            context = "after logAppLaunchEvents() with numberOfOpens=1"
        )
    }

    // ==================== Event Logging Tests ====================

    @Test
    fun `EventsManager log stores event in storage`() = runTest {
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        val event = Event(EventType.VIEW, InstantCompat.now())

        eventsManager.log(event)

        coVerify { mockEventsStorage.addEvent(event) }
    }

    @Test
    fun `EventsManager log sets linkForFutureActions on event when link not already set`() = runTest {
        eventsManager.linkForFutureActions = "https://test.link"
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        val event = Event(EventType.VIEW, InstantCompat.now())

        eventsManager.log(event)

        coVerify { mockEventsStorage.addEvent(match { it.link == "https://test.link" }) }
    }

    @Test
    fun `EventsManager log sends events to backend when allowed`() = runTest {
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        val event = Event(EventType.VIEW, InstantCompat.now())
        coEvery { mockEventsStorage.getEvents() } returns listOf(event)
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))

        eventsManager.log(event)

        coVerify { mockGrovsService.addEvents(any()) }
    }

    // ==================== Purchase Tests ====================

    // ==================== Link Association Tests ====================

    @Test
    fun `EventsManager setLinkForFutureEvents only stamps events logged afterwards`() = runTest {
        val existingEvent = Event(EventType.APP_OPEN, InstantCompat.now())
        coEvery { mockEventsStorage.getEvents() } returns listOf(existingEvent)

        eventsManager.setLinkForFutureEvents("https://test.link")

        assertEqualsWithContext(
            "https://test.link",
            eventsManager.linkForFutureActions,
            "linkForFutureActions",
            "after setLinkForFutureEvents('https://test.link')"
        )
        coVerify(exactly = 0) { mockEventsStorage.addOrReplaceEvents(any()) }
        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
    }

    @Test
    fun `EventsManager completeLinkResolution enables backend sending when delayEvents is false`() = runTest {
        eventsManager.completeLinkResolution("https://test.link", delayEvents = false)

        assertAllowedToSendToBackend(
            eventsManager,
            expected = true,
            context = "after completeLinkResolution() with delayEvents=false"
        )
    }

    @Test
    fun `EventsManager completeLinkResolution associates link with existing events`() = runTest {
        val existingEvent = Event(EventType.APP_OPEN, InstantCompat.now(), sessionId = grovsContext.sessionId)
        coEvery { mockEventsStorage.getEvents() } returns listOf(existingEvent)

        eventsManager.completeLinkResolution("https://test.link", delayEvents = false)

        coVerify {
            mockEventsStorage.addOrReplaceEvents(match { events ->
                events.any { it.link == "https://test.link" }
            })
        }
    }

    // ==================== Backend Sending Tests ====================

    @Test
    fun `a disabled SDK sends no queued lifecycle or payment events`() = runTest {
        eventsManager.allowedToSendToBackend = true
        coEvery { mockEventsStorage.getEvents() } returns listOf(
            Event(EventType.APP_OPEN, InstantCompat.now()),
            Event(EventType.TIME_SPENT, InstantCompat.now(), engagementTime = 5),
        )
        coEvery { mockEventsStorage.getPaymentEvents() } returns listOf(
            io.grovs.model.events.PaymentEvent(eventType = io.grovs.model.events.PaymentEventType.BUY, appId = "app",
                priceCents = 100, currency = "USD", date = InstantCompat.now(), productId = "sku", store = false)
        )
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))
        coEvery { mockGrovsService.addPaymentEvent(any()) } returns LSResult.Success(true)
        grovsContext.settings.sdkEnabled = false

        eventsManager.onAppForegrounded()
        eventsManager.log(Event(EventType.VIEW, InstantCompat.now()))
        eventsManager.releaseLinkResolution(delayEvents = false)

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
        coVerify(exactly = 0) { mockGrovsService.addPaymentEvent(any()) }
        assertTrue("Sending stays allowed so re-enabling delivers the retained events", eventsManager.allowedToSendToBackend)
    }

    @Test
    fun `EventsManager does not send events when allowedToSendToBackend is false`() = runTest {
        eventsManager.allowedToSendToBackend = false

        val event = Event(EventType.VIEW, InstantCompat.now())
        eventsManager.log(event)

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
    }

    @Test
    fun `EventsManager removes successfully sent events from storage`() = runTest {
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        val event = Event(EventType.VIEW, InstantCompat.now())
        coEvery { mockEventsStorage.getEvents() } returns listOf(event)
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))

        eventsManager.log(event)

        coVerify { mockEventsStorage.removeEvents(match { event in it }) }
    }

    // ==================== session_id on payment events ====================

    @Test
    fun `logCustomPurchase stamps the current session id on the payment event`() = runTest {
        val captured = slot<io.grovs.model.events.PaymentEvent>()
        coEvery { mockEventsStorage.addPaymentEvent(capture(captured)) } just Runs
        coEvery { mockEventsStorage.getPaymentEvents() } returns emptyList()

        eventsManager.logCustomPurchase(
            type = io.grovs.model.events.PaymentEventType.BUY,
            priceInCents = 499,
            currency = "USD",
            productId = "pro",
            startDate = InstantCompat.now()
        )

        assertEqualsWithContext(
            grovsContext.sessionId,
            captured.captured.sessionId,
            "paymentEvent.sessionId",
            "after logCustomPurchase()"
        )
    }

    // ==================== Events hold ====================

    @Test
    fun `held events never flush even when delayEvents is false`() = runTest {
        val stored = Event(event = EventType.INSTALL, createdAt = InstantCompat.now())
        coEvery { mockEventsStorage.getEvents() } returns listOf(stored)
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))

        eventsManager.setEventsHeld(true)
        eventsManager.log(Event(event = EventType.VIEW, createdAt = InstantCompat.now()))

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
        assertAllowedToSendToBackend(eventsManager, expected = false, context = "while events are held")
    }

    @Test
    fun `held events never flush even after the delay window has passed`() = runTest {
        val stored = Event(event = EventType.INSTALL, createdAt = InstantCompat.now())
        coEvery { mockEventsStorage.getEvents() } returns listOf(stored)
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))
        eventsManager.eventsDelaySeconds = 0
        eventsManager.firstRequestTime = InstantCompat.ofEpochMilli(0)

        eventsManager.setEventsHeld(true)
        eventsManager.log(Event(event = EventType.VIEW, createdAt = InstantCompat.now()))

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
    }

    @Test
    fun `releasing the hold flushes without changing the link`() = runTest {
        val stored = Event(event = EventType.INSTALL, createdAt = InstantCompat.now())
        coEvery { mockEventsStorage.getEvents() } returns listOf(stored)
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))

        eventsManager.beginLinkResolution()
        eventsManager.setLinkForFutureEvents("https://test.link/direct")
        eventsManager.log(Event(event = EventType.VIEW, createdAt = InstantCompat.now()))
        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }

        eventsManager.setEventsHeld(false)
        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }   // clearing alone does not flush

        eventsManager.releaseLinkResolution(delayEvents = false)
        // Releasing queues the delivery rather than waiting for it.
        coVerify(timeout = 2_000, exactly = 1) { mockGrovsService.addEvents(any()) }
        assertEqualsWithContext(
            "https://test.link/direct",
            eventsManager.linkForFutureActions,
            "linkForFutureActions",
            "after releaseLinkResolution()"
        )
    }

    @Test
    fun `flush requests queued behind a delivery in flight collapse into one`() = runTest {
        coEvery { mockEventsStorage.getEvents() } returns listOf(Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now()))
        val uploads = java.util.concurrent.atomic.AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val firstUpload = CompletableDeferred<Unit>()
        coEvery { mockGrovsService.addEvents(any()) } coAnswers {
            if (uploads.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                firstUpload.await()
            }
            accepted(1)
        }
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        val first = async { eventsManager.flush() }
        firstStarted.await()
        try {
            // Foreground, log(), link release and the deadline can all ask while one delivery runs.
            val returned = withTimeoutOrNull(1_000) { repeat(3) { eventsManager.requestFlush() }; true }
            assertEquals("requestFlush must only queue a delivery", true, returned)
        } finally {
            firstUpload.complete(Unit)
        }
        first.await()
        // Queued behind those requests, so once it returns they have all been served.
        eventsManager.flush()

        assertEquals("the delivery in flight, one shared by the three requests, then the last flush", 3, uploads.get())
    }

    @Test
    fun `releasing the hold queues the delivery instead of waiting for it`() = runTest {
        coEvery { mockEventsStorage.getEvents() } returns listOf(Event(event = EventType.INSTALL, createdAt = InstantCompat.now()))
        val upload = CompletableDeferred<Unit>()
        coEvery { mockGrovsService.addEvents(any()) } coAnswers { upload.await(); accepted(1) }
        eventsManager.beginLinkResolution()

        try {
            // GrovsManager releases the hold under its resolution lock, so this must not wait on the network.
            val returned = withTimeoutOrNull(1_000) { eventsManager.releaseLinkResolution(delayEvents = false); true }
            assertEquals("releaseLinkResolution must return while the upload is still in flight", true, returned)
        } finally {
            upload.complete(Unit)
        }

        coVerify(timeout = 2_000, exactly = 1) { mockGrovsService.addEvents(any()) }
    }

    // ==================== flush() ====================

    private fun storedEvents(count: Int, type: EventType = EventType.APP_OPEN): List<Event> =
        List(count) { Event(event = type, createdAt = InstantCompat.ofEpochMilli(1_000L + it)) }

    private fun accepted(n: Int) = LSResult.Success(BatchEventsResponse(accepted = n, rejected = 0))

    /**
     * Stateful stand-in for EventsStorage: relaxed mockk mocks don't retain state across calls, but
     * the overlapping-flush tests need removal to be real so a second flush can observe it.
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

        override suspend fun removeEvents(events: List<Event>) {
            val doomed = events.toSet()
            storedEvents.removeAll { it in doomed }
        }

        override suspend fun removePaymentEvent(event: PaymentEvent) {}

        override suspend fun replacePaymentEvents(events: List<PaymentEvent>) {}

        override suspend fun getEvents(): List<Event> = storedEvents.toList()

        override suspend fun getPaymentEvents(): List<PaymentEvent> = emptyList()

        override suspend fun hasEmptyTimeSpentEvent(): Boolean = false
    }

    @Test
    fun `flush sends ready events in chunks of BATCH_SIZE and removes each accepted chunk`() = runTest {
        val events = storedEvents(EventsManager.BATCH_SIZE + 3)
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockGrovsService.addEvents(any()) } answers { accepted(firstArg<List<Event>>().size) }
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerifyOrder {
            mockGrovsService.addEvents(events.take(EventsManager.BATCH_SIZE))
            mockEventsStorage.removeEvents(events.take(EventsManager.BATCH_SIZE))
            mockGrovsService.addEvents(events.drop(EventsManager.BATCH_SIZE))
            mockEventsStorage.removeEvents(events.drop(EventsManager.BATCH_SIZE))
        }
    }

    @Test
    fun `flush stops at the first failed chunk and keeps it stored`() = runTest {
        val events = storedEvents(EventsManager.BATCH_SIZE + 1)
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Error(java.io.IOException("down"))
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        val started = System.nanoTime()
        eventsManager.flush()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        coVerify(exactly = 1) { mockGrovsService.addEvents(any()) }
        coVerify(exactly = 0) { mockEventsStorage.removeEvents(any()) }
        assertTrue("A failed chunk must not sleep on the serial dispatcher (took ${elapsedMs}ms)", elapsedMs < 1_000)
        // The wall-clock check above cannot catch a delay(): inside runTest a delay advances virtual
        // time instead of blocking, so a reintroduced delay(5000) would still report ~0ms elapsed.
        // Assert no virtual time was consumed either.
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a chunk with rejected items is still removed`() = runTest {
        val events = storedEvents(2)
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(
            BatchEventsResponse(accepted = 1, rejected = 1, rawErrors = listOf(BatchEventError(1, "unknown event type")))
        )
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerify(exactly = 1) { mockEventsStorage.removeEvents(events) }
    }

    @Test
    fun `an event the backend refuses on its own is dropped and the rest of its chunk delivered`() = runTest {
        val events = storedEvents(4)
        val refused = events[2]
        val removed = mutableListOf<Event>()
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockEventsStorage.removeEvents(any()) } answers { removed += firstArg<List<Event>>(); Unit }
        coEvery { mockGrovsService.addEvents(any()) } answers {
            val part = firstArg<List<Event>>()
            if (refused in part) LSResult.Error(io.grovs.service.HttpStatusException(400, "refused")) else accepted(part.size)
        }
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        assertEquals(events.toSet(), removed.toSet())
        assertEquals(refused, removed.last())
    }

    // A distinct transaction token per payment: PaymentEvent equality is type + token + date.
    private fun payment(sku: String) = PaymentEvent(
        eventType = io.grovs.model.events.PaymentEventType.BUY, appId = "app", priceCents = 100,
        currency = "USD", date = InstantCompat.ofEpochMilli(1_000L), transactionToken = "token-$sku",
        productId = sku, store = false,
    )

    @Test
    fun `a payment the backend refuses does not block the payments behind it`() = runTest {
        val invalid = payment("invalid")
        val valid = payment("valid")
        val removed = mutableListOf<PaymentEvent>()
        coEvery { mockEventsStorage.getPaymentEvents() } returns listOf(invalid, valid)
        coEvery { mockEventsStorage.removePaymentEvent(any()) } answers { removed += firstArg<PaymentEvent>(); Unit }
        coEvery { mockGrovsService.addPaymentEvent(invalid) } returns LSResult.Error(io.grovs.service.HttpStatusException(422, "invalid"))
        coEvery { mockGrovsService.addPaymentEvent(valid) } returns LSResult.Success(true)
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerify(exactly = 1) { mockGrovsService.addPaymentEvent(valid) }
        assertEquals("the accepted payment proves the refusal is about the invalid one", listOf(valid, invalid), removed)
    }

    @Test
    fun `a refused payment is kept while no other payment is accepted`() = runTest {
        coEvery { mockEventsStorage.getPaymentEvents() } returns listOf(payment("invalid"))
        coEvery { mockGrovsService.addPaymentEvent(any()) } returns LSResult.Error(io.grovs.service.HttpStatusException(422, "not configured"))
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerify(exactly = 0) { mockEventsStorage.removePaymentEvent(any()) }
    }

    @Test
    fun `a transient payment failure stops the flush and keeps every payment`() = runTest {
        val first = payment("first")
        val second = payment("second")
        coEvery { mockEventsStorage.getPaymentEvents() } returns listOf(first, second)
        coEvery { mockGrovsService.addPaymentEvent(first) } returns LSResult.Error(java.io.IOException("connection reset"))
        coEvery { mockGrovsService.addPaymentEvent(second) } returns LSResult.Success(true)
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerify(exactly = 0) { mockGrovsService.addPaymentEvent(second) }
        coVerify(exactly = 0) { mockEventsStorage.removePaymentEvent(any()) }
    }

    @Test
    fun `open time-spent nodes are not sent, closed ones are`() = runTest {
        val open = Event(event = EventType.TIME_SPENT, createdAt = InstantCompat.ofEpochMilli(1_000L))
        val closed = Event(event = EventType.TIME_SPENT, createdAt = InstantCompat.ofEpochMilli(2_000L), engagementTime = 30)
        coEvery { mockEventsStorage.getEvents() } returns listOf(open, closed)
        coEvery { mockGrovsService.addEvents(any()) } answers { accepted(firstArg<List<Event>>().size) }
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerify(exactly = 1) { mockGrovsService.addEvents(listOf(closed)) }
    }

    @Test
    fun `flush sends nothing while events are held`() = runTest {
        coEvery { mockEventsStorage.getEvents() } returns storedEvents(1)
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()
        eventsManager.setEventsHeld(true)

        eventsManager.flush()

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
    }

    @Test
    fun `flush sends nothing while the SDK is disabled`() = runTest {
        coEvery { mockEventsStorage.getEvents() } returns storedEvents(1)
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()
        grovsContext.settings.sdkEnabled = false

        eventsManager.flush()

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
    }

    @Test
    fun `a hold arriving mid-flush stops further chunks`() = runTest {
        val events = storedEvents(EventsManager.BATCH_SIZE + 1)
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockGrovsService.addEvents(any()) } answers {
            eventsManager.setEventsHeld(true)
            accepted(firstArg<List<Event>>().size)
        }
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerify(exactly = 1) { mockGrovsService.addEvents(any()) }
    }

    @Test
    fun `the SDK being disabled mid-flush stops further chunks`() = runTest {
        val events = storedEvents(EventsManager.BATCH_SIZE + 1)
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockGrovsService.addEvents(any()) } answers {
            grovsContext.settings.sdkEnabled = false
            accepted(firstArg<List<Event>>().size)
        }
        eventsManager.allowedToSendToBackend = true
        eventsManager.firstRequestTime = InstantCompat.now()

        eventsManager.flush()

        coVerify(exactly = 1) { mockGrovsService.addEvents(any()) }
    }

    // ==================== flush() serialization ====================

    /** Builds a real (non-mocked storage) EventsManager sharing this fixture's service mock. */
    private fun realStorageManager(storage: IEventsStorage): EventsManager =
        EventsManager(
            context = context,
            grovsContext = grovsContext,
            apiKey = testApiKey,
            grovsService = mockGrovsService,
            eventsStorage = storage,
            localCache = mockLocalCache
        ).also {
            it.allowedToSendToBackend = true
            it.firstRequestTime = InstantCompat.now()
        }

    @Test
    fun `overlapping flushes cannot double-post the same events`() = runTest {
        val storage = InMemoryEventsStorage()
        val e1 = Event(event = EventType.APP_OPEN, createdAt = InstantCompat.ofEpochMilli(1_000L))
        val e2 = Event(event = EventType.APP_OPEN, createdAt = InstantCompat.ofEpochMilli(2_000L))
        storage.storedEvents.addAll(listOf(e1, e2))
        val manager = realStorageManager(storage)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val postedBatches = mutableListOf<List<Event>>()
        coEvery { mockGrovsService.addEvents(any()) } coAnswers {
            val batch = firstArg<List<Event>>()
            postedBatches.add(batch)
            started.complete(Unit)
            gate.await()
            accepted(batch.size)
        }

        // The first flush reaches addEvents and occupies the delivery worker for the rest of its run.
        val first = async { manager.flush() }
        started.await()
        // A second, fully overlapping trigger (foreground, log(), the leeway timer, ...) queues on
        // the worker instead of running concurrently against the same not-yet-removed events.
        val second = async { manager.flush() }
        gate.complete(Unit)
        first.await()
        second.await()

        // Serialization means the second flush only ever runs after the first's removeEvents has
        // already emptied storage, so it finds nothing left to post — each event was posted exactly
        // once, by the first flush.
        assertEquals("only the first flush should have posted anything", 1, postedBatches.size)
        assertEquals(setOf(e1, e2), postedBatches.single().toSet())
        assertTrue("storage should be empty once both flushes settle", storage.storedEvents.isEmpty())
    }

    @Test
    fun `completeLinkResolution starting during an in-flight flush does not lose the link`() = runTest {
        val storage = InMemoryEventsStorage()
        val install = Event(event = EventType.INSTALL, createdAt = InstantCompat.ofEpochMilli(1_000L), sessionId = grovsContext.sessionId)
        storage.storedEvents.add(install)
        val manager = realStorageManager(storage)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val postedBatches = mutableListOf<List<Event>>()
        coEvery { mockGrovsService.addEvents(any()) } coAnswers {
            val batch = firstArg<List<Event>>()
            postedBatches.add(batch)
            started.complete(Unit)
            gate.await()
            accepted(batch.size)
        }

        // The flush is already in flight (on the delivery worker, mid network call) before
        // resolution commits the link.
        val flushJob = async { manager.flush() }
        started.await()
        val commit = async { manager.completeLinkResolution("https://test.link/resolved", delayEvents = false) }
        gate.complete(Unit)
        flushJob.await()
        commit.await()

        // Ordering this test observes: the delivery worker runs tasks in the order they were queued
        // and the flush was queued first, so completeLinkResolution's storage rewrite cannot start
        // until the whole in-flight flush —
        // network round trip and removeEvents(chunk) — has finished. By the time the rewrite runs,
        // the INSTALL event is already gone from storage (successfully sent), so there is nothing
        // left to relink: the event goes out unlinked (attribution resolved after it had already
        // left the device, same as if completeLinkResolution had simply been called a moment later),
        // and no stale or orphaned copy is left behind for a later flush to double-post.
        assertEquals(1, postedBatches.size)
        assertNull("the in-flight flush had already read the event before the link resolved", postedBatches.single().single().link)
        assertTrue("no orphaned copy should be left in storage", storage.storedEvents.isEmpty())
    }
}
