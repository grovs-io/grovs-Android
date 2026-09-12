package io.grovs.e2e

import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import io.grovs.Grovs
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.utils.GlInfo
import io.grovs.utils.GlUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.robolectric.Robolectric
import kotlinx.coroutines.test.TestDispatcher
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.CountDownLatch
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

    // Strip the default JUL header line from OkHttp loggers; keeps only the log message.
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

    // Kept alive (never shut down) so lingering GlobalScope coroutines from the previous
    // test can still complete their HTTP requests instead of hitting a connect timeout.
    private val drainedServers = mutableListOf<MockWebServer>()

    /**
     * Creates and starts a MockWebServer.
     * Call this in @Before methods.
     */
    fun createMockWebServer(): MockWebServer {
        val server = MockWebServer()
        server.start()
        return server
    }

    /**
     * Sets up test application with a mock Android ID and clears SDK SharedPreferences
     * so state from a previous test's lingering coroutines can't leak in.
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
     * Cleans up MockWebServer state. Rather than shutting down immediately, installs a
     * catch-all 200 OK dispatcher so lingering GlobalScope coroutines from this test can
     * finish fast instead of hitting a connect timeout; the server itself is never
     * explicitly closed. Call this in @After methods.
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

    /**
     * Enqueues a successful /authenticate response so configure() completes.
     *
     * Serves the response from within the dispatcher (matched by path) rather than via
     * server.enqueue(...): setting server.dispatcher replaces MockWebServer's default
     * QueueDispatcher outright, so a plain enqueue() would sit in a queue nothing drains.
     */
    fun enqueueAuthenticationSuccess(server: MockWebServer) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path.contains("authenticate")) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"linksquared":"grovs_123","uri_scheme":"testscheme"}""")
                }
                // Every other endpoint answers 200 {} unless a test overrides it.
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
    }

    /**
     * Drains the MockWebServer queue looking for a request to [path].
     * Returns null if none arrives within the timeout — used to assert a request was NOT made.
     */
    fun awaitRequestFor(
        server: MockWebServer,
        path: String,
        timeoutMs: Long = 2000,
    ): RecordedRequest? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (request.path == path) return request
        }
        return null
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

    /** Reads a private field of the Grovs singleton via reflection. */
    private fun grovsField(name: String): Any? {
        return try {
            val instance = getGrovsInstance() ?: return null
            Grovs::class.java.getDeclaredField(name).run {
                isAccessible = true
                get(instance)
            }
        } catch (e: Exception) {
            println("Could not read Grovs field '$name': ${e.message}")
            null
        }
    }

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

            // Retire the consent configuration FIRST and wait (bounded) for its cleanup: every
            // operation it admitted is cancelled and finished before anything below closes the
            // manager, cancels jobs or nulls fields, so a still-valid operation can never observe
            // a half-reset singleton, and nothing admitted in this test survives into the next.
            try {
                (grovsField("grovsContext") as? GrovsContext)?.let { replaced ->
                    retireConsentConfiguration(replaced)?.let { errors.add(it) }
                }
            } catch (e: Exception) {
                errors.add("Failed to retire the consent configuration: ${e.message}")
            }

            // Stop the previous manager's custom-events flush timer and authentication.
            try {
                (grovsField("grovsManager") as? GrovsManager)?.close()
            } catch (e: Exception) {
                errors.add("Failed to close GrovsManager: ${e.message}")
            }
            try {
                (grovsField("authenticationJob") as? Job)?.cancel()
            } catch (e: Exception) {
                errors.add("Failed to cancel authenticationJob: ${e.message}")
            }
            try {
                val pendingJobs = grovsField("pendingScreenResolutionJobs") as? MutableMap<*, *>
                pendingJobs?.values?.forEach { (it as? Job)?.cancel() }
                pendingJobs?.clear()
            } catch (e: Exception) {
                errors.add("Failed to clear pending screen resolutions: ${e.message}")
            }

            val registeredApplication = grovsField("application") as? android.app.Application
            val lifecycleObserver =
                grovsField("applicationLifecycleObserver") as? android.app.Application.ActivityLifecycleCallbacks

            // Each configure() call registers the same observer object; leaving old
            // registrations in place duplicates lifecycle callbacks across tests and
            // inflates numStarted so it never reaches 0, so both must be reset here.
            try {
                if (registeredApplication != null && lifecycleObserver != null) {
                    // registerActivityLifecycleCallbacks adds to a list, not a set, so
                    // N configure() calls need N unregisters to remove all copies.
                    repeat(10) {
                        registeredApplication.unregisterActivityLifecycleCallbacks(lifecycleObserver)
                    }
                }

                if (lifecycleObserver != null) {
                    try {
                        val numStartedField = lifecycleObserver.javaClass.getDeclaredField("numStarted")
                        numStartedField.isAccessible = true
                        numStartedField.setInt(lifecycleObserver, 0)
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
                "authenticationJob",
                "deeplinkListener",
                "grovsNotificationsListener",
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

            // Reset grovsContext to a fresh instance so settings/session state don't leak. The replaced
            // context's consent configuration was already retired, and its cleanup awaited, at the top.
            try {
                val contextField = grovsClass.getDeclaredField("grovsContext")
                contextField.isAccessible = true
                contextField.set(instance, GrovsContext())
            } catch (e: Exception) {
                errors.add("Failed to reset grovsContext: ${e.message}")
            }

            // openedLinkDetails is backed by a FlowDelegate, not a plain field named
            // "openedLinkDetails" (its backing field is the delegate itself), so resetting it
            // has to go through the property's own public setter rather than raw field
            // reflection - otherwise a deep link delivered by an earlier test class leaks into
            // the next one via Grovs.openedLinkDetails.
            try {
                (instance as Grovs).openedLinkDetails = null
            } catch (e: Exception) {
                errors.add("Failed to reset 'openedLinkDetails': ${e.message}")
            }

            // lastLinkMatched/handleIntentConflict/lastOnStartTime are plain fields, but not
            // part of the blanket "reset nullable fields to null" loop above: the two latter are
            // primitives (Boolean/Long can't be set to null), so they get explicit resets here.
            try {
                val field = grovsClass.getDeclaredField("lastLinkMatched")
                field.isAccessible = true
                field.set(instance, null)
            } catch (e: Exception) {
                errors.add("Failed to reset 'lastLinkMatched': ${e.message}")
            }
            try {
                val field = grovsClass.getDeclaredField("handleIntentConflict")
                field.isAccessible = true
                field.setBoolean(instance, false)
            } catch (e: Exception) {
                errors.add("Failed to reset 'handleIntentConflict': ${e.message}")
            }
            try {
                val field = grovsClass.getDeclaredField("lastOnStartTime")
                field.isAccessible = true
                field.setLong(instance, 0L)
            } catch (e: Exception) {
                errors.add("Failed to reset 'lastOnStartTime': ${e.message}")
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
     * Flushes pending custom events deterministically. Runs the flush on the SDK's serial
     * dispatcher so it is ordered after any previously enqueued work (track() etc.), after the
     * authentication job has finished.
     */
    /** Publishes [manager] as the singleton's manager, without running a real configure(). */
    internal fun injectGrovsManager(manager: GrovsManager) {
        val instance = getGrovsInstance()
        Grovs::class.java.getDeclaredField("grovsManager").apply { isAccessible = true }.set(instance, manager)
        Grovs::class.java.getDeclaredField("authenticationJob").apply { isAccessible = true }.set(instance, null)
    }

    fun flushCustomEvents() {
        val manager = getGrovsManager() as? GrovsManager ?: return
        val grovsContext = grovsField("grovsContext") as? GrovsContext ?: return
        awaitEventsReleased()
        runBlocking {
            getAuthenticationJob()?.join()
            withContext(grovsContext.serialDispatcher) {
                manager.flushCustomEvents()
            }
        }
    }

    /**
     * Waits until queued custom events are no longer held for a pending link attribution.
     *
     * A flush while the hold is armed sends nothing at all, so a test that flushed too early would
     * see no request and fail for a reason that has nothing to do with what it asserts. Waiting on
     * the actual condition keeps that independent of how many dispatches the SDK happens to take to
     * finish its lookup.
     */
    fun awaitEventsReleased(timeoutMs: Long = 5_000) {
        val manager = getGrovsManager() as? GrovsManager ?: return
        val held = try {
            val customEvents = GrovsManager::class.java.getDeclaredField("customEventsManager")
                .apply { isAccessible = true }.get(manager)
            customEvents.javaClass.getDeclaredField("eventsHeld").apply { isAccessible = true }
                .let { field -> { field.getBoolean(customEvents) } }
        } catch (e: Exception) {
            return // A test double without the field: nothing to wait for.
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (held()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Custom events were still held for link attribution after ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    /**
     * Invokes the SDK's internal lifecycle observers directly, for tests that need to control
     * callback ordering (Robolectric dispatches it differently from a real device).
     */
    fun dispatchActivityResumed(activity: android.app.Activity) {
        val observer = grovsField("applicationLifecycleObserver")
            as? android.app.Application.ActivityLifecycleCallbacks ?: return
        observer.onActivityResumed(activity)
    }

    fun dispatchFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        val observer = grovsField("fragmentLifecycleObserver")
            as? FragmentManager.FragmentLifecycleCallbacks ?: return
        observer.onFragmentResumed(fm, fragment)
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
        return grovsField("authenticationJob") as? Job
    }

    /**
     * Get the current GrovsManager from the Grovs singleton via reflection.
     */
    fun getGrovsManager(): Any? = grovsField("grovsManager")

    /**
     * Reads the Grovs singleton's parked-intent slot via reflection. Tests use this to capture a
     * link parked under the current consent grant, so it can be forced back into the slot later to
     * stand in for a write that raced a revocation and landed after the slot was cleared.
     */
    fun getParkedIntent(): Any? = grovsField("parkedIntent")

    /**
     * Overwrites the Grovs singleton's parked-intent slot via reflection. See [getParkedIntent].
     */
    fun setParkedIntent(value: Any?) {
        val instance = getGrovsInstance() ?: return
        Grovs::class.java.getDeclaredField("parkedIntent").apply {
            isAccessible = true
            set(instance, value)
        }
    }

    /**
     * Get the Grovs singleton instance via reflection.
     */
    /**
     * Retires [context]'s current consent configuration and waits, for at most [timeoutMs], for its
     * cleanup: registered operations cancelled and completed, admitted commits closed, lifetime
     * scope disposed. Returns a description of the problem on timeout, or null once cleanup is done.
     */
    fun retireConsentConfiguration(context: GrovsContext, timeoutMs: Long = 5_000): String? {
        val retirement = context.consent.retireConfiguration(enabled = false)
        val done = CountDownLatch(1)
        retirement.cleanup.invokeOnCompletion { done.countDown() }
        // The retired configuration's operations run on the SDK's serial dispatcher, which in these
        // tests is a virtual-time TestDispatcher: their cancellation only completes when someone
        // advances it. Waiting on this thread without pumping would deadlock against the very work
        // we are waiting for, so drive both clocks while we wait.
        val scheduler = (context.serialDispatcher as? TestDispatcher)?.scheduler
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!done.await(10, TimeUnit.MILLISECONDS)) {
            // runCurrent, not advanceUntilIdle: a cancelled retry loop would otherwise be
            // fast-forwarded through unbounded virtual time and never let this loop return.
            scheduler?.runCurrent()
            if (Looper.myLooper() != null) Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) {
                return "Consent cleanup of ${retirement.retired} did not finish within ${timeoutMs}ms"
            }
        }
        return null
    }

    /**
     * Installs [context] as the singleton's GrovsContext, first retiring the context it replaces.
     * Fails loudly if that cleanup does not finish in time.
     */
    fun installGrovsContext(context: GrovsContext) {
        val field = Grovs::class.java.getDeclaredField("grovsContext").apply { isAccessible = true }
        (field.get(getGrovsInstance()) as? GrovsContext)?.let { replaced ->
            retireConsentConfiguration(replaced)?.let { throw AssertionError(it) }
        }
        field.set(getGrovsInstance(), context)
    }

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
        return (getGrovsManager() as? GrovsManager)?.authenticationState?.toString()
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
        return (grovsField("grovsContext") as? GrovsContext)?.grovsId
    }

    /**
     * Check if SDK is enabled.
     */
    fun isSdkEnabled(): Boolean {
        return (grovsField("grovsContext") as? GrovsContext)?.settings?.sdkEnabled ?: true
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

    /**
     * Extracts every event object from every `/api/v1/sdk/events/batch` request in [requests], in
     * request order and then in-batch order. Non-batch requests (authenticate, device_for_vendor_id,
     * ...) are excluded by path before any JSON parsing happens; a request that DOES match the batch
     * path but isn't a JSON object with an "events" array is a real regression and fails loudly rather
     * than being silently skipped.
     */
    fun eventsFromBatchRequests(requests: List<Pair<String, String>>): List<JSONObject> {
        return requests
            .filter { (path, _) -> path == "/api/v1/sdk/events/batch" }
            .flatMap { (path, body) ->
                val events = try {
                    JSONObject(body).getJSONArray("events")
                } catch (e: Exception) {
                    throw AssertionError(
                        "events/batch request body was not a JSON object with an \"events\" array " +
                            "(path=$path): $body",
                        e
                    )
                }
                (0 until events.length()).map { events.getJSONObject(it) }
            }
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
        apiKey: String = "test-api-key",
        baseURL: String? = null
    ) {
        Grovs.configure(application, apiKey, useTestEnvironment = true, baseURL = baseURL)
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
        apiKey: String = "test-api-key",
        baseURL: String? = null
    ): ActivityController<TestActivity>? {
        Grovs.configure(application, apiKey, useTestEnvironment = true, baseURL = baseURL)

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
     * Bypasses the 15-second leeway delay before events are sent, by backdating
     * firstRequestTime and setting allowedToSendToBackend = true on the EventsManager.
     * Backdating is required because checkEventsSendingAllowed() recomputes
     * allowedToSendToBackend from (now - firstRequestTime), which would otherwise
     * overwrite a plain boolean set.
     */
    fun enableImmediateEventSending() {
        try {
            val manager = getGrovsManager() ?: return

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
