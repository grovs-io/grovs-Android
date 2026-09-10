package io.grovs.handlers

import android.app.Application
import android.content.Context
import io.grovs.TestAssertions.assertAllowedToSendToBackend
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.TestAssertions.assertEventStored
// PURCHASE_EVENT_DISABLED: import io.grovs.TestAssertions.assertPaymentEventStored
import io.grovs.model.BatchEventError
import io.grovs.model.BatchEventsResponse
import io.grovs.model.DebugLogger
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.LogLevel
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEvent
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.service.IGrovsService
import io.grovs.storage.IEventsStorage
import io.grovs.storage.ILocalCache
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
        // PURCHASE_EVENT_DISABLED: coEvery { mockEventsStorage.getPaymentEvents() } returns emptyList()
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

        verify { mockLocalCache.resignTimestamp = any() }
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

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `EventsManager logInAppPurchase parses originalJson and stores payment event`() = runTest {
    // PURCHASE_EVENT_DISABLED:     eventsManager.allowedToSendToBackend = true
    // PURCHASE_EVENT_DISABLED:     eventsManager.firstRequestTime = InstantCompat.now()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     val originalJson = """
    // PURCHASE_EVENT_DISABLED:         {
    // PURCHASE_EVENT_DISABLED:             "orderId": "GPA.1234-5678",
    // PURCHASE_EVENT_DISABLED:             "packageName": "io.grovs.test",
    // PURCHASE_EVENT_DISABLED:             "productId": "premium_subscription",
    // PURCHASE_EVENT_DISABLED:             "purchaseTime": 1234567890000,
    // PURCHASE_EVENT_DISABLED:             "purchaseState": 0,
    // PURCHASE_EVENT_DISABLED:             "purchaseToken": "token123"
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:     """.trimIndent()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     eventsManager.logInAppPurchase(originalJson)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     coVerify { mockEventsStorage.addPaymentEvent(any()) }
    // PURCHASE_EVENT_DISABLED: }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `EventsManager logCustomPurchase stores payment event with correct type`() = runTest {
    // PURCHASE_EVENT_DISABLED:     eventsManager.allowedToSendToBackend = true
    // PURCHASE_EVENT_DISABLED:     eventsManager.firstRequestTime = InstantCompat.now()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     eventsManager.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:         type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         priceInCents = 999,
    // PURCHASE_EVENT_DISABLED:         currency = "USD",
    // PURCHASE_EVENT_DISABLED:         productId = "premium"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     assertPaymentEventStored(
    // PURCHASE_EVENT_DISABLED:         eventType = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         mockStorage = mockEventsStorage,
    // PURCHASE_EVENT_DISABLED:         context = "after logCustomPurchase() with type=BUY"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED: }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `EventsManager logCustomPurchase includes linkForFutureActions on payment event`() = runTest {
    // PURCHASE_EVENT_DISABLED:     eventsManager.linkForFutureActions = "https://test.link"
    // PURCHASE_EVENT_DISABLED:     eventsManager.allowedToSendToBackend = true
    // PURCHASE_EVENT_DISABLED:     eventsManager.firstRequestTime = InstantCompat.now()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     eventsManager.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:         type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         priceInCents = 999,
    // PURCHASE_EVENT_DISABLED:         currency = "USD",
    // PURCHASE_EVENT_DISABLED:         productId = "premium"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     coVerify { mockEventsStorage.addPaymentEvent(match { it.link == "https://test.link" }) }
    // PURCHASE_EVENT_DISABLED: }

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
        coVerify(exactly = 1) { mockGrovsService.addEvents(any()) }
        assertEqualsWithContext(
            "https://test.link/direct",
            eventsManager.linkForFutureActions,
            "linkForFutureActions",
            "after releaseLinkResolution()"
        )
    }

    // ==================== flush() ====================

    private fun storedEvents(count: Int, type: EventType = EventType.APP_OPEN): List<Event> =
        List(count) { Event(event = type, createdAt = InstantCompat.ofEpochMilli(1_000L + it)) }

    private fun accepted(n: Int) = LSResult.Success(BatchEventsResponse(accepted = n, rejected = 0))

    @Test
    fun `flush sends ready events in chunks of BATCH_SIZE and removes each accepted chunk`() = runTest {
        val events = storedEvents(EventsManager.BATCH_SIZE + 3)
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockGrovsService.addEvents(any()) } answers { accepted(firstArg<List<Event>>().size) }
        eventsManager.allowedToSendToBackend = true

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

        val started = System.nanoTime()
        eventsManager.flush()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        coVerify(exactly = 1) { mockGrovsService.addEvents(any()) }
        coVerify(exactly = 0) { mockEventsStorage.removeEvents(any()) }
        assertTrue("A failed chunk must not sleep on the serial dispatcher (took ${elapsedMs}ms)", elapsedMs < 1_000)
    }

    @Test
    fun `a chunk with rejected items is still removed`() = runTest {
        val events = storedEvents(2)
        coEvery { mockEventsStorage.getEvents() } returns events
        coEvery { mockGrovsService.addEvents(any()) } returns LSResult.Success(
            BatchEventsResponse(accepted = 1, rejected = 1, rawErrors = listOf(BatchEventError(1, "unknown event type")))
        )
        eventsManager.allowedToSendToBackend = true

        eventsManager.flush()

        coVerify(exactly = 1) { mockEventsStorage.removeEvents(events) }
    }

    @Test
    fun `open time-spent nodes are not sent, closed ones are`() = runTest {
        val open = Event(event = EventType.TIME_SPENT, createdAt = InstantCompat.ofEpochMilli(1_000L))
        val closed = Event(event = EventType.TIME_SPENT, createdAt = InstantCompat.ofEpochMilli(2_000L), engagementTime = 30)
        coEvery { mockEventsStorage.getEvents() } returns listOf(open, closed)
        coEvery { mockGrovsService.addEvents(any()) } answers { accepted(firstArg<List<Event>>().size) }
        eventsManager.allowedToSendToBackend = true

        eventsManager.flush()

        coVerify(exactly = 1) { mockGrovsService.addEvents(listOf(closed)) }
    }

    @Test
    fun `flush sends nothing while events are held`() = runTest {
        coEvery { mockEventsStorage.getEvents() } returns storedEvents(1)
        eventsManager.allowedToSendToBackend = true
        eventsManager.setEventsHeld(true)

        eventsManager.flush()

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
    }

    @Test
    fun `flush sends nothing while the SDK is disabled`() = runTest {
        coEvery { mockEventsStorage.getEvents() } returns storedEvents(1)
        eventsManager.allowedToSendToBackend = true
        grovsContext.settings.sdkEnabled = false

        eventsManager.flush()

        coVerify(exactly = 0) { mockGrovsService.addEvents(any()) }
    }
}
