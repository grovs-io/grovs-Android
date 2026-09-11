package io.grovs.e2e

import android.os.Looper
import io.grovs.Grovs
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Shared SDK/server lifecycle and screen-event collection for navigation scenarios. */
abstract class ScreenTrackingTestBase {
    protected lateinit var mockWebServer: MockWebServer

    @Before
    fun setUpScreenTracking() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupTestApplication(RuntimeEnvironment.getApplication())
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        mockWebServer = E2ETestUtils.createMockWebServer()
        // Plain TestActivity also starts attribution. Give that flow valid no-match replies
        // so a missing clipboard flag cannot leave screen events held behind a retry.
        fun json(body: String) =
            MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to json("""{"linksquared":"grovs_123","uri_scheme":"testscheme"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "clipboard_status" to json("""{"clipboard_active":false}"""),
        ))
    }

    @After
    fun tearDownScreenTracking() {
        try {
            mockWebServer.shutdown()
        } finally {
            E2ETestUtils.resetGrovsSingleton()
        }
    }

    protected fun configure(autoTrack: Boolean = true) {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
            autoTrackScreenViews = autoTrack,
        )
    }

    protected fun settleAutomaticScreenResolution() {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        mainLooper.idle()
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        while (pendingScreenResolutionJobs().isNotEmpty()) {
            mainLooper.idle()
            if (System.nanoTime() >= deadlineNanos) {
                throw AssertionError("Timed out waiting for automatic screen resolution")
            }
            Thread.sleep(10L)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingScreenResolutionJobs(): Collection<Job> {
        val field = Grovs::class.java.getDeclaredField("pendingScreenResolutionJobs")
        field.isAccessible = true
        return (field.get(E2ETestUtils.getGrovsInstance()) as ConcurrentHashMap<*, Job>).values
    }

    /** Returns new screen names in wire order. Pager scenarios settle during layout themselves. */
    protected fun drainScreenNames(settleResolution: Boolean = true): List<String> {
        if (settleResolution) settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()
        return readScreenNames()
    }

    /** Reads already-flushed events without advancing the looper or sending another batch. */
    protected fun readScreenNames(): List<String> {
        return E2ETestUtils.eventsFromBatchRequests(E2ETestUtils.collectAllRequests(mockWebServer))
            .filter { it.optString("event_name") == "screen_view" }
            .map { it.getJSONObject("properties").getString("screen_name") }
    }
}
