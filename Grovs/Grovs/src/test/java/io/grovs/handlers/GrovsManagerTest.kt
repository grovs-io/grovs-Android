package io.grovs.handlers

import android.app.Application
import android.content.ComponentName
import android.content.Context
import io.grovs.TestFixtures
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import com.google.android.finsky.externalreferrer.IGetInstallReferrerService
import io.grovs.TestAssertions.assertUnauthenticated
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertNullWithContext
import io.grovs.TestAssertions.assertResultSuccess
import io.grovs.TestAssertions.assertResultError
import io.grovs.TestAssertions.assertResultErrorContains
import io.grovs.model.AppDetails
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.LogLevel
import io.grovs.model.events.PaymentEventType
import io.grovs.model.AuthenticationResponse
import io.grovs.model.GetDeviceResponse
import io.grovs.service.IGrovsService
import io.grovs.utils.GVRetryResult
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.LSResult
import io.grovs.FakeClipboard
import io.grovs.FakeLocalCache
import io.grovs.storage.EventsStorage
import io.grovs.storage.ILocalCache
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Core unit tests for GrovsManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GrovsManagerTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var grovsContext: GrovsContext
    private lateinit var mockGrovsService: IGrovsService
    private lateinit var mockEventsManager: IEventsManager
    private lateinit var mockAppDetailsHelper: IAppDetailsHelper
    private lateinit var grovsManager: GrovsManager

    private val testApiKey = "test-api-key-123"

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val REFERRER_SERVICE_CLASS = "com.google.android.finsky.externalreferrer.GetInstallReferrerService"
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)

        context = RuntimeEnvironment.getApplication()
        application = RuntimeEnvironment.getApplication()

        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true

        mockGrovsService = mockk(relaxed = true)
        mockEventsManager = mockk(relaxed = true)
        mockAppDetailsHelper = mockk(relaxed = true)

        coEvery { mockAppDetailsHelper.toAppDetails() } returns TestFixtures.createAppDetails()
        every { mockAppDetailsHelper.deviceID } returns "test-device-id"
        every { mockAppDetailsHelper.versionName } returns "1.0.0"
        every { mockAppDetailsHelper.versionCode } returns 1
        every { mockAppDetailsHelper.applicationId } returns "io.grovs.test"
        every { mockAppDetailsHelper.device } returns "Test Device"

        DebugLogger.instance.logLevel = LogLevel.INFO

        grovsManager = GrovsManager(
            context = context,
            application = application,
            grovsContext = grovsContext,
            apiKey = testApiKey,
            grovsService = mockGrovsService,
            eventsManager = mockEventsManager,
            appDetailsHelper = mockAppDetailsHelper,
            // A real LocalCache on fresh Robolectric prefs reads numberOfOpens == 0, which would arm
            // ClipboardHandler and silently reroute the pre-existing (non-clipboard) tests below into
            // the clipboard flow. Pin this shared manager's install to "not a fresh install" instead.
            localCache = FakeLocalCache(numberOfOpens = 1),
        )
    }

    @After
    fun tearDown() {
        grovsManager.close()
        unmockkAll()
    }

    // ==================== Authentication State Tests ====================

    @Test
    fun `GrovsManager authenticationState is UNAUTHENTICATED when newly constructed`() {
        assertUnauthenticated(
            grovsManager,
            context = "after construction with default settings"
        )
    }

    // ==================== Properties Tests ====================

    @Test
    fun `GrovsManager identifier property updates grovsContext when set`() {
        grovsManager.identifier = "user-123"

        assertEqualsWithContext(
            "user-123",
            grovsContext.identifier,
            "grovsContext.identifier",
            "after setting grovsManager.identifier='user-123'"
        )
    }

    @Test
    fun `GrovsManager pushToken property updates grovsContext when set`() {
        grovsManager.pushToken = "fcm-token-xyz"

        assertEqualsWithContext(
            "fcm-token-xyz",
            grovsContext.pushToken,
            "grovsContext.pushToken",
            "after setting grovsManager.pushToken='fcm-token-xyz'"
        )
    }

    @Test
    fun `GrovsManager attributes property updates grovsContext when set`() {
        val attrs = mapOf("key1" to "value1", "key2" to 42)

        grovsManager.attributes = attrs

        assertEqualsWithContext(
            attrs,
            grovsContext.attributes,
            "grovsContext.attributes",
            "after setting grovsManager.attributes with key1='value1', key2=42"
        )
    }

    // ==================== Lifecycle Tests ====================

    @Test
    fun `GrovsManager onAppForegrounded delegates to eventsManager`() = runTest {
        grovsManager.onAppForegrounded()

        coVerify { mockEventsManager.onAppForegrounded() }
    }

    @Test
    fun `GrovsManager onAppBackgrounded delegates to eventsManager`() {
        grovsManager.onAppBackgrounded()

        verify { mockEventsManager.onAppBackgrounded() }
    }

    @Test
    fun `foreground does nothing and background only clears the future-events link while disabled`() = runTest {
        grovsContext.settings.sdkEnabled = false

        grovsManager.onAppForegrounded()
        grovsManager.onAppBackgrounded()

        coVerify(exactly = 0) { mockEventsManager.onAppForegrounded() }
        verify(exactly = 0) { mockEventsManager.onAppBackgrounded() }
        verify(exactly = 1) { mockEventsManager.setLinkForFutureEvents(null) }
    }

    @Test
    fun `session link committed before a disabled background never reaches the next session's events`() = runTest {
        context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)
            .edit().clear().commit()

        val realEventsStorage = EventsStorage(context)
        val mockLocalCacheForEvents = mockk<ILocalCache>(relaxed = true)
        val eventsGrovsService = mockk<IGrovsService>(relaxed = true)
        // Nothing is sent or held: keep every event queued so the test observes storage state.
        coEvery { eventsGrovsService.addPaymentEvent(any()) } returns LSResult.Error(Exception("not sent in test"))
        coEvery { eventsGrovsService.addEvents(any()) } returns LSResult.Error(Exception("not sent in test"))

        val realEventsManager = EventsManager(
            context = context,
            grovsContext = grovsContext,
            apiKey = testApiKey,
            grovsService = eventsGrovsService,
            eventsStorage = realEventsStorage,
            localCache = mockLocalCacheForEvents,
        )

        val manager = GrovsManager(
            context = context,
            application = application,
            grovsContext = grovsContext,
            apiKey = testApiKey,
            grovsService = mockGrovsService,
            eventsManager = realEventsManager,
            appDetailsHelper = mockAppDetailsHelper,
        )

        realEventsManager.setLinkForFutureEvents("https://test.grovs.io/session-a")

        grovsContext.settings.sdkEnabled = false
        manager.onAppBackgrounded()

        grovsContext.settings.sdkEnabled = true
        manager.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "p")

        val storedPaymentEvents = realEventsStorage.getPaymentEvents()
        assertEqualsWithContext(
            1,
            storedPaymentEvents.size,
            "storedPaymentEvents.size",
            "after logging a purchase in the next session"
        )
        assertNullWithContext(
            storedPaymentEvents.first().link,
            "storedPaymentEvents.first().link",
            "session A's link must not leak onto a session B purchase"
        )

        manager.close()
    }

    private fun managerWithCustomEvents(customEventsManager: ICustomEventsManager) = GrovsManager(
        context = context,
        application = application,
        grovsContext = grovsContext,
        apiKey = testApiKey,
        grovsService = mockGrovsService,
        eventsManager = mockEventsManager,
        appDetailsHelper = mockAppDetailsHelper,
        customEventsManager = customEventsManager,
    )

    @Test
    fun `close disposes the custom events manager exactly once`() {
        val customEventsManager = mockk<ICustomEventsManager>(relaxed = true)
        val manager = managerWithCustomEvents(customEventsManager)

        manager.close()
        manager.close()

        verify(exactly = 1) { customEventsManager.close() }
    }

    @Test
    fun `track rejects reserved and blank names before reaching the events manager`() = runTest {
        val customEventsManager = mockk<ICustomEventsManager>(relaxed = true)
        val manager = managerWithCustomEvents(customEventsManager)

        manager.track("app_open", null, null)
        manager.track("   ", null, null)

        coVerify(exactly = 0) { customEventsManager.track(any(), any(), any()) }
        manager.close()
    }

    @Test
    fun `track forwards valid names to the events manager`() = runTest {
        val customEventsManager = mockk<ICustomEventsManager>(relaxed = true)
        val manager = managerWithCustomEvents(customEventsManager)

        manager.track("checkout_completed", mapOf("sku" to "abc"), listOf("shop"))

        coVerify(exactly = 1) {
            customEventsManager.track("checkout_completed", mapOf("sku" to "abc"), listOf("shop"))
        }
        manager.close()
    }

    @Test
    fun `setScreenAliases applies aliases and syncs them to the backend`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        coEvery { mockGrovsService.syncScreenAliases(any()) } returns LSResult.Success(true)

        grovsManager.setScreenAliases(mapOf("MainActivity" to "Home"))

        coVerify(exactly = 1) { mockGrovsService.syncScreenAliases(mapOf("MainActivity" to "Home")) }
    }

    @Test
    fun `screen aliases set before authentication are synced once authentication succeeds`() = runTest {
        assertUnauthenticated(grovsManager, context = "before setScreenAliases()")

        grovsManager.setScreenAliases(mapOf("MainActivity" to "Home"))

        coVerify(exactly = 0) { mockGrovsService.syncScreenAliases(any()) }

        stubSuccessfulAuthentication()
        coEvery { mockGrovsService.syncScreenAliases(any()) } returns LSResult.Success(true)

        grovsManager.authenticate()

        coVerify(exactly = 1) { mockGrovsService.syncScreenAliases(mapOf("MainActivity" to "Home")) }
    }

    @Test
    fun `a failed alias sync is retried on the next foreground and cleared once it lands`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        coEvery { mockGrovsService.syncScreenAliases(any()) } returns
            LSResult.Error(java.io.IOException("offline"))

        grovsManager.setScreenAliases(mapOf("MainActivity" to "Home"))
        coVerify(exactly = 1) { mockGrovsService.syncScreenAliases(mapOf("MainActivity" to "Home")) }

        coEvery { mockGrovsService.syncScreenAliases(any()) } returns LSResult.Success(true)
        grovsManager.onAppForegrounded()
        coVerify(exactly = 2) { mockGrovsService.syncScreenAliases(mapOf("MainActivity" to "Home")) }

        // The sync landed, so the pending set is cleared and a later foreground does not resend it.
        grovsManager.onAppForegrounded()
        coVerify(exactly = 2) { mockGrovsService.syncScreenAliases(mapOf("MainActivity" to "Home")) }
    }

    @Test
    fun `an empty alias map is synced so a remote clear reaches the backend`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        coEvery { mockGrovsService.syncScreenAliases(any()) } returns LSResult.Success(true)

        grovsManager.setScreenAliases(emptyMap())

        coVerify(exactly = 1) { mockGrovsService.syncScreenAliases(emptyMap()) }

        // The clear landed, so it is not resent on the next foreground.
        grovsManager.onAppForegrounded()
        coVerify(exactly = 1) { mockGrovsService.syncScreenAliases(emptyMap()) }
    }

    @Test
    fun `an empty alias map set before authentication is still synced afterwards`() = runTest {
        assertUnauthenticated(grovsManager, context = "before setScreenAliases(emptyMap())")

        grovsManager.setScreenAliases(emptyMap())
        coVerify(exactly = 0) { mockGrovsService.syncScreenAliases(any()) }

        stubSuccessfulAuthentication()
        coEvery { mockGrovsService.syncScreenAliases(any()) } returns LSResult.Success(true)

        grovsManager.authenticate()

        coVerify(exactly = 1) { mockGrovsService.syncScreenAliases(emptyMap()) }
    }

    @Test
    fun `a stale alias sync response does not clear a newer pending set`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val staleCallParked = CompletableDeferred<Unit>()
        val releaseStaleCall = CompletableDeferred<Unit>()
        val aliasesSent = mutableListOf<Map<String, String>>()
        var callCount = 0
        coEvery { mockGrovsService.syncScreenAliases(any()) } coAnswers {
            aliasesSent.add(firstArg())
            when (++callCount) {
                1 -> {
                    staleCallParked.complete(Unit)
                    releaseStaleCall.await()
                    LSResult.Success(true)
                }
                2 -> LSResult.Error(java.io.IOException("offline"))
                else -> LSResult.Success(true)
            }
        }

        val staleSync = async { grovsManager.setScreenAliases(mapOf("A" to "Alpha")) }
        runCurrent()
        assertTrue("the first sync should be in flight", staleCallParked.isCompleted)

        // A newer set of aliases replaces the pending set while the first sync is still parked.
        grovsManager.setScreenAliases(mapOf("B" to "Beta"))

        releaseStaleCall.complete(Unit)
        staleSync.await()

        // The stale Success must not have cleared the newer pending set, so it is still resent.
        grovsManager.onAppForegrounded()

        assertEquals(
            "the newer alias set should be resent after the stale response landed",
            listOf(mapOf("A" to "Alpha"), mapOf("B" to "Beta"), mapOf("B" to "Beta")),
            aliasesSent
        )
    }

    @Test
    fun `screen aliases set while disabled sync on enable`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        coEvery { mockGrovsService.syncScreenAliases(any()) } returns LSResult.Success(true)
        grovsContext.settings.sdkEnabled = false

        grovsManager.setScreenAliases(mapOf("MainActivity" to "Home"))
        coVerify(exactly = 0) { mockGrovsService.syncScreenAliases(any()) }

        grovsContext.settings.sdkEnabled = true
        grovsManager.onEnabled()

        coVerify(exactly = 1) { mockGrovsService.syncScreenAliases(mapOf("MainActivity" to "Home")) }
    }

    // ==================== Attribute Update Ordering Tests ====================

    /** Records the identifier each updateAttributes call carries; the first call parks until cancelled. */
    private class AttributeUpdateProbe {
        val identifiersSeen = mutableListOf<String?>()
        val firstCallStarted = CompletableDeferred<Unit>()
        val firstCallCancelled = CompletableDeferred<Unit>()
        var isFirstCall = true
    }

    private fun stubParkingFirstUpdate(): AttributeUpdateProbe {
        val probe = AttributeUpdateProbe()
        coEvery { mockGrovsService.updateAttributes(any(), any(), any()) } coAnswers {
            probe.identifiersSeen.add(firstArg())
            if (probe.isFirstCall) {
                probe.isFirstCall = false
                probe.firstCallStarted.complete(Unit)
                try {
                    // Stands in for the service's own while(true) retry loop, which parks the call
                    // indefinitely while the backend is unreachable.
                    awaitCancellation()
                } catch (e: CancellationException) {
                    probe.firstCallCancelled.complete(Unit)
                    throw e
                }
            }
            LSResult.Success(true)
        }
        return probe
    }

    @Test
    fun `a newer attribute update cancels the one in flight`() = runTest {
        grovsManager.attributesUpdateScope = this
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        val probe = stubParkingFirstUpdate()

        grovsManager.identifier = "stale"
        runCurrent()
        assertTrue(
            "the first update should be in flight before the second setter runs",
            probe.firstCallStarted.isCompleted
        )

        grovsManager.identifier = "fresh"
        runCurrent()

        assertTrue(
            "the in-flight update carrying the stale identifier should have been cancelled",
            probe.firstCallCancelled.isCompleted
        )
    }

    @Test
    fun `the last attribute value written is the one the service ends up with`() = runTest {
        grovsManager.attributesUpdateScope = this
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        val probe = stubParkingFirstUpdate()

        grovsManager.identifier = "stale"
        runCurrent()
        grovsManager.identifier = "fresh"
        runCurrent()

        assertEquals(
            "updateAttributes should have been called with the stale value then the fresh one",
            listOf("stale", "fresh"),
            probe.identifiersSeen
        )
        coVerify(exactly = 1) { mockGrovsService.updateAttributes("fresh", any(), any()) }
    }

    /** Makes the service answer both legs of [GrovsManager.authenticate] with a success. */
    private fun stubSuccessfulAuthentication() {
        every { mockGrovsService.getDeviceFor(any()) } returns
            flowOf(GVRetryResult.Success(GetDeviceResponse(lastSeen = null)))
        every { mockGrovsService.authenticate(any()) } returns flowOf(
            GVRetryResult.Success(
                AuthenticationResponse(
                    grovsId = "grovs-id",
                    uriScheme = "grovs",
                    sdkIdentifier = null,
                    sdkAttributes = null,
                )
            )
        )
    }

    // ==================== Consent Gate Tests ====================

    @Test
    fun `authenticate re-checks consent after the device lookup and never calls authenticate when it was withdrawn`() = runTest {
        // Consent is withdrawn from inside the device-lookup answer itself, so it is false by the
        // time authenticate() re-checks it right after that collect finishes.
        every { mockGrovsService.getDeviceFor(any()) } answers {
            grovsContext.settings.sdkEnabled = false
            flowOf(GVRetryResult.Success(GetDeviceResponse(lastSeen = null)))
        }

        val result = grovsManager.authenticate()

        assertFalse(
            "authenticate() must fail when consent is withdrawn between the device lookup and the authenticate call",
            result
        )
        verify(exactly = 0) { mockGrovsService.authenticate(any()) }
        assertUnauthenticated(
            grovsManager,
            context = "after consent withdrawn between the device lookup and the authenticate call"
        )
    }

    @Test
    fun `logAppLaunchEvents runs to completion even if the authenticate job is cancelled while it is writing`() = runTest {
        stubSuccessfulAuthentication()
        val gate = CompletableDeferred<Unit>()
        val completed = AtomicBoolean(false)
        coEvery { mockEventsManager.logAppLaunchEvents() } coAnswers {
            gate.await()
            completed.set(true)
        }

        val job = launch { grovsManager.authenticate() }
        runCurrent()
        assertFalse("logAppLaunchEvents should still be parked on the gate", completed.get())

        job.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(
            "logAppLaunchEvents must run to completion despite the job being cancelled mid-write",
            completed.get()
        )
    }

    @Test
    fun `the superseded update is fully stopped before the new request goes out`() = runTest {
        grovsManager.attributesUpdateScope = this
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val order = mutableListOf<String>()
        val staleCallStarted = CompletableDeferred<Unit>()
        var isFirstCall = true
        coEvery { mockGrovsService.updateAttributes(any(), any(), any()) } coAnswers {
            if (isFirstCall) {
                isFirstCall = false
                staleCallStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    // Unwinding a real HTTP call is not instant; NonCancellable keeps this window
                    // open so "stopped" and "next request sent" cannot be confused for each other.
                    withContext(NonCancellable) {
                        delay(100)
                        order.add("superseded update stopped")
                    }
                }
            }
            order.add("new request sent")
            LSResult.Success(true)
        }

        grovsManager.identifier = "stale"
        runCurrent()
        assertTrue("the first update should be in flight", staleCallStarted.isCompleted)

        grovsManager.identifier = "fresh"
        advanceUntilIdle()

        assertEquals(
            "the new request must not be issued while the superseded one is still unwinding",
            listOf("superseded update stopped", "new request sent"),
            order
        )
    }

    /**
     * Holds the very first `launch` inside [CoroutineScope.launch] itself - after
     * updateAttributesIfNeeded has read and cancelled the previous job, but before it has stored the
     * new one. That is the exact window a second thread must not be able to slip through.
     */
    private class FirstDispatchGate(
        val arrived: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    ) : CoroutineDispatcher() {
        private val gated = AtomicBoolean(false)
        private var delegate: CoroutineDispatcher = Dispatchers.Default

        fun on(delegate: CoroutineDispatcher): FirstDispatchGate {
            this.delegate = delegate
            return this
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (gated.compareAndSet(false, true)) {
                arrived.countDown()
                release.await()
            }
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun `two setters racing on different threads never leave two updates in flight`() {
        val gate = FirstDispatchGate()
        // A dedicated pool, not Dispatchers.Default: under a full-suite run the shared pool can be
        // saturated, and this test needs its two jobs to actually get to run.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        // Swallow, don't propagate: these jobs are torn down mid-flight below, and an escaping
        // throw would surface as an uncaught exception charged to whichever test runs next.
        val silence = CoroutineExceptionHandler { _, _ -> }
        val scope = CoroutineScope(gate.on(pool.asCoroutineDispatcher()) + SupervisorJob() + silence)
        grovsManager.attributesUpdateScope = scope
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val park = CompletableDeferred<Unit>()
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val entered = AtomicInteger(0)
        val anyCallEntered = CountDownLatch(1)

        coEvery { mockGrovsService.updateAttributes(any(), any(), any()) } coAnswers {
            entered.incrementAndGet()
            val concurrent = inFlight.incrementAndGet()
            peakInFlight.getAndUpdate { maxOf(it, concurrent) }
            anyCallEntered.countDown()
            try {
                park.await()
            } finally {
                inFlight.decrementAndGet()
            }
            LSResult.Success(true)
        }

        try {
            val first = Thread({ grovsManager.identifier = "first" }, "attr-setter-1")
            first.start()
            assertTrue(
                "the first launch should reach the dispatcher",
                gate.arrived.await(10, TimeUnit.SECONDS)
            )

            // The second setter runs while the first is still mid-launch: it has already cancelled
            // the previous job but has not yet stored its own.
            val second = Thread({ grovsManager.identifier = "second" }, "attr-setter-2")
            second.start()
            Thread.sleep(100)

            gate.release.countDown()
            first.join(10_000)
            second.join(10_000)

            assertTrue(
                "at least one update should have reached the service",
                anyCallEntered.await(10, TimeUnit.SECONDS)
            )

            // Deliberately no assertion that *both* jobs ran, and none on which value the surviving
            // call carried. Once the gate opens, the first job is handed to the pool while the
            // second thread is concurrently cancelling it; if the cancel wins that race the first
            // job never reaches the service at all. One entry and two entries are both correct
            // outcomes here, so waiting on a second entry - or on a particular last value - would
            // fail against a correct implementation on a loaded machine. Last-write-wins has its
            // own deterministic single-threaded test above; what this test owns is the pair of
            // properties below, neither of which depends on who won that race.
            assertTrue(
                "two attribute updates must never be in flight at once, " +
                    "peaked at ${peakInFlight.get()} across ${entered.get()} call(s)",
                peakInFlight.get() <= 1
            )

            // The real damage of a lost cancel is an orphan the manager no longer references and so
            // can never stop. close() cancels everything it still knows about; nothing may survive.
            // The wait is generous and the assertion positive: a slow machine makes this slower,
            // never redder. An orphan never drains, however long we wait.
            grovsManager.close()
            val deadline = System.currentTimeMillis() + 10_000
            while (inFlight.get() != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(
                "close() left an update running that the manager no longer had a handle on",
                0,
                inFlight.get()
            )
        } finally {
            // Unpark, then wait for the jobs to actually finish before the pool goes away - a
            // half-torn-down job would otherwise resume onto a dead dispatcher, or into a mock
            // that tearDown has already unmocked.
            park.complete(Unit)
            gate.release.countDown()
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            pool.shutdown()
        }
    }

    @Test
    fun `attributes set while unauthenticated are sent once authentication succeeds`() = runTest {
        grovsManager.attributesUpdateScope = this
        assertUnauthenticated(grovsManager, context = "before setting the identifier")

        grovsManager.identifier = "queued-while-offline"
        runCurrent()
        coVerify(exactly = 0) { mockGrovsService.updateAttributes(any(), any(), any()) }

        stubSuccessfulAuthentication()
        coEvery { mockGrovsService.updateAttributes(any(), any(), any()) } returns LSResult.Success(true)

        val authenticated = grovsManager.authenticate()
        runCurrent()

        assertTrue("authenticate() should have succeeded", authenticated)
        coVerify(exactly = 1) {
            mockGrovsService.updateAttributes("queued-while-offline", any(), any())
        }
    }

    @Test
    fun `attribute changes while disabled are held and sent on enable`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        coEvery { mockGrovsService.updateAttributes(any(), any(), any()) } returns LSResult.Success(true)
        grovsContext.settings.sdkEnabled = false

        grovsManager.identifier = "user-1"
        coVerify(exactly = 0) { mockGrovsService.updateAttributes(any(), any(), any()) }

        grovsContext.settings.sdkEnabled = true
        grovsManager.onEnabled()
        attributesJob(grovsManager)?.join()

        coVerify(exactly = 1) { mockGrovsService.updateAttributes("user-1", any(), any()) }
    }

    /** The attributes update runs on its own scope; join it so the verify below is deterministic. */
    private fun attributesJob(manager: GrovsManager): Job? =
        GrovsManager::class.java.getDeclaredField("attributesUpdateJob").run {
            isAccessible = true
            get(manager) as? Job
        }

    // ==================== Generate Link Tests ====================

    @Test
    fun `GrovsManager generateLink returns LSResult Error when SDK is disabled`() = runTest {
        grovsContext.settings.sdkEnabled = false

        val result = grovsManager.generateLink(
            title = "Test",
            subtitle = null,
            imageURL = null,
            data = null,
            tags = null,
            customRedirects = null,
            showPreviewIos = null,
            showPreviewAndroid = null,
            tracking = null
        )

        assertResultError(
            result,
            context = "after generateLink() with sdkEnabled=false"
        )
    }

    @Test
    fun `GrovsManager generateLink returns LSResult Error with not ready message when unauthenticated`() = runTest {
        assertUnauthenticated(grovsManager, context = "before generateLink() call")

        val result = grovsManager.generateLink(
            title = "Test",
            subtitle = null,
            imageURL = null,
            data = null,
            tags = null,
            customRedirects = null,
            showPreviewIos = null,
            showPreviewAndroid = null,
            tracking = null
        )

        assertResultErrorContains(
            result,
            expectedMessageContains = "not ready",
            context = "after generateLink() with UNAUTHENTICATED state"
        )
    }

    @Test
    fun `GrovsManager generateLink calls service and returns Success when authenticated`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val expectedResponse = GenerateLinkResponse(
            link = "https://test.grovs.io/abc123"
        )

        coEvery {
            mockGrovsService.generateLink(
                title = any(),
                subtitle = any(),
                imageURL = any(),
                data = any(),
                tags = any(),
                customRedirects = any(),
                showPreviewIos = any(),
                showPreviewAndroid = any(),
                copyToClipboardIos = any(),
                copyToClipboardAndroid = any(),
                tracking = any()
            )
        } returns LSResult.Success(expectedResponse)

        val result = grovsManager.generateLink(
            title = "Test Link",
            subtitle = "Subtitle",
            imageURL = "https://example.com/image.png",
            data = mapOf("key" to "value" as Serializable),
            tags = listOf("tag1", "tag2"),
            customRedirects = null,
            showPreviewIos = true,
            showPreviewAndroid = false,
            tracking = null
        )

        val response = assertResultSuccess(
            result,
            context = "after generateLink() with AUTHENTICATED state"
        )
        assertEqualsWithContext(
            "https://test.grovs.io/abc123",
            response.link,
            "link",
            "after generateLink() returns success"
        )

        coVerify {
            mockGrovsService.generateLink(
                title = "Test Link",
                subtitle = "Subtitle",
                imageURL = "https://example.com/image.png",
                data = any(),
                tags = listOf("tag1", "tag2"),
                customRedirects = null,
                showPreviewIos = true,
                showPreviewAndroid = false,
                copyToClipboardIos = null,
                copyToClipboardAndroid = null,
                tracking = null
            )
        }
    }

    // ==================== Link Details Tests ====================

    @Test
    fun `GrovsManager linkDetails returns LSResult Error when SDK is disabled`() = runTest {
        grovsContext.settings.sdkEnabled = false

        val result = grovsManager.linkDetails("/test-path")

        assertResultError(
            result,
            context = "after linkDetails() with sdkEnabled=false"
        )
    }

    @Test
    fun `GrovsManager linkDetails calls service and returns Success when authenticated`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val expectedResponse = LinkDetailsResponse(
            link = mapOf("url" to "https://test.grovs.io/path", "title" to "Test Title")
        )

        coEvery { mockGrovsService.linkDetails(any()) } returns LSResult.Success(expectedResponse)

        val result = grovsManager.linkDetails("/path")

        val response = assertResultSuccess(
            result,
            context = "after linkDetails('/path') with AUTHENTICATED state"
        )
        assertEqualsWithContext(
            "Test Title",
            response.link["title"],
            "link['title']",
            "after linkDetails() returns success"
        )

        coVerify { mockGrovsService.linkDetails("/path") }
    }

    // ==================== Handle Intent Tests ====================

    @Test
    fun `GrovsManager handleIntent returns null when not authenticated`() = runTest {
        val intent = Intent()

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() with UNAUTHENTICATED state"
        )
    }

    @Test
    fun `GrovsManager handleIntent with data URI calls payloadWithLinkFor and returns DeeplinkDetails`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/deep/link")
        }

        val expectedDetails = DeeplinkDetails(
            link = "https://test.grovs.io/deep/link",
            data = mapOf("key" to "value" as Object),
            tracking = null
        )

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(expectedDetails)
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNotNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() with data URI and AUTHENTICATED state"
        )
        assertEqualsWithContext(
            "https://test.grovs.io/deep/link",
            result?.link,
            "link",
            "after handleIntent() with data URI"
        )

        coVerify { mockEventsManager.completeLinkResolution(any(), delayEvents = false) }
        coVerify { mockGrovsService.payloadWithLinkFor(any()) }
    }

    @Test
    fun `GrovsManager handleIntent returns DeeplinkDetails with data and tracking when present`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/promo")
        }

        val expectedDetails = DeeplinkDetails(
            link = "https://test.grovs.io/promo",
            data = mapOf("promo" to "summer2024" as Object),
            tracking = mapOf("campaign" to "email" as Object)
        )

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(expectedDetails)
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNotNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() with promo link"
        )
        assertEqualsWithContext(
            "https://test.grovs.io/promo",
            result?.link,
            "link",
            "after handleIntent() with promo link"
        )
        assertEqualsWithContext(
            "summer2024",
            result?.data?.get("promo"),
            "data['promo']",
            "after handleIntent() with promo data"
        )
        assertEqualsWithContext(
            "email",
            result?.tracking?.get("campaign"),
            "tracking['campaign']",
            "after handleIntent() with tracking data"
        )
    }

    @Test
    fun `GrovsManager handleIntent returns null when service returns error`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/error")
        }

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Error(Exception("Network error"))
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() when service returns error"
        )
    }

    @Test
    fun `GrovsManager handleIntent updates eventsManager linkForFutureActions on success`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/track")
        }

        val expectedDetails = DeeplinkDetails(
            link = "https://test.grovs.io/resolved-link",
            data = mapOf("key" to "value" as Object),
            tracking = null
        )

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(expectedDetails)
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs

        grovsManager.handleIntent(intent, delayEvents = false)

        coVerify { mockEventsManager.completeLinkResolution("https://test.grovs.io/resolved-link", delayEvents = false) }
    }

    // ==================== Payment Events Tests ====================

    @Test
    fun `purchases are dropped while disabled`() = runTest {
        grovsContext.settings.sdkEnabled = false

        grovsManager.logInAppPurchase("""{"productId":"p","purchaseToken":"t"}""")
        grovsManager.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "p")

        coVerify(exactly = 0) { mockEventsManager.logInAppPurchase(any()) }
        coVerify(exactly = 0) { mockEventsManager.logCustomPurchase(any(), any(), any(), any(), any()) }
    }

    // ==================== Edge Cases Tests ====================

    @Test
    fun `GrovsManager identifier property can be set to null after being set`() {
        grovsContext.identifier = "existing-user"
        assertEqualsWithContext(
            "existing-user",
            grovsManager.identifier,
            "identifier",
            "after setting grovsContext.identifier"
        )

        grovsManager.identifier = null

        assertNullWithContext(
            grovsManager.identifier,
            "identifier",
            "after setting grovsManager.identifier=null"
        )
        assertNullWithContext(
            grovsContext.identifier,
            "grovsContext.identifier",
            "after setting grovsManager.identifier=null"
        )
    }

    @Test
    fun `GrovsManager handleIntent returns null when DeeplinkDetails has null link and null data`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent()

        val emptyDetails = DeeplinkDetails(
            link = null,
            data = null,
            tracking = null
        )

        coEvery { mockGrovsService.payloadFor(any()) } returns LSResult.Success(emptyDetails)
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() when DeeplinkDetails has null link and data"
        )
    }

    // ==================== session_id on device payload ====================

    @Test
    fun `handleIntent sends the current session id on the device payload`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        val captured = slot<AppDetails>()
        coEvery { mockGrovsService.payloadFor(capture(captured)) } returns LSResult.Success(DeeplinkDetails(null, null, null))
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs

        grovsManager.handleIntent(Intent(), delayEvents = false)

        assertEqualsWithContext(
            grovsContext.sessionId,
            captured.captured.sessionId,
            "appDetails.sessionId",
            "after handleIntent() without a link"
        )
    }

    @Test
    fun `handleIntent sends the current session id on the device-and-url payload`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        val captured = slot<AppDetails>()
        coEvery { mockGrovsService.payloadWithLinkFor(capture(captured)) } returns LSResult.Success(DeeplinkDetails("https://x.sqd.link/a", null, null))
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs

        grovsManager.handleIntent(Intent().apply { data = Uri.parse("https://x.sqd.link/a") }, delayEvents = false)

        assertEqualsWithContext(
            grovsContext.sessionId,
            captured.captured.sessionId,
            "appDetails.sessionId",
            "after handleIntent() with a link"
        )
    }

    // ==================== Clipboard flow integration ====================

    private val clipboardLink = "https://demo.sqd.link/abc?gd=device123"

    private class ClipboardRig(
        val cache: FakeLocalCache = FakeLocalCache(numberOfOpens = 0),
        val clipboard: FakeClipboard = FakeClipboard(),
        val customEventsManager: ICustomEventsManager = mockk(relaxed = true),
    )

    private fun clipboardManager(rig: ClipboardRig, service: IGrovsService = mockGrovsService): GrovsManager {
        val handler = ClipboardHandler(
            grovsService = service,
            localCache = rig.cache,
            clipboard = rig.clipboard,
            clipboardDomains = emptyList(),
        )
        return GrovsManager(
            context = context,
            application = application,
            grovsContext = grovsContext,
            apiKey = testApiKey,
            grovsService = service,
            eventsManager = mockEventsManager,
            appDetailsHelper = mockAppDetailsHelper,
            customEventsManager = rig.customEventsManager,
            localCache = rig.cache,
            clipboardHandler = handler,
        ).also { it.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED }
    }

    private fun stubEmptyFingerprint() {
        coEvery { mockGrovsService.payloadFor(any()) } returns LSResult.Success(DeeplinkDetails(null, null, null))
        coEvery { mockGrovsService.clipboardStatus() } returns LSResult.Success(true)
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs
        every { mockEventsManager.setEventsHeld(any()) } just Runs
    }

    @Test
    fun `clipboard focus recovery retries without another intent`() = runTest {
        stubEmptyFingerprint()
        val rig = ClipboardRig(clipboard = FakeClipboard(accessGranted = false, text = clipboardLink))
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns
            LSResult.Success(DeeplinkDetails(clipboardLink, null, null))
        val manager = clipboardManager(rig)
        manager.attributionScope = backgroundScope
        try {
            val pending = async { manager.handleIntent(Intent(), delayEvents = true) }
            runCurrent()
            assertFalse(pending.isCompleted)
            assertEquals(0, rig.clipboard.readCount)

            rig.clipboard.accessGranted = true
            advanceTimeBy(250)
            runCurrent()

            assertEquals(clipboardLink, pending.await()?.link)
            assertEquals(2, rig.clipboard.awaitAccessCount)
            assertEquals(1, rig.clipboard.readCount)
            assertEquals(1, rig.clipboard.clearCount)
            coVerify(exactly = 1) { mockGrovsService.payloadWithLinkFor(any()) }
        } finally { manager.close() }
    }

    @Test
    fun `clipboard focus retry cannot cross revoke and regrant`() = runTest {
        stubEmptyFingerprint()
        val rig = ClipboardRig(clipboard = FakeClipboard(accessGranted = false, text = clipboardLink))
        val manager = clipboardManager(rig)
        manager.attributionScope = backgroundScope
        try {
            val pending = async { manager.handleIntent(Intent(), delayEvents = true) }
            runCurrent()
            grovsContext.settings.sdkEnabled = false
            grovsContext.settings.sdkEnabled = true
            rig.clipboard.accessGranted = true
            advanceTimeBy(250)
            runCurrent()

            assertNull(pending.await())
            assertEquals(1, rig.clipboard.awaitAccessCount)
            assertEquals(0, rig.clipboard.readCount)
            assertEquals(0, rig.clipboard.clearCount)
            coVerify(exactly = 0) { mockGrovsService.payloadWithLinkFor(any()) }
        } finally { manager.close() }
    }

    @Test
    fun `clipboard focus retries stop at the attribution deadline`() = runTest {
        stubEmptyFingerprint()
        val rig = ClipboardRig(clipboard = FakeClipboard(accessGranted = false, text = clipboardLink))
        val manager = clipboardManager(rig)
        manager.attributionScope = backgroundScope
        manager.attributionTimeoutMs = 500
        try {
            val pending = async { manager.handleIntent(Intent(), delayEvents = true) }
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(pending.isCompleted)
            assertNull(pending.await())
            assertEquals(0, rig.clipboard.readCount)
            assertTrue(rig.cache.clipboardFlowPending)
            coVerify(exactly = 1) { mockEventsManager.releaseLinkResolution(delayEvents = false) }
            coVerify(exactly = 0) { mockGrovsService.payloadWithLinkFor(any()) }
        } finally { manager.close() }
    }

    @Test
    fun `authentication never arms the events hold on its own`() = runTest {
        // Only a lookup may hold events: a host that never reaches handleIntent must not have
        // every launch's events held for the whole attribution deadline.
        stubSuccessfulAuthentication()
        val manager = clipboardManager(ClipboardRig())
        manager.attributionScope = backgroundScope
        try {
            assertTrue(manager.authenticate())
            coVerify { mockEventsManager.logAppLaunchEvents() }
            verify(exactly = 0) { mockEventsManager.beginLinkResolution() }
        } finally { manager.close() }
    }

    @Test
    fun `install is held until the clipboard flow matches and then carries the clipboard string`() = runTest {
        stubEmptyFingerprint()
        val resolvedLink = "https://demo.sqd.link/resolved"
        val rig = ClipboardRig(clipboard = FakeClipboard(text = clipboardLink))
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = resolvedLink, data = null, tracking = null))
        val manager = clipboardManager(rig)

        val result = manager.handleIntent(Intent(), delayEvents = true)

        // The delivered DeeplinkDetails carries the backend-resolved link...
        assertEqualsWithContext(resolvedLink, result?.link, "link", "after a clipboard match")
        coVerifyOrder {
            mockEventsManager.beginLinkResolution()
            // ...but INSTALL is stamped with the raw clipboard string, verbatim.
            mockEventsManager.completeLinkResolution(clipboardLink, delayEvents = true)
        }
        coVerify { rig.customEventsManager.setLinkForFutureEvents(resolvedLink) }
        assertFalse(rig.cache.clipboardFlowPending)
        manager.close()
    }

    @Test
    fun `install is held while unresolved and released with no link on no match`() = runTest {
        stubEmptyFingerprint()
        val rig = ClipboardRig(clipboard = FakeClipboard(text = clipboardLink))
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(null, null, null))
        val manager = clipboardManager(rig)

        val result = manager.handleIntent(Intent(), delayEvents = true)

        assertNullWithContext(result, "handleIntent result", "after a clipboard no-match")
        coVerifyOrder {
            mockEventsManager.beginLinkResolution()
            mockEventsManager.releaseLinkResolution(delayEvents = true)
        }
        coVerify(exactly = 0) { mockEventsManager.completeLinkResolution(any(), any()) }
        manager.close()
    }

    @Test
    fun `release valve flushes without a link and a late match still patches`() = runTest {
        stubEmptyFingerprint()
        val resolvedLink = "https://demo.sqd.link/resolved"
        val gate = CompletableDeferred<LSResult<Boolean>>()
        coEvery { mockGrovsService.clipboardStatus() } coAnswers { gate.await() }
        val rig = ClipboardRig(clipboard = FakeClipboard(text = clipboardLink))
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = resolvedLink, data = null, tracking = null))
        val manager = clipboardManager(rig)
        manager.attributionTimeoutMs = 1_000
        manager.attributionScope = backgroundScope

        val pending = async { manager.handleIntent(Intent(), delayEvents = true) }
        advanceTimeBy(1_500)
        runCurrent()

        // Valve fired: hold cleared and a link-less flush requested while the flow is still parked.
        coVerify(exactly = 1) { mockEventsManager.releaseLinkResolution(delayEvents = false) }
        coVerify(exactly = 0) { mockEventsManager.completeLinkResolution(any(), any()) }

        gate.complete(LSResult.Success(true))
        val result = pending.await()

        // The delivered DeeplinkDetails carries the backend-resolved link, but the late patch to
        // INSTALL still carries the raw clipboard string, verbatim.
        assertEqualsWithContext(resolvedLink, result?.link, "link", "after a late clipboard match")
        coVerify { mockEventsManager.completeLinkResolution(clipboardLink, delayEvents = true) }
        coVerify { rig.customEventsManager.setLinkForFutureEvents(resolvedLink) }
        manager.close()
    }

    @Test
    fun `a re-entrant flow does not stack a second valve or release the hold`() = runTest {
        stubEmptyFingerprint()
        val resolvedLink = "https://demo.sqd.link/resolved"
        val gate = CompletableDeferred<LSResult<Boolean>>()
        coEvery { mockGrovsService.clipboardStatus() } coAnswers { gate.await() }
        val rig = ClipboardRig(clipboard = FakeClipboard(text = clipboardLink))
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = resolvedLink, data = null, tracking = null))
        val manager = clipboardManager(rig)

        val first = async { manager.handleIntent(Intent(), delayEvents = true) }
        advanceUntilIdle()
        val second = manager.handleIntent(Intent(), delayEvents = true)

        assertNullWithContext(second, "re-entrant handleIntent result", "while the clipboard flow is in flight")
        coVerify(exactly = 0) { mockEventsManager.completeLinkResolution(any(), any()) }

        gate.complete(LSResult.Success(true))
        assertEqualsWithContext(resolvedLink, first.await()?.link, "link", "after the first run completes")
        coVerify(exactly = 1) { mockEventsManager.completeLinkResolution(any(), any()) }
        coVerify { rig.customEventsManager.setLinkForFutureEvents(resolvedLink) }
        manager.close()
    }

    @Test
    fun `a re-entrant call after the valve fires does not re-hold events or arm a second valve`() = runTest {
        stubEmptyFingerprint()
        val resolvedLink = "https://demo.sqd.link/resolved"
        val gate = CompletableDeferred<LSResult<Boolean>>()
        coEvery { mockGrovsService.clipboardStatus() } coAnswers { gate.await() }
        val rig = ClipboardRig(clipboard = FakeClipboard(text = clipboardLink))
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = resolvedLink, data = null, tracking = null))
        val manager = clipboardManager(rig)
        manager.attributionTimeoutMs = 1_000
        manager.attributionScope = backgroundScope

        val first = async { manager.handleIntent(Intent(), delayEvents = true) }
        advanceTimeBy(1_500)
        runCurrent()

        // Valve fired while the first run is still parked on the network call.
        coVerify(exactly = 1) { mockEventsManager.beginLinkResolution() }
        coVerify(exactly = 1) { mockEventsManager.releaseLinkResolution(any()) }

        // A second handleIntent arrives before the first run reaches a terminal outcome: it must not
        // re-hold the already-released events or arm a second valve.
        val second = manager.handleIntent(Intent(), delayEvents = true)

        assertNullWithContext(second, "re-entrant handleIntent result", "after the valve already released the hold")
        coVerify(exactly = 1) { mockEventsManager.beginLinkResolution() }
        coVerify(exactly = 1) { mockEventsManager.releaseLinkResolution(any()) }
        coVerify(exactly = 0) { mockEventsManager.completeLinkResolution(any(), any()) }

        gate.complete(LSResult.Success(true))
        val result = first.await()

        // The first run's late match still patches, without touching the hold a second time.
        assertEqualsWithContext(resolvedLink, result?.link, "link", "after the late clipboard match")
        coVerify { mockEventsManager.completeLinkResolution(clipboardLink, delayEvents = true) }
        coVerify { rig.customEventsManager.setLinkForFutureEvents(resolvedLink) }
        coVerify(exactly = 1) { mockEventsManager.beginLinkResolution() }
        coVerify(exactly = 1) { mockEventsManager.completeLinkResolution(any(), any()) }
        coVerify(exactly = 1) { mockEventsManager.releaseLinkResolution(any()) }
        manager.close()
    }

    @Test
    fun `a throwing release valve is contained and never reaches the host app`() = runTest {
        stubEmptyFingerprint()
        val resolvedLink = "https://demo.sqd.link/resolved"
        // The deadline flush fails while the lookup is still waiting on the network.
        var nullFlushes = 0
        coEvery { mockEventsManager.releaseLinkResolution(any()) } answers {
            nullFlushes++
            throw IllegalStateException("events storage down")
        }
        val gate = CompletableDeferred<LSResult<Boolean>>()
        coEvery { mockGrovsService.clipboardStatus() } coAnswers { gate.await() }
        val rig = ClipboardRig(clipboard = FakeClipboard(text = clipboardLink))
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = resolvedLink, data = null, tracking = null))
        val manager = clipboardManager(rig)
        manager.attributionTimeoutMs = 1_000
        manager.attributionScope = backgroundScope

        val pending = async { manager.handleIntent(Intent(), delayEvents = true) }
        advanceTimeBy(1_500)
        runCurrent()

        // The valve reached the throwing flush, having already released the hold...
        assertEqualsWithContext(1, nullFlushes, "link-less flush count", "after the release valve fired")
        coVerify(exactly = 0) { mockEventsManager.completeLinkResolution(any(), any()) }
        // ...and the throw never escaped: an uncaught one here would cancel this TestScope.
        assertTrue("the valve scope is still active after a throwing valve", isActive)

        // The still-parked flow reaches its terminal outcome unaffected.
        gate.complete(LSResult.Success(true))
        assertEqualsWithContext(resolvedLink, pending.await()?.link, "link", "after a throwing valve")
        coVerify { mockEventsManager.completeLinkResolution(clipboardLink, delayEvents = true) }
        manager.close()
    }

    private val referrerUrl = "https://demo.sqd.link/referrer?gd=referrer123"

    /**
     * Makes `InstallReferrerClient` connect under Robolectric: the client only binds when a
     * `com.android.vending` service resolves the bind intent and the Play Store package reports at
     * least version 80837300, and it reads the referrer over the AIDL interface it gets back.
     */
    private fun servePlayInstallReferrer(referrer: String) {
        val playStore = PackageInfo().apply {
            packageName = PLAY_STORE_PACKAGE
            @Suppress("DEPRECATION")
            versionCode = 80837300
            applicationInfo = ApplicationInfo().apply { packageName = PLAY_STORE_PACKAGE }
        }
        shadowOf(application.packageManager).installPackage(playStore)

        val component = ComponentName(PLAY_STORE_PACKAGE, REFERRER_SERVICE_CLASS)
        shadowOf(application.packageManager).addOrUpdateService(
            ServiceInfo().apply {
                packageName = component.packageName
                name = component.className
                applicationInfo = playStore.applicationInfo
            }
        )
        shadowOf(application).setComponentNameAndServiceForBindService(component, FakeReferrerService(referrer))
    }

    /** Minimal Play Store install-referrer AIDL endpoint. */
    private class FakeReferrerService(private val referrer: String) : IGetInstallReferrerService.Stub() {
        override fun a(request: Bundle): Bundle = Bundle().apply {
            putString("install_referrer", referrer)
            putLong("referrer_click_timestamp_seconds", 0L)
            putLong("install_begin_timestamp_seconds", 0L)
        }
    }

    /** Lets the bound-service callback run: Robolectric posts `onServiceConnected` to the main looper. */
    private fun deliverServiceConnection() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a direct link supersedes a pending install referrer callback`() = runTest {
        servePlayInstallReferrer(referrerUrl)
        val direct = "https://demo.sqd.link/new-direct"
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns
            LSResult.Success(DeeplinkDetails(direct, null, null))
        val manager = clipboardManager(ClipboardRig())
        manager.attributionScope = backgroundScope
        try {
            val old = async { manager.handleIntent(Intent(), delayEvents = true) }
            runCurrent()
            verify(exactly = 1) { mockEventsManager.beginLinkResolution() }
            coVerify(exactly = 0) { mockGrovsService.payloadWithLinkFor(any()) }

            assertEquals(direct, manager.handleIntent(Intent().setData(Uri.parse(direct)), false)?.link)
            deliverServiceConnection()
            assertNull(old.await())
            coVerify(exactly = 0) { mockGrovsService.payloadWithLinkFor(match { it.url == referrerUrl }) }
            coVerify(exactly = 1) { mockEventsManager.completeLinkResolution(direct, false) }
        } finally { manager.close() }
    }

    @Test
    fun `a referrer read by a superseded lookup is still sent by the next lookup`() = runTest {
        servePlayInstallReferrer(referrerUrl)
        val direct = "https://demo.sqd.link/new-direct"
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns
            LSResult.Success(DeeplinkDetails(direct, null, null))
        val manager = clipboardManager(ClipboardRig())
        manager.attributionScope = backgroundScope
        try {
            val old = async { manager.handleIntent(Intent(), delayEvents = true) }
            runCurrent()
            assertEquals(direct, manager.handleIntent(Intent().setData(Uri.parse(direct)), false)?.link)
            deliverServiceConnection()
            assertNull(old.await())
            coVerify(exactly = 0) { mockGrovsService.payloadWithLinkFor(match { it.url == referrerUrl }) }

            val next = async { manager.handleIntent(Intent(), delayEvents = true) }
            runCurrent()
            deliverServiceConnection()
            assertEquals(direct, next.await()?.link)
            coVerify(exactly = 1) { mockGrovsService.payloadWithLinkFor(match { it.url == referrerUrl }) }
        } finally { manager.close() }
    }

    @Test
    fun `an organic Play referrer never contaminates custom event attribution`() = runTest {
        val organicReferrer = "utm_source=google-play&utm_medium=organic"
        val resolved = "https://demo.sqd.link/resolved"
        servePlayInstallReferrer(organicReferrer)
        coEvery { mockGrovsService.payloadWithLinkFor(match { it.url == organicReferrer }) } returns
            LSResult.Success(DeeplinkDetails(null, null, null))
        coEvery { mockGrovsService.payloadWithLinkFor(match { it.url == clipboardLink }) } returns
            LSResult.Success(DeeplinkDetails(resolved, null, null))
        coEvery { mockGrovsService.clipboardStatus() } returns LSResult.Success(true)

        val storage = io.grovs.storage.CustomEventsStorage(context)
        val custom = CustomEventsManager(context, grovsContext, mockGrovsService, storage, startFlushTimer = false)
        val manager = clipboardManager(ClipboardRig(
            clipboard = FakeClipboard(text = clipboardLink), customEventsManager = custom,
        ))
        try {
            manager.track("onboarding_started", null, null)
            val pending = async { manager.handleIntent(Intent(), true) }
            runCurrent()
            deliverServiceConnection()
            assertEquals(resolved, pending.await()?.link)
            assertEquals(resolved, storage.getEvents().single().link)
        } finally { manager.close() }
    }

    @Test
    fun `install referrer url that resolves to a link disarms the clipboard flow`() = runTest {
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns
            LSResult.Success(DeeplinkDetails(link = "https://demo.sqd.link/from-referrer", data = null, tracking = null))
        servePlayInstallReferrer(referrerUrl)
        val rig = ClipboardRig()
        val manager = clipboardManager(rig)

        val pending = async { manager.handleIntent(Intent(), delayEvents = true) }
        runCurrent()
        deliverServiceConnection()
        advanceUntilIdle()
        val result = pending.await()

        assertEqualsWithContext("https://demo.sqd.link/from-referrer", result?.link, "link", "after an install referrer hit")
        coVerify { mockGrovsService.payloadWithLinkFor(match { it.url == referrerUrl }) }
        assertFalse(rig.cache.clipboardFlowPending)
        coVerify(exactly = 0) { mockGrovsService.clipboardStatus() }
        verify(exactly = 1) { mockEventsManager.beginLinkResolution() }
        manager.close()
    }

    @Test
    fun `install referrer url that resolves to no link falls through to the clipboard flow`() = runTest {
        stubEmptyFingerprint()
        val resolvedLink = "https://demo.sqd.link/resolved"
        // The referrer resolves empty; only the clipboard string matches.
        coEvery { mockGrovsService.payloadWithLinkFor(match { it.url == referrerUrl }) } returns
            LSResult.Success(DeeplinkDetails(null, null, null))
        coEvery { mockGrovsService.payloadWithLinkFor(match { it.url == clipboardLink }) } returns
            LSResult.Success(DeeplinkDetails(link = resolvedLink, data = null, tracking = null))
        servePlayInstallReferrer(referrerUrl)
        val rig = ClipboardRig(clipboard = FakeClipboard(text = clipboardLink))
        val manager = clipboardManager(rig)

        val pending = async { manager.handleIntent(Intent(), delayEvents = true) }
        runCurrent()
        deliverServiceConnection()
        advanceUntilIdle()
        val result = pending.await()

        // The referrer really was resolved (not skipped into the plain fingerprint path)...
        coVerify(exactly = 1) { mockGrovsService.payloadWithLinkFor(match { it.url == referrerUrl }) }
        coVerify(exactly = 0) { mockGrovsService.payloadFor(any()) }
        // ...and its empty answer fell through to the clipboard flow, which matched.
        assertEqualsWithContext(resolvedLink, result?.link, "link", "after an empty install referrer resolve")
        coVerify(exactly = 1) { mockGrovsService.clipboardStatus() }
        coVerifyOrder {
            mockEventsManager.beginLinkResolution()
            mockEventsManager.completeLinkResolution(clipboardLink, delayEvents = true)
        }
        assertFalse(rig.cache.clipboardFlowPending)
        manager.close()
    }

    @Test
    fun `fingerprint hit wins and disarms the clipboard flow`() = runTest {
        coEvery { mockGrovsService.payloadFor(any()) } returns LSResult.Success(DeeplinkDetails(link = "https://demo.sqd.link/fp", data = null, tracking = null))
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs
        val rig = ClipboardRig()
        val manager = clipboardManager(rig)

        val result = manager.handleIntent(Intent(), delayEvents = true)

        assertEqualsWithContext("https://demo.sqd.link/fp", result?.link, "link", "after a fingerprint hit")
        assertFalse(rig.cache.clipboardFlowPending)
        coVerify(exactly = 0) { mockGrovsService.clipboardStatus() }
        verify(exactly = 1) { mockEventsManager.beginLinkResolution() }
        manager.close()
    }

    @Test
    fun `direct intent link disarms the clipboard flow`() = runTest {
        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = "https://demo.sqd.link/direct", data = null, tracking = null))
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs
        val rig = ClipboardRig()
        val manager = clipboardManager(rig)

        manager.handleIntent(Intent().apply { data = Uri.parse("https://demo.sqd.link/direct") }, delayEvents = false)

        assertFalse(rig.cache.clipboardFlowPending)
        coVerify(exactly = 0) { mockGrovsService.clipboardStatus() }
        manager.close()
    }

    @Test
    fun `not pending skips the clipboard flow entirely`() = runTest {
        stubEmptyFingerprint()
        val rig = ClipboardRig(cache = FakeLocalCache(numberOfOpens = 5))
        val manager = clipboardManager(rig)

        val result = manager.handleIntent(Intent(), delayEvents = true)

        assertNullWithContext(result, "handleIntent result", "on an existing install with no link")
        coVerify(exactly = 0) { mockGrovsService.clipboardStatus() }
        verify(exactly = 1) { mockEventsManager.beginLinkResolution() }
        manager.close()
    }

    @Test
    fun `fingerprint transport error skips the clipboard flow`() = runTest {
        coEvery { mockGrovsService.payloadFor(any()) } returns LSResult.Error(java.io.IOException("down"))
        coEvery { mockEventsManager.completeLinkResolution(any(), any()) } just Runs
        val rig = ClipboardRig()
        val manager = clipboardManager(rig)

        manager.handleIntent(Intent(), delayEvents = true)

        assertTrue(rig.cache.clipboardFlowPending)
        coVerify(exactly = 0) { mockGrovsService.clipboardStatus() }
        manager.close()
    }
}
