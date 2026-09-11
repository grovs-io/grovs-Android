package io.grovs.handlers

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.FakeLocalCache
import io.grovs.e2e.E2ETestUtils
import io.grovs.model.CustomEvent
import io.grovs.model.EventType
import io.grovs.model.events.PaymentEvent
import io.grovs.model.events.PaymentEventType
import io.grovs.service.GatedExecutor
import io.grovs.service.IGrovsService
import io.grovs.service.useConsentController
import io.grovs.storage.ICustomEventsStorage
import io.grovs.storage.IEventsStorage
import io.grovs.storage.EventsStorage
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** Public SDK lifecycle and collection boundaries, with controlled cancellation and real engagement storage. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConsentLifecycleTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun purchaseWaitingOnAuthenticationCannotReviveUnderNewConsent() = runTest {
        E2ETestUtils.resetGrovsSingleton()
        val cleanup = GatedExecutor()
        val context = GrovsContext(StandardTestDispatcher(testScheduler))
        context.useConsentController(ConsentController(cleanupExecutor = cleanup))
        E2ETestUtils.installGrovsContext(context)
        val service = mockk<IGrovsService>(relaxed = true)
        val storage = mockk<IEventsStorage>(relaxed = true)
        val queued = mutableListOf<PaymentEvent>()
        coEvery { storage.addPaymentEvent(any()) } answers { queued.add(firstArg()); Unit }
        val events = EventsManager(app, context, "key", service, storage, FakeLocalCache(numberOfOpens = 1))
        val manager = GrovsManager(app, app, context, "key", service, events,
            mockk<IAppDetailsHelper>(relaxed = true), mockk<ICustomEventsManager>(relaxed = true),
            localCache = FakeLocalCache(numberOfOpens = 1))
        manager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        E2ETestUtils.injectGrovsManager(manager)
        val authentication = Job()
        Grovs::class.java.getDeclaredField("authenticationJob").apply {
            isAccessible = true; set(E2ETestUtils.getGrovsInstance(), authentication)
        }
        try {
            Grovs.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "revoked_purchase")
            runCurrent()
            assertEquals(1, context.consent.registrationCount())
            assertTrue(queued.isEmpty())
            Grovs.setSDK(false)
            Grovs.setSDK(true)
            authentication.complete()
            runCurrent()
            assertTrue("Purchase admitted under revoked consent must not be stored after re-enable", queued.isEmpty())
        } finally {
            authentication.complete()
            cleanup.open()
            runCurrent()
            manager.close()
            E2ETestUtils.resetGrovsSingleton()
        }
    }

    @Test
    fun automaticScreenQueuedBeforeRevocationCannotUseNewConsent() = runTest {
        E2ETestUtils.resetGrovsSingleton()
        val context = GrovsContext(StandardTestDispatcher(testScheduler))
        E2ETestUtils.installGrovsContext(context)
        val service = mockk<IGrovsService>(relaxed = true)
        val storage = mockk<ICustomEventsStorage>(relaxed = true)
        val queued = mutableListOf<CustomEvent>()
        coEvery { storage.addEvent(any()) } answers { queued.add(firstArg()); Unit }
        val custom = CustomEventsManager(app, context, service, storage, startFlushTimer = false)
        val manager = GrovsManager(app, app, context, "key", service,
            mockk<IEventsManager>(relaxed = true), mockk<IAppDetailsHelper>(relaxed = true), custom,
            localCache = FakeLocalCache(numberOfOpens = 1))
        manager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        E2ETestUtils.injectGrovsManager(manager)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume()
        val observer = Grovs::class.java.getDeclaredField("applicationLifecycleObserver").run {
            isAccessible = true
            get(E2ETestUtils.getGrovsInstance()) as Application.ActivityLifecycleCallbacks
        }
        try {
            observer.onActivityResumed(activity.get())
            assertTrue(queued.isEmpty())
            Grovs.setSDK(false)
            Grovs.setSDK(true)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertTrue("Old queued auto-screen work must not record under the new grant: $queued", queued.isEmpty())
            observer.onActivityResumed(activity.get())
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("A new permitted resume must still track", 1, queued.size)
        } finally {
            activity.pause().stop().destroy()
            manager.close()
            E2ETestUtils.resetGrovsSingleton()
        }
    }

    @Test
    fun engagementExcludesTheDisabledInterval() = runTest { verifyEngagement(resumeInForeground = true) }

    @Test
    fun enablingInBackgroundWaitsForForegroundToResumeEngagement() = runTest { verifyEngagement(resumeInForeground = false) }

    private suspend fun TestScope.verifyEngagement(resumeInForeground: Boolean) {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(app)
        val context = GrovsContext(StandardTestDispatcher(testScheduler))
        context.useConsentController(ConsentController(cleanupExecutor = Executor { it.run() }))
        // After the swap, so it belongs to the configuration the managers below will own.
        context.markAuthenticated("device", context.consent.currentConfiguration)
        E2ETestUtils.installGrovsContext(context)
        val service = mockk<IGrovsService>(relaxed = true)
        coEvery { service.addEvents(any()) } returns LSResult.Error(Exception("retain for inspection"))
        val storage = EventsStorage(app)
        val events = EventsManager(app, context, "key", service, storage, FakeLocalCache(numberOfOpens = 1))
        val resumed = CompletableDeferred<Unit>()
        val custom = mockk<ICustomEventsManager>(relaxed = true)
        coEvery { custom.flush() } answers { resumed.complete(Unit); Unit }
        val manager = GrovsManager(app, app, context, "key", service, events,
            mockk<IAppDetailsHelper>(relaxed = true), custom,
            localCache = FakeLocalCache(numberOfOpens = 1))
        manager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        E2ETestUtils.injectGrovsManager(manager)
        var elapsed = 0L
        val start = System.currentTimeMillis()
        mockkObject(InstantCompat.Companion)
        every { InstantCompat.now() } answers { InstantCompat(start + elapsed) }
        try {
            context.isForeground = true
            events.logAppLaunchEvents()
            elapsed = 10_000
            Grovs.setSDK(false)
            if (!resumeInForeground) manager.onAppBackgrounded()
            elapsed = 110_000
            Grovs.setSDK(true)
            if (resumeInForeground) {
                E2ETestUtils.waitForCondition(description = "engagement resumed under the new grant") {
                    testScheduler.runCurrent()
                    runBlocking { storage.getEvents() }.any {
                        it.event == EventType.TIME_SPENT && it.engagementTime == null && it.createdAt.toEpochMilli() == start + elapsed
                    }
                }
                elapsed = 120_000
            } else {
                E2ETestUtils.waitForCondition(description = "background enable finished") {
                    testScheduler.runCurrent()
                    resumed.isCompleted && context.consent.outstandingCommitCount() == 0
                }
                assertTrue(storage.getEvents().none { it.event == EventType.TIME_SPENT && it.engagementTime == null })
                elapsed = 120_000
                manager.onAppForegrounded()
                elapsed = 130_000
            }
            manager.onAppBackgrounded()
            E2ETestUtils.waitForCondition(description = "both enabled engagement segments closed") {
                testScheduler.runCurrent()
                runBlocking { storage.getEvents() }.count { it.event == EventType.TIME_SPENT && it.engagementTime != null } == 2
            }
            val segments = storage.getEvents().filter { it.event == EventType.TIME_SPENT && it.engagementTime != null }
            assertEquals("10s before disable + 10s after enable, excluding 100s disabled", 20, segments.sumOf { it.engagementTime!! })
        } finally {
            unmockkObject(InstantCompat.Companion)
            manager.close()
            E2ETestUtils.resetGrovsSingleton()
        }
    }

}
