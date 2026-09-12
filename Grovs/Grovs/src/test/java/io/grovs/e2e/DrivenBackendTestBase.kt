package io.grovs.e2e

import android.app.Application
import android.content.Intent
import android.os.Looper
import io.grovs.Grovs
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.ConsentBackend
import io.grovs.service.ConsentRequestExecutor
import io.grovs.service.DrivenDispatcher
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Shared harness: a routed backend, virtual retry waits through [DrivenDispatcher] so no test
 * sleeps through a real backoff, and one activity controller so a foreground is a real stop/start.
 */
abstract class DrivenBackendTestBase {

    protected lateinit var application: Application
    protected lateinit var backend: ConsentBackend
    protected lateinit var driver: DrivenDispatcher
    protected lateinit var context: GrovsContext

    protected val device = "device_for_vendor_id"
    protected val authenticate = "authenticate"
    protected val payload = "data_for_device_and_url"
    protected val fingerprint = "data_for_device"
    protected val attributes = "visitor_attributes"
    protected val notifications = "notifications_for_device"
    protected val authOk = """{"linksquared":"test-grovs-id","uri_scheme":"testapp"}"""

    private var host: ActivityController<TestActivity>? = null
    private var hostStarted = false

    @Before
    fun setUpHarness() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK recovery tests")
        E2ETestUtils.setupMockScreenResolution()
        DebugLogger.instance.logLevel = LogLevel.INFO
        // Deterministic retry timing. It is a global seam, so teardown restores it.
        ConsentRequestExecutor.retryJitterMs = { 0 }
        backend = ConsentBackend().apply {
            phase = "enabled"
            respond(device, """{"last_seen":null}""")
            respond(fingerprint, """{"link":null,"data":null}""")
            respond("notifications_to_display_automatically", """{"notifications":[]}""")
            respond("events/batch", """{"accepted":50,"rejected":0,"errors":[]}""")
            respond("clipboard_status", """{"clipboard_active":false}""")
        }
        driver = DrivenDispatcher()
        context = GrovsContext(serialDispatcher = driver)
        E2ETestUtils.installGrovsContext(context)
    }

    @After
    fun tearDownHarness() {
        val retirement = context.consent.retireConfiguration(enabled = false)
        driver.runUntil("the configuration's cleanup", onEachPass = { idleMain() }) {
            retirement.cleanup.isCompleted
        }
        E2ETestUtils.resetGrovsSingleton()
        DebugLogger.instance.logLevel = LogLevel.ERROR
        ConsentRequestExecutor.retryJitterMs = ConsentRequestExecutor.defaultJitterMs
        backend.close()
    }

    protected fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    protected fun pump() {
        driver.runCurrent()
        idleMain()
        driver.runCurrent()
    }

    /** Runs due work until [condition] holds. Does not move virtual time. */
    protected fun pumpUntil(description: String, condition: () -> Boolean) =
        driver.runUntil(description, onEachPass = { idleMain(); driver.runCurrent() }, condition = condition)

    /** Moves virtual time in one second steps until [condition] holds. */
    protected fun advanceUntil(description: String, timeoutMs: Long = 300_000, condition: () -> Boolean) {
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (condition()) return
            driver.advanceTimeBy(1_000)
            pump()
            Thread.sleep(2)
            elapsed += 1_000
        }
        if (!condition()) fail("timed out waiting for $description")
    }

    /** Moves virtual time forward unconditionally. */
    protected fun advance(totalMs: Long) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            driver.advanceTimeBy(1_000)
            pump()
            Thread.sleep(2)
            elapsed += 1_000
        }
    }

    internal fun manager() = E2ETestUtils.getGrovsManager() as GrovsManager

    protected fun configure(enabled: Boolean = true) = Grovs.configure(
        application, "test-api-key", useTestEnvironment = true, baseURL = backend.baseUrl,
        autoTrackScreenViews = false, clipboardDomains = null, enabled = enabled,
    )

    /**
     * One real foreground. The SDK's hook only fires when the started-activity count goes from zero
     * to one (`Grovs.kt:537`), so building a second activity on top of a running one fires nothing.
     * Passing [intent] starts a fresh activity carrying it, which is how a tapped link arrives.
     */
    protected fun foreground(intent: Intent? = null) {
        if (hostStarted) {
            host?.stop()
            pump()
            hostStarted = false
        }
        if (host == null || intent != null) {
            host = if (intent != null) {
                Robolectric.buildActivity(TestActivity::class.java, intent)
            } else {
                Robolectric.buildActivity(TestActivity::class.java)
            }.also { it.create() }
        }
        host?.start()
        hostStarted = true
        pump()
    }

    /** Home button: the started-activity count drops to zero, so the SDK sees a background. */
    protected fun background() {
        if (hostStarted) {
            host?.stop()
            hostStarted = false
        }
        pump()
    }
}
