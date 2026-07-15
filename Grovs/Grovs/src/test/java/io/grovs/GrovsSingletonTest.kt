package io.grovs

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.TestAssertions.assertFalseWithContext
import io.grovs.TestAssertions.assertCallbackInvokedWithLink
import io.grovs.TestAssertions.assertCallbackInvoked
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.TestActivity
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.handlers.VisibleFragmentResolver
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LogLevel
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
import io.grovs.utils.LSResult
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Core unit tests for Grovs singleton class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GrovsSingletonTest {

    private lateinit var application: Application
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        Dispatchers.setMain(testDispatcher)

        application = RuntimeEnvironment.getApplication()
        context = application.applicationContext

        E2ETestUtils.resetGrovsSingleton()

        DebugLogger.instance.logLevel = LogLevel.INFO
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        E2ETestUtils.resetGrovsSingleton()
    }

    // ==================== Configuration Tests ====================

    @Test
    fun `Grovs configure initializes grovsManager when given valid API key`() {
        val apiKey = "test-api-key-123"

        Grovs.configure(application, apiKey, useTestEnvironment = false)

        assertNotNullWithContext(
            currentManager(),
            "manager",
            "after configure() with apiKey='$apiKey'"
        )
    }

    @Test
    fun `Grovs configure sets useTestEnvironment flag in grovsContext settings`() {
        val apiKey = "test-api-key"

        Grovs.configure(application, apiKey, useTestEnvironment = true)

        assertTrueWithContext(
            currentGrovsContext().settings.useTestEnvironment,
            "useTestEnvironment",
            "after configure() with useTestEnvironment=true"
        )
    }

    @Test
    fun `Grovs configure can be called multiple times with different settings`() {
        Grovs.configure(application, "first-key", useTestEnvironment = false)
        Grovs.configure(application, "second-key", useTestEnvironment = true)

        assertTrueWithContext(
            currentGrovsContext().settings.useTestEnvironment,
            "useTestEnvironment",
            "after second configure() call with useTestEnvironment=true"
        )
    }

    @Test
    fun `reconfigure closes the previous manager`() {
        val oldManager = mockk<GrovsManager>(relaxed = true)
        injectMockGrovsManagerDirectly(oldManager)

        Grovs.configure(application, "replacement-key", useTestEnvironment = true)

        verify(exactly = 1) { oldManager.close() }
    }

    @Test
    fun `reconfigure keeps one application lifecycle callback registration`() {
        Grovs.configure(application, "first-key", useTestEnvironment = true)
        Grovs.configure(application, "second-key", useTestEnvironment = true)

        val controller = Robolectric.buildActivity(TestActivity::class.java).create().start()
        try {
            assertEquals(1, getLifecycleStartedCount())
        } finally {
            controller.stop().destroy()
        }
    }

    // ==================== SDK Enable/Disable Tests ====================

    @Test
    fun `Grovs setSDK enables SDK when enabled parameter is true`() {
        Grovs.configure(application, "test-api-key", useTestEnvironment = false)

        Grovs.setSDK(enabled = true)

        assertTrueWithContext(
            currentGrovsContext().settings.sdkEnabled,
            "sdkEnabled",
            "after setSDK(enabled=true)"
        )
    }

    @Test
    fun `Grovs setSDK disables SDK when enabled parameter is false`() {
        Grovs.configure(application, "test-api-key", useTestEnvironment = false)

        Grovs.setSDK(enabled = false)

        assertFalseWithContext(
            currentGrovsContext().settings.sdkEnabled,
            "sdkEnabled",
            "after setSDK(enabled=false)"
        )
    }

    // ==================== Properties Tests ====================

    @Test
    fun `Grovs identifier property can be set after configuration`() {
        Grovs.configure(application, "test-api-key", useTestEnvironment = false)

        Grovs.identifier = "user-123"

        assertEqualsWithContext(
            "user-123",
            Grovs.identifier,
            "identifier",
            "after setting identifier='user-123'"
        )
    }

    @Test
    fun `Grovs pushToken property can be set after configuration`() {
        Grovs.configure(application, "test-api-key", useTestEnvironment = false)

        Grovs.pushToken = "fcm-token-abc123"

        assertEqualsWithContext(
            "fcm-token-abc123",
            Grovs.pushToken,
            "pushToken",
            "after setting pushToken='fcm-token-abc123'"
        )
    }

    @Test
    fun `Grovs attributes property can be set after configuration`() {
        Grovs.configure(application, "test-api-key", useTestEnvironment = false)
        val attrs = mapOf<String, Any>("name" to "John", "age" to 30)

        Grovs.attributes = attrs

        assertEqualsWithContext(
            attrs,
            Grovs.attributes,
            "attributes",
            "after setting attributes with name='John', age=30"
        )
    }

    // ==================== Lifecycle Tests ====================

    @Test
    fun `Grovs lifecycle methods are safe to call before SDK configuration`() {
        // Arrange - SDK is not configured
        val intent = Intent()

        // Act & Assert - these should not throw
        try {
            Grovs.onStart(null)
        } catch (e: Exception) {
            fail("onStart should not throw when SDK not configured: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            Grovs.onNewIntent(intent, null)
        } catch (e: Exception) {
            fail("onNewIntent should not throw when SDK not configured: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Verify SDK state is still valid (not corrupted)
        assertNullWithContext(
            currentManager(),
            "manager",
            "after calling lifecycle methods before configure - SDK state should remain uncorrupted"
        )
    }

    // ==================== Deeplink Listener Tests ====================

    @Test
    fun `Grovs setOnDeeplinkReceivedListener stores listener in singleton`() {
        var receivedDetails: DeeplinkDetails? = null
        val listener = GrovsDeeplinkListener { details ->
            receivedDetails = details
        }

        Grovs.setOnDeeplinkReceivedListener(null, listener)

        val grovsInstance = getGrovsInstance()
        val listenerField = Grovs::class.java.getDeclaredField("deeplinkListener")
        listenerField.isAccessible = true
        assertNotNullWithContext(
            listenerField.get(grovsInstance),
            "deeplinkListener",
            "after setOnDeeplinkReceivedListener() with non-null listener"
        )
    }

    // ==================== Link Generation Tests ====================

    @Test
    fun `Grovs generateLink returns LINK_GENERATION_ERROR when SDK not configured`() {
        var receivedLink: String? = null
        var receivedException: GrovsException? = null

        Grovs.generateLink(
            title = "Test",
            listener = { link, error ->
                receivedLink = link
                receivedException = error
            }
        )

        assertNullWithContext(
            receivedLink,
            "link",
            "after generateLink() without SDK configured"
        )
        assertNotNullWithContext(
            receivedException,
            "error",
            "after generateLink() without SDK configured"
        )
        assertEqualsWithContext(
            GrovsErrorCode.LINK_GENERATION_ERROR,
            receivedException?.errorCode,
            "errorCode",
            "after generateLink() without SDK configured"
        )
    }

    @Test
    fun `Grovs generateLink invokes callback with link URL when authenticated`() {
        val mockManager = mockk<GrovsManager>(relaxed = true)
        every { mockManager.authenticationState } returns GrovsManager.AuthenticationState.AUTHENTICATED
        coEvery { mockManager.generateLink(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            LSResult.Success(GenerateLinkResponse("https://test.grovs.io/generated-link"))

        injectMockGrovsManagerDirectly(mockManager)

        val latch = CountDownLatch(1)
        var callbackInvoked = false
        var receivedLink: String? = null
        var receivedError: Exception? = null

        Grovs.generateLink(
            title = "Test Title",
            subtitle = "Test Subtitle",
            imageURL = "https://example.com/image.png",
            data = mapOf("key" to "value"),
            tags = listOf("tag1", "tag2"),
            listener = { link, error ->
                callbackInvoked = true
                receivedLink = link
                receivedError = error
                latch.countDown()
            }
        )

        val startTime = System.currentTimeMillis()
        val timeoutMs = 5_000L
        while (latch.count > 0 && System.currentTimeMillis() - startTime < timeoutMs) {
            testDispatcher.scheduler.advanceUntilIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertCallbackInvoked(
            callbackInvoked,
            timeoutMs,
            "after generateLink() with authenticated mockManager"
        )
        assertCallbackInvokedWithLink(
            link = receivedLink,
            error = receivedError,
            expectedLink = "https://test.grovs.io/generated-link",
            context = "after generateLink() with authenticated mockManager returning success"
        )
    }

    @Test
    fun `unconfigured unread callback remains asynchronous on main`() {
        val callbackReceived = CountDownLatch(1)
        var callbackInvoked = false
        var callbackLooper: Looper? = null
        var unreadCount: Int? = -1

        Grovs.numberOfUnreadMessages(onResult = { count ->
            callbackInvoked = true
            callbackLooper = Looper.myLooper()
            unreadCount = count
            callbackReceived.countDown()
        })

        assertFalse("Unread callback must not run inline", callbackInvoked)

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (callbackReceived.count > 0 && System.nanoTime() < deadlineNanos) {
            testDispatcher.scheduler.advanceUntilIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertEquals("Unread callback was not delivered", 0L, callbackReceived.count)
        assertNull(unreadCount)
        assertSame(Looper.getMainLooper(), callbackLooper)
    }

    // ==================== Screen Tracking Tests ====================

    @Test
    fun `automatic hierarchy resolution runs on the main looper`() {
        injectMockGrovsManagerDirectly(mockk(relaxed = true))
        val controller = Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        val resolverCalled = CountDownLatch(1)
        var resolverLooper: Looper? = null
        mockkObject(VisibleFragmentResolver)
        every { VisibleFragmentResolver.findVisibleLeaf(any()) } answers {
            resolverLooper = Looper.myLooper()
            resolverCalled.countDown()
            null
        }

        try {
            E2ETestUtils.dispatchActivityResumed(controller.get())
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(
                "VisibleFragmentResolver was not called within the timeout",
                resolverCalled.await(2, TimeUnit.SECONDS),
            )
            assertSame(Looper.getMainLooper(), resolverLooper)
            verify(exactly = 1) { VisibleFragmentResolver.findVisibleLeaf(any()) }
        } finally {
            controller.pause().stop().destroy()
            unmockkObject(VisibleFragmentResolver)
        }
    }

    @Test
    fun `track reaches the manager while authentication is pending`() {
        val mockManager = mockk<GrovsManager>(relaxed = true)
        val pendingAuthentication = Job()
        injectMockGrovsManagerDirectly(mockManager, pendingAuthentication)

        try {
            Grovs.track("offline_event")

            coVerify(timeout = 1_000, exactly = 1) {
                mockManager.track("offline_event", null, null)
            }
            assertFalse(pendingAuthentication.isCompleted)
        } finally {
            pendingAuthentication.cancel()
        }
    }

    @Test
    fun `manual screen tracking reaches the manager while authentication is pending`() {
        val mockManager = mockk<GrovsManager>(relaxed = true)
        val pendingAuthentication = Job()
        injectMockGrovsManagerDirectly(mockManager, pendingAuthentication)

        try {
            Grovs.trackScreenView("Checkout")

            coVerify(timeout = 1_000, exactly = 1) {
                mockManager.trackScreenView("Checkout", null)
            }
            assertFalse(pendingAuthentication.isCompleted)
        } finally {
            pendingAuthentication.cancel()
        }
    }

    @Test
    fun `automatic screen tracking reaches the manager while authentication is pending`() {
        val mockManager = mockk<GrovsManager>(relaxed = true)
        val pendingAuthentication = Job()
        val controller = Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        injectMockGrovsManagerDirectly(mockManager, pendingAuthentication)

        try {
            E2ETestUtils.dispatchActivityResumed(controller.get())
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(timeout = 1_500, exactly = 1) {
                mockManager.autoTrackScreen("TestActivity", any())
            }
            assertFalse(pendingAuthentication.isCompleted)
        } finally {
            pendingAuthentication.cancel()
            controller.pause().stop().destroy()
        }
    }

    // Regression test: an orphaned posted job from a rapid pause->resume must not fire a
    // second autoTrackScreen. Uses a mocked GrovsManager rather than the real ScreenTracker,
    // whose own dedup window would otherwise mask the bug.
    @Test
    fun `rapid pause then resume of the same activity emits only one screen view`() {
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)

        val mockManager = mockk<GrovsManager>(relaxed = true)
        injectMockGrovsManagerDirectly(mockManager)

        val controller = Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        try {
            // Pause -> resume the same Activity before the first posted resolution job runs.
            controller.pause().resume()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(timeout = 3_000, exactly = 1) {
                mockManager.autoTrackScreen("TestActivity", any())
            }

            // Keep observing so a late orphaned job cannot escape the final exact-count verification.
            Thread.sleep(300L)
            coVerify(exactly = 1) { mockManager.autoTrackScreen("TestActivity", any()) }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `pausing before screen resolution prevents emission`() {
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)

        currentManager()?.close()

        val mockManager = mockk<GrovsManager>(relaxed = true)
        injectMockGrovsManager(mockManager)

        val controller = Robolectric.buildActivity(TestActivity::class.java)
            .create().start().resume().pause()
        try {
            testDispatcher.scheduler.advanceUntilIdle()
            coVerify(exactly = 0) { mockManager.autoTrackScreen(any(), any()) }
        } finally {
            controller.stop().destroy()
        }
    }

    // ==================== Purchase Tests ====================

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `Grovs logInAppPurchase does not throw when SDK not configured`() {
    // PURCHASE_EVENT_DISABLED:     val originalJson = """{"productId":"premium","purchaseToken":"abc123"}"""
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Should complete without exception
    // PURCHASE_EVENT_DISABLED:     Grovs.logInAppPurchase(originalJson)
    // PURCHASE_EVENT_DISABLED: }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `Grovs logCustomPurchase does not throw when SDK not configured`() {
    // PURCHASE_EVENT_DISABLED:     // Should complete without exception
    // PURCHASE_EVENT_DISABLED:     Grovs.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:         type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         priceInCents = 999,
    // PURCHASE_EVENT_DISABLED:         currency = "USD",
    // PURCHASE_EVENT_DISABLED:         productId = "premium"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED: }

    // ==================== Helper Methods ====================

    private fun getGrovsInstance(): Grovs {
        val instanceField = Grovs::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        val companionField = Grovs::class.java.getDeclaredField("Companion")
        companionField.isAccessible = true
        val companion = companionField.get(null)
        return instanceField.get(companion) as Grovs
    }

    private fun getLifecycleStartedCount(): Int {
        val grovsInstance = getGrovsInstance()
        val observerField = Grovs::class.java.getDeclaredField("applicationLifecycleObserver")
        observerField.isAccessible = true
        val observer = observerField.get(grovsInstance)

        val numStartedField = observer.javaClass.getDeclaredField("numStarted")
        numStartedField.isAccessible = true
        return numStartedField.getInt(observer)
    }

    private fun grovsField(name: String) =
        Grovs::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun currentGrovsContext(): GrovsContext =
        grovsField("grovsContext").get(getGrovsInstance()) as GrovsContext

    private fun currentManager(): GrovsManager? =
        grovsField("grovsManager").get(getGrovsInstance()) as? GrovsManager

    private fun injectMockGrovsManager(mockManager: GrovsManager) {
        injectMockGrovsManagerDirectly(mockManager)
    }

    /**
     * Injects a mock GrovsManager directly without calling configure().
     * This avoids starting the real authentication job that can cause test issues.
     */
    private fun injectMockGrovsManagerDirectly(
        mockManager: GrovsManager,
        authenticationJob: Job? = null,
    ) {
        try {
            val instance = getGrovsInstance()
            (grovsField("authenticationJob").get(instance) as? Job)?.cancel()
            grovsField("grovsManager").set(instance, mockManager)
            grovsField("authenticationJob").set(instance, authenticationJob)
        } catch (e: Exception) {
            throw RuntimeException("Failed to inject mock GrovsManager", e)
        }
    }
}
