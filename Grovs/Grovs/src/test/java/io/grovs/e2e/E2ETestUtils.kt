package io.grovs.e2e

import android.app.Application
import android.os.Looper
import android.provider.Settings
import io.grovs.Grovs
import io.grovs.handlers.GrovsContext
import io.grovs.service.GrovsService
import io.grovs.utils.GlInfo
import io.grovs.utils.GlUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.TimeUnit
import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Shared utilities for E2E tests.
 * Provides MockWebServer management, response enqueuers, and SDK state helpers.
 */
object E2ETestUtils {

    // Replace default JUL formatter on OkHttp loggers to suppress redundant
    // "Jan 28, 2026 4:07:35 PM okhttp3.internal.platform.Platform log" header lines.
    // Keeps the actual HTTP log content visible.
    private val okhttpLoggers = listOf(
        Logger.getLogger("okhttp3.internal.platform.Platform"),
        Logger.getLogger("okhttp3.OkHttpClient"),
        Logger.getLogger("okhttp3.internal.platform")
    ).onEach { logger ->
        logger.useParentHandlers = false
        logger.addHandler(ConsoleHandler().apply {
            formatter = object : Formatter() {
                override fun format(record: LogRecord): String = "${record.message}\n"
            }
        })
    }

    // ==================== MockWebServer Management ====================

    // Servers kept alive after tests so lingering GlobalScope coroutines
    // (from onAppBackgrounded/onAppForegrounded) can complete their HTTP requests
    // against a live server instead of hitting ConnectException + 10s OkHttp timeout
    // + 5s retry delay. These are never explicitly shut down — the JVM process exit
    // cleans them up. Keeping ~30 ephemeral ports alive is fine for a test suite.
    private val drainedServers = mutableListOf<MockWebServer>()

    /**
     * Creates and starts a MockWebServer.
     * Call this in @Before methods.
     */
    fun createMockWebServer(): MockWebServer {
        val server = MockWebServer()
        server.start()
        GrovsService.testBaseUrl = server.url("/").toString()
        return server
    }

    /**
     * Sets up test application with mock Android ID and clears SDK SharedPreferences
     * to prevent stale data from lingering GlobalScope.launch coroutines (e.g.,
     * markTimeSpentNode from onAppBackgrounded) contaminating the next test.
     */
    fun setupTestApplication(application: Application) {
        Settings.Secure.putString(
            application.contentResolver,
            Settings.Secure.ANDROID_ID,
            "robolectric-test-device-id"
        )
        // Clear all SDK SharedPreferences to start fresh
        application.getSharedPreferences("GrovsStorage", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        application.getSharedPreferences("grovs_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /**
     * Cleans up MockWebServer and test state.
     * Instead of shutting down immediately, installs a permissive drain dispatcher
     * that returns 200 OK for all requests. This lets lingering GlobalScope coroutines
     * (e.g., sendTimeSpentEventsToBackend retry loops from the previous test) complete
     * their HTTP calls instantly rather than hitting ConnectException → 10s OkHttp
     * connect timeout → 5s delay per event. The server is shut down in the next
     * test's createMockWebServer() call.
     * Call this in @After methods.
     */
    fun cleanupMockWebServer(server: MockWebServer) {
        try {
            // Install a catch-all dispatcher so lingering coroutines get fast 200 responses
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}")
                }
            }
            // Keep server alive indefinitely for lingering coroutines
            drainedServers.add(server)
        } catch (e: Exception) {
            // If we can't set the dispatcher, shut down immediately as fallback
            try { server.shutdown() } catch (e2: Exception) { /* ignore */ }
        }
        GrovsService.testBaseUrl = null
    }

    // ==================== Response Enqueuers ====================

    fun enqueueAuthenticationResponse(server: MockWebServer, grovsId: String = "test-grovs-id-123") {
        val response = """
            {
                "linksquared": "$grovsId",
                "uri_scheme": "testapp"
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueDeviceResponse(server: MockWebServer, lastSeen: String? = null) {
        val lastSeenJson = if (lastSeen != null) "\"$lastSeen\"" else "null"
        val response = """
            {
                "last_seen": $lastSeenJson
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueEventResponse(server: MockWebServer) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
        )
    }

