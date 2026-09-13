package io.grovs.e2e

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.handlers.ConsentController
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.ConsentBackend
import io.grovs.service.DrivenDispatcher
import io.grovs.service.useConsentController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Concurrent consent transitions from different threads. The consent controller hands each
 * revocation's cleanup to an executor synchronously, from inside `revoke()`, which is the one
 * deterministic point between "consent is withdrawn" and "setSDK(false) continues". A test
 * executor runs the competing call there, on a second thread, and waits for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentTransitionRaceE2ETest {

    private lateinit var application: Application
    private lateinit var backend: ConsentBackend
    private lateinit var driver: DrivenDispatcher
    private lateinit var context: GrovsContext

    /** Set right before the transition under test; the next cleanup hand-off consumes it. */
    private val enableDuringNextRevocation = AtomicBoolean(false)
    private var failureOnEnableThread: Throwable? = null

    private val interleavingCleanupExecutor = Executor { cleanup ->
        if (enableDuringNextRevocation.compareAndSet(true, false)) {
            thread { runCatching { Grovs.setSDK(true) }.onFailure { failureOnEnableThread = it } }.join()
        }
        cleanup.run()
    }

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent transition race tests")
        E2ETestUtils.setupMockScreenResolution()
        DebugLogger.instance.logLevel = LogLevel.INFO
        backend = ConsentBackend().apply {
            respond("device_for_vendor_id", """{"last_seen":null}""")
            respond("authenticate", """{"linksquared":"test-grovs-id","uri_scheme":"testapp"}""")
            respond("notifications_to_display_automatically", """{"notifications":[]}""")
            respond("events/batch", """{"accepted":50,"rejected":0,"errors":[]}""")
            respond("clipboard_status", """{"clipboard_active":false}""")
        }
        driver = DrivenDispatcher()
        context = GrovsContext(serialDispatcher = driver)
        E2ETestUtils.installGrovsContext(context)
        context.useConsentController(ConsentController(cleanupExecutor = interleavingCleanupExecutor))
    }

    @After
    fun tearDown() {
        val retirement = context.consent.retireConfiguration(enabled = false)
        driver.runUntil("the configuration's cleanup", onEachPass = { idleMain() }) { retirement.cleanup.isCompleted }
        E2ETestUtils.resetGrovsSingleton()
        DebugLogger.instance.logLevel = LogLevel.ERROR
        backend.close()
        failureOnEnableThread?.let { throw AssertionError("setSDK(true) failed on its thread", it) }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun pumpUntil(description: String, condition: () -> Boolean) =
        driver.runUntil(description, onEachPass = { idleMain(); driver.runCurrent() }, condition = condition)

    private fun manager() = E2ETestUtils.getGrovsManager() as GrovsManager

    @Test
    fun `an enable racing a disable before authentication finished does not deadlock the new authentication`() {
        Grovs.configure(
            application, "test-api-key", useTestEnvironment = true, baseURL = backend.baseUrl,
            autoTrackScreenViews = false, clipboardDomains = null, enabled = true,
        )
        assertTrue("authentication is still pending", E2ETestUtils.getAuthenticationJob()?.isActive == true)

        enableDuringNextRevocation.set(true)
        Grovs.setSDK(false)
        assertTrue("the enable ran during the revocation", !enableDuringNextRevocation.get())

        pumpUntil("the disable's cleanup and the new grant's authentication to finish") {
            context.consent.pendingWork().isCompleted && E2ETestUtils.getAuthenticationJob()?.isCompleted == true
        }
        assertEquals(GrovsManager.AuthenticationState.AUTHENTICATED, manager().authenticationState)
        assertTrue("consent is granted", context.consent.isEnabled)
    }
}
