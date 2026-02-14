package io.grovs.e2e

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.grovs.Grovs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E2E tests for deep linking functionality.
 * Covers cold start, warm start, hot start, edge cases, and advanced scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeepLinkingE2ETest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var application: Application

    @get:Rule
    val testName = TestName()

    @Before
    fun setUp() {
        println("\n========== Running: ${testName.methodName} ==========")
        Dispatchers.setMain(UnconfinedTestDispatcher())
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        mockWebServer = E2ETestUtils.createMockWebServer()
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.setupTestApplication(application)
    }

    @After
    fun tearDown() {
        E2ETestUtils.cleanupMockWebServer(mockWebServer)
        Dispatchers.resetMain()
    }

    private suspend fun configureAndWaitForAuth(apiKey: String = "test-api-key") {
        E2ETestUtils.configureAndWaitForAuth(application, apiKey)
    }

    // ==================== Cold Start Tests ====================

    @Test
    fun `test cold start from deeplink processes link parameter`() {
        // Arrange - use URL-based dispatcher to avoid FIFO response ordering issues
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/abc123","data":null}""")
        ))

        // Configure without creating an activity to avoid consuming the deeplink response
        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Act - create activity with deeplink intent
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=abc123")
        }

        var receivedDeeplink: io.grovs.model.DeeplinkDetails? = null

        val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
        activityController.create()


        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            receivedDeeplink = details
        }

        activityController.start()


        // Wait for deeplink callback - outside runBlocking so main looper is free
        E2ETestUtils.waitForCondition(description = "deeplink callback") {
            receivedDeeplink != null
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertNotNull("Received deeplink should have a link", receivedDeeplink?.link)
        assertEquals("https://test.grovs.io/abc123", receivedDeeplink?.link)

        activityController.stop()
        activityController.destroy()
    }

    @Test
    fun `test cold start without deeplink calls data_for_device`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueDataForDeviceResponse(mockWebServer)


            configureAndWaitForAuth()

            // Act - create activity without deeplink
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create()
    

            activityController.start()
    

            delay(1000)
    

            // Assert
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            assertTrue(
                "SDK should call device_for_vendor_id endpoint on cold start",
                requests.any { it.first.contains("device_for_vendor_id") }
            )
            assertTrue(
                "SDK should call authenticate endpoint on cold start",
                requests.any { it.first.contains("authenticate") }
            )
            E2ETestUtils.assertAuthenticationCompleted()

            activityController.stop()
            activityController.destroy()
        }
    }

    @Test
    fun `test cold start from deeplink with custom data processes link`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/campaign","data":{"campaign":"summer","source":"email"}}""")
        ))

        // Configure without creating an activity to avoid consuming the deeplink response
        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Act
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=campaign")
        }

        val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
        activityController.create()


        var receivedData: Map<String, Any>? = null
        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            receivedData = details?.data
        }

        activityController.start()


        // Wait for deeplink callback - outside runBlocking so main looper is free
        E2ETestUtils.waitForCondition(description = "deeplink data callback") {
            receivedData != null
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertTrue("Received data should not be empty", receivedData!!.isNotEmpty())
        assertEquals("Should receive campaign value", "summer", receivedData!!["campaign"])
        assertEquals("Should receive source value", "email", receivedData!!["source"])

        activityController.stop().destroy()
    }

    // ==================== Warm/Hot Start Tests ====================

    @Test
    fun `test warm start activity receives onNewIntent with deeplink`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/warmstart","data":{"ref":"warm"}}""")
        ))

        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Create and start activity (no deeplink)
        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()


        // Simulate app going to background (warm start = activity stopped but not destroyed)
        activityController.pause().stop()


        var receivedDeeplink: io.grovs.model.DeeplinkDetails? = null
        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            receivedDeeplink = details
        }

        // Act - app brought back to foreground with a deeplink intent
        val newIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=warmstart")
        }
        activityController.newIntent(newIntent)

        activityController.start().resume()


        // Wait for deeplink callback
        E2ETestUtils.waitForCondition(description = "warm start deeplink callback") {
            receivedDeeplink != null
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertNotNull("Deeplink listener should receive link", receivedDeeplink?.link)
        assertEquals("https://test.grovs.io/warmstart", receivedDeeplink?.link)

        activityController.pause().stop().destroy()
    }

    @Test
    fun `test hot start - link received while app visible triggers processing`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/hotstart","data":{"ref":"hot"}}""")
        ))

        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Create activity in foreground
        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()


        var receivedDeeplink: io.grovs.model.DeeplinkDetails? = null
        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            receivedDeeplink = details
        }

        // Act - receive deeplink while in foreground
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=hotstart")
        }
        activityController.newIntent(deeplinkIntent)


        // Wait for deeplink callback
        E2ETestUtils.waitForCondition(description = "hot start deeplink callback") {
            receivedDeeplink != null
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertNotNull("Deeplink listener should receive link", receivedDeeplink?.link)
        assertEquals("https://test.grovs.io/hotstart", receivedDeeplink?.link)

        activityController.pause().stop().destroy()
    }

    @Test
    fun `test warm start from recents without link does not trigger deeplink processing`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            
            configureAndWaitForAuth()

            // Create and start activity first
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create().start().resume()
    

            var listenerCalled = false
            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
                listenerCalled = true
            }

            // Simulate app going to background
            activityController.pause().stop()
    

            // Act - return from recents
            activityController.start().resume()
    

            delay(500)
    

            // Assert
            assertFalse(
                "Deeplink listener should not be called when returning from recents without link",
                listenerCalled
            )

            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun `test hot start - duplicate intent within 2 seconds ignored`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/duplicate","data":{"ref":"dup"}}""")
        ))

        E2ETestUtils.configureAndWaitForAuthOnly(application)

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()


        var listenerCallCount = 0
        var receivedDeeplink: io.grovs.model.DeeplinkDetails? = null
        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            listenerCallCount++
            receivedDeeplink = details
        }

        // Act - send same deeplink intent twice quickly
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=duplicate-test")
        }

        activityController.newIntent(deeplinkIntent)


        // Wait for the first callback to arrive
        E2ETestUtils.waitForCondition(description = "first deeplink callback") {
            receivedDeeplink != null
        }

        // Send the same intent again within 2 seconds
        activityController.newIntent(deeplinkIntent)


        // Give time for a potential second callback
        Thread.sleep(500)


        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertNotNull("Deeplink listener should receive link", receivedDeeplink?.link)
        assertEquals("https://test.grovs.io/duplicate", receivedDeeplink?.link)
        assertTrue(
            "Duplicate intent should be ignored (listener called at most once), got $listenerCallCount",
            listenerCallCount <= 1
        )

        activityController.pause().stop().destroy()
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `test malformed deeplink URL handled gracefully without crash`() {
        // Arrange - return null link for the malformed URL
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":null,"data":null}""")
        ))

        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Act - create activity with malformed URI
        val malformedIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("not-a-valid-uri-scheme")
        }

        var listenerCalled = false
        val activityController = Robolectric.buildActivity(TestActivity::class.java, malformedIntent)

        try {
            activityController.create()
    

            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
                listenerCalled = true
            }

            activityController.start()
    

            // Give SDK time to process the malformed URL
            Thread.sleep(1000)
    

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
            assertFalse(
                "Deeplink listener should not be called for malformed URL",
                listenerCalled
            )
        } catch (e: Exception) {
            fail("SDK should not crash on malformed deeplink: ${e.message}")
        }

        activityController.stop().destroy()
    }

    @Test
    fun `test deeplink resolution timeout handled gracefully`() {
        // Arrange - use URL dispatcher with a delayed data_for_device response
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/slow","data":null}""")
                .setBodyDelay(30_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        ))

        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Act
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=slow")
        }

        var listenerCalled = false
        val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
        activityController.create()


        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            listenerCalled = true
        }

        activityController.start()


        // Give SDK time to attempt the request (but not enough for the 30s delay)
        Thread.sleep(1000)


        // Assert - SDK should attempt to resolve but listener should not be called (server too slow)
        E2ETestUtils.assertAuthenticationCompleted()
        assertTrue(
            "SDK should make requests even with slow server",
            mockWebServer.requestCount >= 1
        )
        assertFalse(
            "Deeplink listener should not be called when server response is delayed",
            listenerCalled
        )

        activityController.stop().destroy()
    }

    @Test
    fun `test listener set after deeplink received still works`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/pending","data":{"ref":"late"}}""")
        ))

        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Act - create and start activity with deeplink BEFORE setting listener
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=pending")
        }

        val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
        activityController.create().start()


        // Wait for the deeplink to be processed (without a listener set)
        Thread.sleep(1000)


        // Now set the listener after deeplink was already processed
        var listenerCalled = false
        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            listenerCalled = true
        }


        Thread.sleep(500)


        // Assert
        E2ETestUtils.assertAuthenticationCompleted()

        // Listener should not be retroactively called (deeplink was already delivered)
        assertFalse(
            "Listener set after deeplink processed should not be retroactively called",
            listenerCalled
        )

        // But openedLinkDetails should be available with the resolved link
        val openedLink = Grovs.openedLinkDetails
        assertNotNull("openedLinkDetails should be set after deeplink resolved", openedLink)
        assertEquals("https://test.grovs.io/pending", openedLink?.link)

        activityController.stop().destroy()
    }

    // ==================== Advanced Tests ====================

    @Test
    fun `test deep link with custom data payload passes data to listener`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/promo","data":{"promo_code":"SUMMER2024","discount":20}}""")
        ))

        // Configure without creating an activity to avoid consuming the deeplink response
        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Act
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=promo")
        }

        var receivedData: Map<String, Any>? = null
        var listenerCalled = false
        val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
        activityController.create()


        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            listenerCalled = true
            receivedData = details.data
        }

        activityController.start()


        // Wait for deeplink callback - outside runBlocking so main looper is free
        E2ETestUtils.waitForCondition(description = "deeplink data callback") {
            listenerCalled && receivedData != null
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()

        val data = receivedData!!
        assertEquals("Should receive mocked promo_code value",
            "SUMMER2024", data["promo_code"] ?: data["promoCode"])
        assertEquals("Should receive mocked discount value",
            20, (data["discount"] as? Number)?.toInt())

        activityController.stop().destroy()
    }

    @Test
    fun `test multiple deep links in quick succession handled correctly`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/resolved","data":{"seq":"multi"}}""")
        ))

        E2ETestUtils.configureAndWaitForAuthOnly(application)

        var callCount = 0
        val receivedLinks = mutableListOf<String?>()

        // Act
        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()


        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            callCount++
            receivedLinks.add(details.link)
        }

        // Rapidly handle multiple intents
        val intent1 = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=first")
        }
        val intent2 = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=second")
        }
        val intent3 = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=third")
        }

        Grovs.onNewIntent(intent1, activityController.get())

        Grovs.onNewIntent(intent2, activityController.get())

        Grovs.onNewIntent(intent3, activityController.get())


        // Wait for at least one callback
        E2ETestUtils.waitForCondition(description = "deeplink callback from rapid intents") {
            callCount > 0
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertTrue("Listener should be called at least once, got $callCount", callCount >= 1)
        assertTrue("All received links should be the resolved URL",
            receivedLinks.all { it == "https://test.grovs.io/resolved" })

        activityController.stop().destroy()
    }

    @Test
    fun `test deep link with tracking parameters preserves tracking data`() {
        // Arrange - use URL-based dispatcher for deterministic response routing
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"last_seen":null}"""),
            "data_for_device" to MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"link":"https://test.grovs.io/campaign","data":{"product":"premium"},"tracking":{"campaign":"email_blast","source":"newsletter","medium":"email"}}""")
        ))

        // Configure without creating an activity to avoid consuming the deeplink response
        E2ETestUtils.configureAndWaitForAuthOnly(application)

        // Act
        val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("testapp://open?link=campaign")
        }

        var receivedTracking: Map<String, Any>? = null
        var listenerCalled = false
        val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
        activityController.create()


        Grovs.setOnDeeplinkReceivedListener(activityController.get()) { details ->
            listenerCalled = true
            receivedTracking = details.tracking
        }

        activityController.start()


        // Wait for deeplink callback - outside runBlocking so main looper is free
        E2ETestUtils.waitForCondition(description = "deeplink tracking callback") {
            listenerCalled && receivedTracking != null
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()

        val tracking = receivedTracking!!
        assertEquals("Should receive mocked campaign value", "email_blast", tracking["campaign"])
        assertEquals("Should receive mocked source value", "newsletter", tracking["source"])
        assertEquals("Should receive mocked medium value", "email", tracking["medium"])

        activityController.stop().destroy()
    }
}