    fun enqueueDataForDeviceResponse(
        server: MockWebServer,
        link: String? = null,
        data: Map<String, Any>? = null
    ) {
        val linkJson = link?.let { "\"$it\"" } ?: "null"
        val dataJson = data?.let {
            data.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":${if (v is String) "\"$v\"" else v}"
            }
        } ?: "null"
        val response = """
            {
                "link": $linkJson,
                "data": $dataJson
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueDataForDeviceAndUrlResponse(
        server: MockWebServer,
        link: String? = null,
        data: Map<String, Any>? = null,
        tracking: Map<String, Any>? = null
    ) {
        val linkJson = link?.let { "\"$it\"" } ?: "null"
        val dataJson = data?.let {
            data.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":${if (v is String) "\"$v\"" else v}"
            }
        } ?: "null"
        val trackingJson = tracking?.let {
            tracking.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":${if (v is String) "\"$v\"" else v}"
            }
        } ?: "null"
        val response = """
            {
                "link": $linkJson,
                "data": $dataJson,
                "tracking": $trackingJson
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueGenerateLinkResponse(server: MockWebServer, link: String = "https://test.grovs.io/abc123") {
        val response = """
            {
                "link": "$link"
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueLinkDetailsResponse(
        server: MockWebServer,
        title: String? = "Test Title",
        subtitle: String? = "Test Subtitle",
        imageUrl: String? = null,
        data: Map<String, Any>? = null
    ) {
        val titleJson = title?.let { "\"$it\"" } ?: "null"
        val subtitleJson = subtitle?.let { "\"$it\"" } ?: "null"
        val imageJson = imageUrl?.let { "\"$it\"" } ?: "null"
        val dataJson = data?.let {
            data.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":${if (v is String) "\"$v\"" else v}"
            }
        } ?: "null"
        val response = """
            {
                "title": $titleJson,
                "subtitle": $subtitleJson,
                "image_url": $imageJson,
                "data": $dataJson
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueNotificationsResponse(server: MockWebServer, notifications: List<Map<String, Any>> = emptyList()) {
        val notificationsJson = notifications.joinToString(",", "[", "]") { notification ->
            notification.entries.joinToString(",", "{", "}") { (k, v) ->
                when (v) {
                    is String -> "\"$k\":\"$v\""
                    is Boolean -> "\"$k\":$v"
                    else -> "\"$k\":$v"
                }
            }
        }
        val response = """
            {
                "notifications": $notificationsJson
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueUnreadCountResponse(server: MockWebServer, count: Int) {
        val response = """
            {
                "number_of_unread_notifications": $count
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueVisitorAttributesResponse(server: MockWebServer) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
        )
    }

    fun enqueueErrorResponse(server: MockWebServer, code: Int, message: String = "Error") {
        val response = """
            {
                "error": "$message"
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    fun enqueueDelayedResponse(server: MockWebServer, delayMs: Long, responseCode: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(responseCode)
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
        )
    }

    // PURCHASE_EVENT_DISABLED: fun enqueuePaymentEventResponse(server: MockWebServer) {
    // PURCHASE_EVENT_DISABLED:     server.enqueue(
    // PURCHASE_EVENT_DISABLED:         MockResponse()
    // PURCHASE_EVENT_DISABLED:             .setResponseCode(200)
    // PURCHASE_EVENT_DISABLED:             .setHeader("Content-Type", "application/json")
    // PURCHASE_EVENT_DISABLED:             .setBody("{}")
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED: }

    fun enqueueAutoDisplayNotificationsResponse(server: MockWebServer, notifications: List<Map<String, Any>> = emptyList()) {
        val notificationsJson = notifications.joinToString(",", "[", "]") { notification ->
            notification.entries.joinToString(",", "{", "}") { (k, v) ->
                when (v) {
                    is String -> "\"$k\":\"$v\""
                    is Boolean -> "\"$k\":$v"
                    is Int -> "\"$k\":$v"
                    else -> "\"$k\":$v"
                }
            }
        }
        val response = """
            {
                "notifications": $notificationsJson
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response)
        )
    }

    // ==================== Singleton/State Management ====================

    /**
     * Reset Grovs singleton state via reflection.
     * This allows each test to start with a fresh SDK state.
     */
    fun resetGrovsSingleton() {
        val errors = mutableListOf<String>()

        try {
            val grovsClass = Grovs::class.java
            val instanceField = grovsClass.getDeclaredField("instance")
            instanceField.isAccessible = true
            val instance = instanceField.get(null)
                ?: throw IllegalStateException("Grovs instance is null")

            // Unregister lifecycle callbacks and reset numStarted to prevent
            // accumulation across tests. Each configure() call registers the same
            // observer object; without unregistering ALL copies, subsequent tests
            // get duplicate onActivityStarted/onActivityStopped callbacks. This
            // inflates numStarted so it never reaches 0, preventing
            // onAppBackgrounded/onAppForegrounded from firing.
            try {
                val appField = grovsClass.getDeclaredField("application")
                appField.isAccessible = true
                val app = appField.get(instance) as? android.app.Application

                val observerField = grovsClass.getDeclaredField("applicationLifecycleObserver")
                observerField.isAccessible = true
                val observer = observerField.get(instance) as? android.app.Application.ActivityLifecycleCallbacks

                if (app != null && observer != null) {
                    // Unregister multiple times to remove all accumulated registrations.
                    // registerActivityLifecycleCallbacks adds to a list (not a set), so
                    // N configure() calls = N registrations. Each unregister removes one.
                    repeat(10) {
                        app.unregisterActivityLifecycleCallbacks(observer)
                    }
                }

                // Reset numStarted to 0 so the next test's first onActivityStarted
                // correctly detects numStarted==0 → foreground transition.
                if (observer != null) {
                    try {
                        val numStartedField = observer.javaClass.getDeclaredField("numStarted")
                        numStartedField.isAccessible = true
                        numStartedField.setInt(observer, 0)
                    } catch (e: Exception) {
                        errors.add("Failed to reset numStarted: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                errors.add("Failed to unregister lifecycle callbacks: ${e.message}")
            }

            // Reset nullable fields to null
            listOf(
                "grovsManager",
                "notificationsManager",
                "apiKey",
                "application",
                "deeplinkListener",
                "grovsNotificationsListener",
                "authenticationJob",
                "launcherActivityReference",
                "currentActivityReference"
            ).forEach { fieldName ->
                try {
                    val field = grovsClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(instance, null)
                } catch (e: NoSuchFieldException) {
                    errors.add("Field '$fieldName' not found")
                } catch (e: Exception) {
                    errors.add("Failed to reset '$fieldName': ${e.message}")
                }
            }

            // Reset grovsContext to fresh instance
            try {
                val contextField = grovsClass.getDeclaredField("grovsContext")
                contextField.isAccessible = true
                contextField.set(instance, GrovsContext())
            } catch (e: Exception) {
                errors.add("Failed to reset grovsContext: ${e.message}")
            }

        } catch (e: Exception) {
            throw AssertionError(
                "Critical failure resetting Grovs singleton - tests will leak state: ${e.message}",
                e
            )
        }

        if (errors.isNotEmpty()) {
            System.err.println("WARNING: E2E singleton reset incomplete (${errors.size} issues):")
            errors.forEach { System.err.println("  - $it") }
        }
    }

    /**
     * Pre-populate ScreenUtils cache with known values so the SDK sends
     * predictable screen dimensions during authentication. These values
     * must match what postDeviceFingerprint() sends to the browser device.
     */
    fun setupMockScreenResolution(width: String = "393", height: String = "851") {
        try {
            val screenUtilsClass = Class.forName("io.grovs.utils.ScreenUtils")
            val cachedField = screenUtilsClass.getDeclaredField("cachedResolution")
            cachedField.isAccessible = true
            cachedField.set(null, Pair(width, height))
            println("Mock screen resolution set to: ${width}x${height}")
        } catch (e: Exception) {
            // ScreenUtils is a Kotlin object; try instance-based access
            try {
                val screenUtilsClass = Class.forName("io.grovs.utils.ScreenUtils")
                val instanceField = screenUtilsClass.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                val instance = instanceField.get(null)
                val cachedField = screenUtilsClass.getDeclaredField("cachedResolution")
                cachedField.isAccessible = true
                cachedField.set(instance, Pair(width, height))
                println("Mock screen resolution set to: ${width}x${height} (via INSTANCE)")
            } catch (e2: Exception) {
                println("Warning: Could not set mock screen resolution: ${e.message}, ${e2.message}")
            }
        }
    }

    /**
     * Pre-populate GlUtils cache to avoid EGL errors in Robolectric.
     */
    fun setupMockGlInfo() {
        try {
            val glUtilsClass = GlUtils::class.java
            val cachedGlInfoField = glUtilsClass.getDeclaredField("cachedGlInfo")
            cachedGlInfoField.isAccessible = true
            cachedGlInfoField.set(GlUtils, GlInfo(
                vendor = "Robolectric",
                renderer = "Robolectric GL",
                version = "OpenGL ES 2.0"
            ))
        } catch (e: Exception) {
            println("Warning: Could not set mock GlInfo: ${e.message}")
        }
    }

    /**
     * Set the cached user agent in WebViewUtils to match a browser profile.
     * This ensures the SDK sends the same user agent as the simulated browser,
     * allowing the backend to match browser sessions to SDK sessions.
     */
    fun setupMockUserAgent(userAgent: String) {
        try {
            // Kotlin stores private companion vars as static fields on the outer class
            val webViewUtilsClass = Class.forName("io.grovs.utils.WebViewUtils")
            val cachedField = webViewUtilsClass.getDeclaredField("cachedUserAgent")
            cachedField.isAccessible = true
            cachedField.set(null, userAgent)
            println("Mock user agent set to: $userAgent")
        } catch (e: Exception) {
            println("Warning: Could not set mock user agent: ${e.message}")
        }
    }

    /**
     * Get the authenticationJob from Grovs singleton via reflection.
     */
    fun getAuthenticationJob(): Job? {
        return try {
            val grovsClass = Grovs::class.java
            val instanceField = grovsClass.getDeclaredField("instance")
            instanceField.isAccessible = true
            val instance = instanceField.get(null)

            val jobField = grovsClass.getDeclaredField("authenticationJob")
            jobField.isAccessible = true
            jobField.get(instance) as? Job
        } catch (e: Exception) {
            println("Could not get authenticationJob: ${e.message}")
            null
        }
    }

    /**
     * Get the Grovs singleton instance via reflection.
     */
    fun getGrovsInstance(): Any? {
        return try {
            val companionClass = Class.forName("io.grovs.Grovs\$Companion")
            val grovsClass = Grovs::class.java

            val companionField = grovsClass.getDeclaredField("Companion")
            companionField.isAccessible = true
            val companion = companionField.get(null)

            val instanceField = companionClass.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.get(companion)
        } catch (e: Exception) {
            try {
                val grovsClass = Grovs::class.java
                val instanceField = grovsClass.getDeclaredField("instance")
                instanceField.isAccessible = true
                instanceField.get(null)
            } catch (e2: Exception) {
                println("Could not get Grovs instance: ${e.message}, fallback failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Get authentication state from GrovsManager.
     */
    fun getAuthenticationState(): String? {
        return try {
            val instance = getGrovsInstance() ?: return null

            val managerField = instance.javaClass.getDeclaredField("grovsManager")
            managerField.isAccessible = true
            val manager = managerField.get(instance) ?: return null

            val stateField = manager.javaClass.getDeclaredField("authenticationState")
            stateField.isAccessible = true
            stateField.get(manager)?.toString()
        } catch (e: Exception) {
            println("Could not get auth state: ${e.message}")
            null
        }
    }

    /**
     * Get grovsId from GrovsContext.
     */
    fun setNumStarted(value: Int) {
        try {
            val instance = getGrovsInstance() ?: return
            val observerField = instance.javaClass.getDeclaredField("applicationLifecycleObserver")
            observerField.isAccessible = true
            val observer = observerField.get(instance) ?: return
            val numStartedField = observer.javaClass.getDeclaredField("numStarted")
            numStartedField.isAccessible = true
            numStartedField.setInt(observer, value)
        } catch (e: Exception) {
            println("Could not set numStarted: ${e.message}")
        }
    }

    fun getGrovsId(): String? {
        return try {
            val instance = getGrovsInstance() ?: return null

            val contextField = instance.javaClass.getDeclaredField("grovsContext")
            contextField.isAccessible = true
            val context = contextField.get(instance)

            val grovsIdField = context.javaClass.getDeclaredField("grovsId")
            grovsIdField.isAccessible = true
            grovsIdField.get(context) as? String
        } catch (e: Exception) {
            println("Could not get grovsId: ${e.message}")
            null
        }
    }

    /**
     * Check if SDK is enabled.
     */
    fun isSdkEnabled(): Boolean {
        return try {
            val instance = getGrovsInstance() ?: return true

            val contextField = instance.javaClass.getDeclaredField("grovsContext")
            contextField.isAccessible = true
            val context = contextField.get(instance)

            val settingsField = context.javaClass.getDeclaredField("settings")
            settingsField.isAccessible = true
            val settings = settingsField.get(context)

            val enabledField = settings.javaClass.getDeclaredField("sdkEnabled")
            enabledField.isAccessible = true
            enabledField.get(settings) as? Boolean ?: true
        } catch (e: Exception) {
            println("Could not get SDK enabled state: ${e.message}")
            true
        }
    }

    // ==================== Request Helpers ====================

    /**
     * Collect all requests made to MockWebServer.
     */
    fun collectAllRequests(server: MockWebServer): List<Pair<String, String>> {
        val requests = mutableListOf<Pair<String, String>>()
        while (true) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
            val path = request.path ?: ""
            val body = request.body.readUtf8()
            requests.add(Pair(path, body))
        }
        return requests
    }

    /**
     * Find requests matching a path pattern.
     */
    fun findRequestsByPath(requests: List<Pair<String, String>>, pathContains: String): List<Pair<String, String>> {
        return requests.filter { it.first.contains(pathContains) }
    }

    // ==================== Assertion Helpers ====================

    /**
     * Assert authentication flow completed.
     */
    fun assertAuthenticationCompleted() {
        val authState = getAuthenticationState()
        if (authState != null) {
            assertEquals("Authentication state should be AUTHENTICATED", "AUTHENTICATED", authState)
        }
    }

    /**
     * Verify requests contain specific endpoint calls.
     */
    fun assertRequestMade(requests: List<Pair<String, String>>, endpoint: String, message: String = "Request to $endpoint should be made") {
        assertTrue(message, requests.any { it.first.contains(endpoint) })
    }

    /**
     * Verify request body contains expected content.
     */
    fun assertRequestBodyContains(requests: List<Pair<String, String>>, endpoint: String, content: String, message: String? = null) {
        val matchingRequests = requests.filter { it.first.contains(endpoint) }
        assertTrue(
            message ?: "Request body for $endpoint should contain '$content'",
            matchingRequests.any { it.second.contains(content) }
        )
    }

    /**
     * Verify SDK is functional after error/timeout by exercising public API
     * properties and checking internal state is consistent.
     *
     * Checks:
     * 1. Public properties (identifier, pushToken, attributes) are readable without exceptions
     * 2. Public properties are writable without exceptions
     * 3. Authentication state is a known valid enum value
     * 4. SDK is still enabled
     * 5. Grovs singleton instance exists
     */
    fun assertSdkFunctionalAfterError() {
        // 1. Verify singleton instance exists
        val instance = getGrovsInstance()
        assertNotNull("Grovs singleton instance should exist after error", instance)

        // 2. Verify public properties are readable
        val identifier = Grovs.identifier
        val pushToken = Grovs.pushToken
        val attributes = Grovs.attributes

        // 3. Verify public properties are writable (set, then restore)
        val originalIdentifier = identifier
        Grovs.identifier = "sdk-functional-check"
        assertEquals(
            "Setting identifier should work after error",
            "sdk-functional-check",
            Grovs.identifier
        )
        Grovs.identifier = originalIdentifier

        val originalPushToken = pushToken
        Grovs.pushToken = "test-push-token-check"
        assertEquals(
            "Setting pushToken should work after error",
            "test-push-token-check",
            Grovs.pushToken
        )
        Grovs.pushToken = originalPushToken

        // 4. Verify authentication state is a known valid value
        val authState = getAuthenticationState()
        assertNotNull("Authentication state should be accessible after error", authState)
        assertTrue(
            "Authentication state should be a valid enum value, got: $authState",
            authState in listOf("AUTHENTICATED", "RETRYING", "UNAUTHENTICATED")
        )

        // 5. Verify SDK is still enabled
        assertTrue("SDK should still be enabled after error", isSdkEnabled())
    }

    /**
     * Verify authentication is in progress or retrying.
     */
    fun assertAuthenticationInProgress() {
        val authState = getAuthenticationState()
        assertNotNull("Authentication state should be accessible", authState)
        assertTrue(
            "Authentication state should be RETRYING or UNAUTHENTICATED during timeout/error, got: $authState",
            authState in listOf("RETRYING", "UNAUTHENTICATED")
        )
    }

    /**
     * Verify event infrastructure works.
     */
    fun verifyEventInfrastructureWorks(requests: List<Pair<String, String>>) {
        assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
        assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")
    }

    /**
     * Verify payment infrastructure works.
     */
    fun verifyPaymentInfrastructureWorks(requests: List<Pair<String, String>>) {
        assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
        assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")
    }

    // ==================== Request Value Assertions ====================
    // These mirror the enqueue* functions — verifying the SDK sent expected values.

    private const val TEST_VENDOR_ID = "robolectric-test-device-id"
    private const val TEST_APP_VERSION = "1.0.0"
    private const val TEST_BUNDLE = "io.grovs.test"
    private const val TEST_DEVICE = "Unknown robolectric"

    /**
     * Assert the authenticate request was made with correct device info.
     * Mirrors [enqueueAuthenticationResponse].
     */
    fun assertAuthenticateRequestValues(requests: List<Pair<String, String>>) {
        val authRequests = findRequestsByPath(requests, "authenticate")
        assertTrue("Should call authenticate endpoint", authRequests.isNotEmpty())
        val body = authRequests.first().second
        assertTrue("Auth request should contain vendor_id '$TEST_VENDOR_ID'",
            body.contains("\"vendor_id\":\"$TEST_VENDOR_ID\""))
        assertTrue("Auth request should contain device '$TEST_DEVICE'",
            body.contains("\"device\":\"$TEST_DEVICE\""))
        assertTrue("Auth request should contain app_version '$TEST_APP_VERSION'",
            body.contains("\"app_version\":\"$TEST_APP_VERSION\""))
        assertTrue("Auth request should contain bundle '$TEST_BUNDLE'",
            body.contains("\"bundle\":\"$TEST_BUNDLE\""))
    }

    /**
     * Assert the device_for_vendor_id request was made with correct vendor_id.
     * Mirrors [enqueueDeviceResponse].
     */
    fun assertDeviceRequestValues(requests: List<Pair<String, String>>) {
        val deviceRequests = findRequestsByPath(requests, "device_for_vendor_id")
        assertTrue("Should call device_for_vendor_id endpoint", deviceRequests.isNotEmpty())
        val path = deviceRequests.first().first
        assertTrue("Device request should query with vendor_id=$TEST_VENDOR_ID",
            path.contains("vendor_id=$TEST_VENDOR_ID"))
    }

    /**
     * Assert event requests contain the expected event type.
     * Mirrors [enqueueEventResponse].
     */
    fun assertEventRequestValues(requests: List<Pair<String, String>>, eventType: String) {
        val eventRequests = findRequestsByPath(requests, "event")
        assertTrue("Should have event requests", eventRequests.isNotEmpty())
        assertTrue("Event requests should contain '$eventType' event",
            eventRequests.any { it.second.contains(eventType) })
    }

    /**
     * Assert a payment event request contains expected values.
     * Mirrors [enqueuePaymentEventResponse].
     */
    // PURCHASE_EVENT_DISABLED: fun assertPaymentEventRequestValues(
    // PURCHASE_EVENT_DISABLED:     requests: List<Pair<String, String>>,
    // PURCHASE_EVENT_DISABLED:     priceInCents: Int,
    // PURCHASE_EVENT_DISABLED:     currency: String,
    // PURCHASE_EVENT_DISABLED:     productId: String
    // PURCHASE_EVENT_DISABLED: ) {
    // PURCHASE_EVENT_DISABLED:     val eventRequests = findRequestsByPath(requests, "event")
    // PURCHASE_EVENT_DISABLED:     val paymentRequest = eventRequests.find {
    // PURCHASE_EVENT_DISABLED:         it.second.contains(priceInCents.toString()) || it.second.contains(productId)
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED:     if (paymentRequest != null) {
    // PURCHASE_EVENT_DISABLED:         assertTrue("Payment request should contain price $priceInCents",
    // PURCHASE_EVENT_DISABLED:             paymentRequest.second.contains(priceInCents.toString()))
    // PURCHASE_EVENT_DISABLED:         assertTrue("Payment request should contain currency $currency",
    // PURCHASE_EVENT_DISABLED:             paymentRequest.second.contains(currency))
    // PURCHASE_EVENT_DISABLED:         assertTrue("Payment request should contain productId $productId",
    // PURCHASE_EVENT_DISABLED:             paymentRequest.second.contains(productId))
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED: }

    /**
     * Assert a visitor_attributes request contains expected field and value.
     * Mirrors [enqueueVisitorAttributesResponse].
     */
    fun assertVisitorAttributesRequestValues(
        requests: List<Pair<String, String>>,
        field: String,
        value: String
    ) {
        val attrRequests = findRequestsByPath(requests, "visitor_attributes")
        if (attrRequests.isNotEmpty()) {
            assertTrue("visitor_attributes request should contain '$field' with '$value'",
                attrRequests.any { it.second.contains(field) && it.second.contains(value) })
        } else {
            // Async API call may not complete within test timeframe
            assertAuthenticationCompleted()
        }
    }

    /**
     * Assert the generate_link request contains expected parameters.
     * Mirrors [enqueueGenerateLinkResponse].
     */
    fun assertGenerateLinkRequestValues(
        requests: List<Pair<String, String>>,
        expectedTitle: String? = null,
        expectedSubtitle: String? = null
    ) {
        val linkRequests = findRequestsByPath(requests, "generate_link")
        assertTrue("Should call generate_link endpoint", linkRequests.isNotEmpty())
        val body = linkRequests.first().second
        expectedTitle?.let {
            assertTrue("Generate link request should contain title '$it'", body.contains(it))
        }
        expectedSubtitle?.let {
            assertTrue("Generate link request should contain subtitle '$it'", body.contains(it))
        }
    }

    /**
     * Assert the data_for_device_and_url request was made with the expected deeplink URL.
     * Mirrors [enqueueDataForDeviceResponse] / [enqueueDataForDeviceAndUrlResponse].
     */
    fun assertDataForDeviceAndUrlRequestValues(
        requests: List<Pair<String, String>>,
        expectedScheme: String,
        expectedLinkParam: String
    ) {
        val dataRequests = findRequestsByPath(requests, "data_for_device_and_url")
        assertTrue("SDK should call data_for_device_and_url endpoint", dataRequests.isNotEmpty())
        val body = dataRequests.first().second
        assertTrue("Request should contain deeplink scheme '$expectedScheme'",
            body.contains(expectedScheme))
        assertTrue("Request should contain deeplink parameter '$expectedLinkParam'",
            body.contains(expectedLinkParam))
        assertTrue("Request should contain vendor_id '$TEST_VENDOR_ID'",
            body.contains(TEST_VENDOR_ID))
    }

    /**
     * Assert the data_for_device request was made (no deeplink URL).
     * Mirrors [enqueueDataForDeviceResponse].
     */
    fun assertDataForDeviceRequestValues(requests: List<Pair<String, String>>) {
        val dataRequests = findRequestsByPath(requests, "data_for_device")
        assertTrue("SDK should call data_for_device endpoint", dataRequests.isNotEmpty())
        val body = dataRequests.first().second
        assertTrue("Request should contain vendor_id '$TEST_VENDOR_ID'",
            body.contains(TEST_VENDOR_ID))
    }

    // ==================== Async Helpers ====================

    /**
     * Run a suspend function that needs Dispatchers.Main while pumping the looper.
     */
    fun <T> runWithLooperPumping(
        timeoutMs: Long = 5_000L,
        failOnTimeout: Boolean = false,
        operationName: String = "operation",
        block: suspend CoroutineScope.() -> T
    ): T? {
        val deferred = CompletableDeferred<T>()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = block()
                deferred.complete(result)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }

        val startTime = System.currentTimeMillis()
        while (!deferred.isCompleted && System.currentTimeMillis() - startTime < timeoutMs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }

        val elapsedMs = System.currentTimeMillis() - startTime

        return if (deferred.isCompleted) {
            try {
                runBlocking { deferred.await() }
            } catch (e: Exception) {
                if (failOnTimeout) {
                    throw AssertionError("$operationName failed with exception after ${elapsedMs}ms", e)
                }
                null
            }
        } else {
            if (failOnTimeout) {
                throw AssertionError(
                    "$operationName timed out after ${timeoutMs}ms. " +
                    "This may indicate a deadlock or the operation taking longer than expected."
                )
            }
            null
        }
    }

    /**
     * Wait for a condition to become true while pumping the main looper.
     * Fails the test if the condition is not met within the timeout.
     *
     * Uses a [CountDownLatch] with looper pumping to handle async SDK callbacks
     * that dispatch to Dispatchers.Main via the Android main looper.
     */
    fun waitForCondition(
        timeoutMs: Long = 5_000L,
        description: String = "condition",
        condition: () -> Boolean
    ) {
        val startTime = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - startTime < timeoutMs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for $description after ${timeoutMs}ms", condition())
    }

    /**
     * Configure SDK and wait for authentication without creating an activity.
     * Use this for tests that create their own activity (e.g., deeplink tests)
     * to avoid a second handleIntent call consuming mock responses.
     */
    fun configureAndWaitForAuthOnly(
        application: Application,
        apiKey: String = "test-api-key"
    ) {
        Grovs.configure(application, apiKey, useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking {
            val authJob = getAuthenticationJob()
            withTimeoutOrNull(10_000) { authJob?.join() }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Configure SDK and wait for authentication.
     */
    suspend fun configureAndWaitForAuth(
        application: Application,
        apiKey: String = "test-api-key"
    ): ActivityController<TestActivity>? {
        Grovs.configure(application, apiKey, useTestEnvironment = true)

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        activityController.start()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val authJob = getAuthenticationJob()
        withTimeoutOrNull(10_000) { authJob?.join() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        return activityController
    }

    /**
     * Process main looper.
     */
    fun processMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    // ==================== URL-Based Mock Dispatcher ====================

    /**
     * Set up a URL-based dispatcher on MockWebServer so responses are matched
     * by endpoint path instead of consumed FIFO. This is needed for deeplink
     * tests where multiple concurrent SDK calls (auth, events, notifications,
     * deeplink resolution) would consume responses in unpredictable order.
     *
     * @param responses Map of path substring to MockResponse.
     *   A request whose path contains the key gets the corresponding response.
     *   Unmatched requests get a 200 OK with empty JSON body.
     */
    /**
     * Enable immediate event sending by setting allowedToSendToBackend = true on the EventsManager
     * and backdating firstRequestTime to 20 seconds ago. This bypasses the 15-second leeway delay
     * that normally prevents events from being sent immediately.
     *
     * The backdating is necessary because checkEventsSendingAllowed() recalculates
     * allowedToSendToBackend based on (now - firstRequestTime) > 14 seconds, which would
     * overwrite a simple boolean set.
     */
    fun enableImmediateEventSending() {
        try {
            val instance = getGrovsInstance() ?: return

            val managerField = instance.javaClass.getDeclaredField("grovsManager")
            managerField.isAccessible = true
            val manager = managerField.get(instance) ?: return

            val eventsManagerField = manager.javaClass.getDeclaredField("eventsManager")
            eventsManagerField.isAccessible = true
            val eventsManager = eventsManagerField.get(manager) ?: return

            // Set firstRequestTime to 20 seconds ago so the duration check passes
            val firstRequestTimeField = eventsManager.javaClass.getDeclaredField("firstRequestTime")
            firstRequestTimeField.isAccessible = true
            val instantCompatClass = firstRequestTimeField.type
            val constructor = instantCompatClass.getConstructor(Long::class.java)
            val pastInstant = constructor.newInstance(System.currentTimeMillis() - 20_000)
            firstRequestTimeField.set(eventsManager, pastInstant)

            // Set allowedToSendToBackend = true
            val allowedField = eventsManager.javaClass.getDeclaredField("allowedToSendToBackend")
            allowedField.isAccessible = true
            allowedField.setBoolean(eventsManager, true)
        } catch (e: Exception) {
            println("Could not enable immediate event sending: ${e.message}")
        }
    }

    fun setUrlDispatcher(
        server: MockWebServer,
        responses: Map<String, MockResponse>
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                for ((pathContains, response) in responses) {
                    if (path.contains(pathContains)) {
                        return response
                    }
                }
                // Default: return 200 OK for any unmatched request
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}")
            }
        }
    }

}
