package io.grovs.e2e

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import io.grovs.Grovs
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.DrivenDispatcher
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Validation of two reported field issues, both starting from a JSON 500 on /authenticate at first
 * launch:
 *   1. (fixed by the bounded retry budget) a JSON 500 used to be treated as terminal instead of
 *      retried, so the SDK stayed unauthenticated even after the backend recovered. It now retries
 *      like any other 5xx and authenticates once the backend heals within the budget.
 *   2. a link tapped during the outage still never arrives, even once that later recovery
 *      authenticates the SDK: nothing replays a lookup that failed for an unrelated reason.
 *
 * Mirrors ConsentRetryE2ETest's harness (DrivenDispatcher for virtual retry waits), but the backend
 * answers with a real HTTP 500 carrying a JSON error body rather than dropping the connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackendOutageValidationTest {

    private lateinit var application: Application
    private lateinit var driver: DrivenDispatcher
    private lateinit var context: GrovsContext
    private lateinit var server: MockWebServer

    /** Bodies keyed by path suffix; when [failing] is set, /authenticate answers 500 instead. */
    private val seen = CopyOnWriteArrayList<String>()

    @Volatile
    private var failing = true

    /** The 500 body. Null means "500 with an empty body" (the contrast case). */
    @Volatile
    private var failureBody: String? = """{"error":"internal server error"}"""

    private fun count(suffix: String) = seen.count { it.endsWith(suffix) }

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK backend outage validation")
        E2ETestUtils.setupMockScreenResolution()
        DebugLogger.instance.logLevel = LogLevel.INFO

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                seen += path
                fun ok(body: String) = MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json").setBody(body)
                return when {
                    path.endsWith("authenticate") && failing ->
                        MockResponse().setResponseCode(500)
                            .setHeader("Content-Type", "application/json")
                            .setBody(failureBody ?: "")
                    path.endsWith("authenticate") ->
                        ok("""{"linksquared":"test-grovs-id","uri_scheme":"testapp"}""")
                    path.endsWith("device_for_vendor_id") -> ok("""{"last_seen":null}""")
                    path.endsWith("data_for_device_and_url") -> ok("""{"link":"campaign-link","data":{"k":"v"}}""")
                    path.endsWith("clipboard_status") -> ok("""{"clipboard_active":false}""")
                    path.endsWith("notifications_to_display_automatically") -> ok("""{"notifications":[]}""")
                    else -> ok("{}")
                }
            }
        }
        server.start()

        driver = DrivenDispatcher()
        context = GrovsContext(serialDispatcher = driver)
        E2ETestUtils.installGrovsContext(context)
    }

    @After
    fun tearDown() {
        val retirement = context.consent.retireConfiguration(enabled = false)
        driver.runUntil("the configuration's cleanup", onEachPass = { idleMain() }) { retirement.cleanup.isCompleted }
        E2ETestUtils.resetGrovsSingleton()
        DebugLogger.instance.logLevel = LogLevel.ERROR
        server.shutdown()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun pump() {
        driver.runCurrent()
        idleMain()
        driver.runCurrent()
    }

    private fun pumpUntil(description: String, condition: () -> Boolean) =
        driver.runUntil(description, onEachPass = { idleMain(); driver.runCurrent() }, condition = condition)

    private fun manager() = E2ETestUtils.getGrovsManager() as GrovsManager

    private fun configure() = Grovs.configure(
        application, "test-api-key", useTestEnvironment = true, baseURL = server.url("/").toString(),
        autoTrackScreenViews = false, clipboardDomains = null, enabled = true,
    )

    @Test
    fun `issue 1 - a JSON 500 at first launch is retried and authentication recovers once the backend heals`() {
        configure()
        pumpUntil("the first authenticate attempt") { count("authenticate") >= 1 }
        pumpUntil("the failed attempt to enter its retry wait") {
            manager().authenticationState == GrovsManager.AuthenticationState.RETRYING
        }

        assertEquals(
            "a JSON 500 is retried like any other 5xx, not treated as terminal",
            GrovsManager.AuthenticationState.RETRYING,
            manager().authenticationState,
        )

        // The backend recovers well inside the bounded retry budget (2s + 4s + 8s of waits). Fine
        // grained steps with a short real sleep, matching the "contrast" test below: each retry is a
        // real network round trip on a background thread, and a coarse virtual-time jump can outrun
        // it before its completion is dispatched back.
        failing = false
        var elapsed = 0L
        while (elapsed < 120_000 && manager().authenticationState != GrovsManager.AuthenticationState.AUTHENTICATED) {
            driver.advanceTimeBy(1_000)
            pump()
            Thread.sleep(5)
            elapsed += 1_000
        }

        assertEquals(
            "authentication recovers once the backend heals, within the bounded retry budget",
            GrovsManager.AuthenticationState.AUTHENTICATED,
            manager().authenticationState,
        )
    }

    @Test
    fun `issue 2 - a link tapped during a JSON 500 never resolves during the recovery window`() {
        configure()
        pumpUntil("the first authenticate attempt") { count("authenticate") >= 1 }
        pump()

        // The user taps a campaign link while the backend is down.
        val link = "https://demo.sqd.link/backend-outage"
        val activity = Robolectric.buildActivity(TestActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        activity.create().start()
        pump()

        // The backend recovers, well inside a 30 second window.
        failing = false
        repeat(6) { driver.advanceTimeBy(5_000); pump() }

        assertEquals(
            "the tapped link is never sent to the backend for resolution",
            0,
            count("data_for_device_and_url"),
        )
        assertNull("no deeplink is ever delivered to the host app", Grovs.openedLinkDetails)
    }

    @Test
    fun `contrast - a 500 with an empty body also retries and recovers, same as a JSON body`() {
        failureBody = null
        configure()
        pumpUntil("authentication to enter its retry wait") {
            manager().authenticationState == GrovsManager.AuthenticationState.RETRYING
        }

        // The retry wait is virtual, so time has to be advanced for the retry to fire at all.
        failing = false
        var elapsed = 0L
        while (elapsed < 120_000 && manager().authenticationState != GrovsManager.AuthenticationState.AUTHENTICATED) {
            driver.advanceTimeBy(1_000)
            pump()
            Thread.sleep(5)
            elapsed += 1_000
        }

        assertEquals(
            "an empty-body 500 retries and recovers once the backend is healthy",
            GrovsManager.AuthenticationState.AUTHENTICATED,
            manager().authenticationState,
        )
    }

    @Test
    fun `control - with a healthy backend the same tapped link resolves and is delivered`() {
        failing = false
        configure()
        pumpUntil("authentication") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }

        val link = "https://demo.sqd.link/backend-outage"
        val activity = Robolectric.buildActivity(TestActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        activity.create().start()
        pumpUntil("the link lookup") { count("data_for_device_and_url") >= 1 }
        pump()

        // The same harness, same intent, same activity start: the only difference from the issue 2
        // test is that authentication succeeded. So the zero there is caused by the failed
        // authentication, not by the harness never exercising the link path.
        assertEquals(
            "an authenticated SDK does ask the backend to resolve the tapped link",
            1,
            count("data_for_device_and_url"),
        )
    }
}
