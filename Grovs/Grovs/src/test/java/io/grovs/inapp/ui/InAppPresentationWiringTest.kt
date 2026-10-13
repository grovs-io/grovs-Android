package io.grovs.inapp.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.Grovs
import io.grovs.R
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.TestActivity
import io.grovs.inapp.InAppCancellable
import io.grovs.inapp.InAppController
import io.grovs.inapp.InAppFileStore
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.model.InAppDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppPresentationWiringTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = RuntimeEnvironment.getApplication()

    private fun stamp(millis: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    @Test
    fun aSyncedAppOpenCampaignIsDrawnAndItsCloseIsReported() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val images = InAppUiFixtures.images(folder.newFolder("img"))
        val actions = InAppActions(app, { activity }, reviewAvailable = false)
        val presenter = InAppDialogPresenter({ activity }, images, actions).also { it.register(app) }
        val campaign = InAppUiFixtures.json("""{"triggers":[{"type":"app_open"}]}""")
        val now = System.currentTimeMillis()
        val body = JsonParser.parseString("""{"campaigns_changed_at":"a","user_changed_at":"b",
            "server_time":"${stamp(now)}","store_url":null,"first_seen_at":"2026-08-01T00:00:00Z",
            "global_caps":{"session":10,"day":10},"campaigns":[$campaign],"test_messages":[]}""").asJsonObject
        val reported = mutableListOf<String>()
        val handler = Handler(Looper.getMainLooper())
        val controller = InAppController(InAppController.Dependencies(
            store = InAppFileStore(folder.newFolder("store"), "key"),
            presenter = presenter,
            images = images,
            device = {
                InAppDevice("android", "2.1.0", "14", "en", null, 0, JsonObject(), actions.pushPermission(),
                    emptyList(), actions.unsupportedActions())
            },
            sync = { _, done -> done(true, body) },
            report = { _, name, _ -> reported += name },
            sessionId = { "s1" },
            canRun = { true },
            isActive = { true },
            schedule = { delay, work ->
                val task = Runnable { work() }
                handler.postDelayed(task, delay)
                object : InAppCancellable { override fun cancel() = handler.removeCallbacks(task) }
            },
            sdkVersion = "test",
        ))
        try {
            controller.start()
            controller.onAppOpen()
            shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
            val window = presenter.shownWindow!!
            assertTrue(window.dialog.isShowing)
            window.dialog.findViewById<View>(R.id.grovs_in_app_close).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("in_app_viewed", "in_app_dismissed"), reported)
        } finally {
            controller.stop()
            presenter.unregister(app)
        }
    }

    @Test
    fun configureDrawsWithTheDialogPresenter() {
        E2ETestUtils.resetGrovsSingleton()
        try {
            E2ETestUtils.setupTestApplication(app)
            configure()
            val grovs = E2ETestUtils.getGrovsInstance()!!
            assertTrue(presenterOf(grovs) is InAppDialogPresenter)
            E2ETestUtils.resetGrovsSingleton()
            assertEquals(null, presenterOf(grovs))
        } finally {
            E2ETestUtils.resetGrovsSingleton()
        }
    }

    @Test
    fun reconfigureReleasesThePreviousPresenter() {
        E2ETestUtils.resetGrovsSingleton()
        try {
            E2ETestUtils.setupTestApplication(app)
            configure()
            val grovs = E2ETestUtils.getGrovsInstance()!!
            val first = presenterOf(grovs) as InAppDialogPresenter
            Robolectric.buildActivity(TestActivity::class.java).setup()
            shadowOf(Looper.getMainLooper()).idle()
            val outcomes = mutableListOf<InAppOutcome>()
            assertTrue(first.present(InAppUiFixtures.presentation(InAppUiFixtures.campaign())) { outcomes += it })

            configure()

            val second = presenterOf(grovs)
            assertTrue(second is InAppDialogPresenter)
            assertNotSame(first, second)
            assertEquals(listOf(InAppOutcome.Dismissed("app")), outcomes)
            assertNull(first.shownWindow)
            assertFalse(lifecycleCallbacks().contains(first))
            assertTrue(lifecycleCallbacks().contains(second))
        } finally {
            E2ETestUtils.resetGrovsSingleton()
        }
    }

    private fun configure() {
        Grovs.configure(app, "in-app-wiring-key", useTestEnvironment = true, baseURL = "http://127.0.0.1:9/")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun presenterOf(grovs: Any): Any? =
        Grovs::class.java.getDeclaredField("inAppPresenter").apply { isAccessible = true }.get(grovs)

    private fun lifecycleCallbacks(): List<*> =
        Application::class.java.getDeclaredField("mActivityLifecycleCallbacks").apply { isAccessible = true }.get(app) as List<*>
}
