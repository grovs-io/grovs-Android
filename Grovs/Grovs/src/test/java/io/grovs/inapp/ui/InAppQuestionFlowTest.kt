package io.grovs.inapp.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.Grovs
import io.grovs.GrovsInAppListenerAdapter
import io.grovs.R
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.TestActivity
import io.grovs.handlers.GrovsManager
import io.grovs.inapp.InAppCancellable
import io.grovs.inapp.InAppController
import io.grovs.inapp.InAppFileStore
import io.grovs.inapp.InAppHost
import io.grovs.inapp.model.InAppDevice
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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
class InAppQuestionFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = RuntimeEnvironment.getApplication()
    private val reports = mutableListOf<Pair<String, JsonObject>>()
    private val answered = mutableListOf<Triple<String, Any, String?>>()
    private var reviews = 0

    private fun stamp(millis: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private fun idle(seconds: Long = 3) = shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)

    /** c1 asks on app open; c2 follows an answer of c1. */
    private fun start(): Pair<InAppController, InAppDialogPresenter> {
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val images = InAppUiFixtures.images(folder.newFolder("img"))
        val actions = InAppActions(app, { activity }, reviewAvailable = true, review = { _, done -> reviews++; done() })
        val presenter = InAppDialogPresenter({ activity }, images, actions).also { it.register(app) }
        val asking = InAppUiFixtures.json("""{"triggers":[{"type":"app_open"}],"content":{"question":
            {"id":"q1","type":"stars","options":[],"comment":{"split":null,"low":"Why?","high":null},"thanks":null,"review_from":4}}}""")
        val following = InAppUiFixtures.json("""{"id":"c2","created_at":"2026-09-02T00:00:00Z",
            "triggers":[{"type":"event","name":"in_app_answered","filters":[{"field":"campaign_id","op":"eq","value":"c1"}]}],
            "content":{"title":"Thanks for rating"}}""")
        val body = JsonParser.parseString("""{"campaigns_changed_at":"a","user_changed_at":"b",
            "server_time":"${stamp(System.currentTimeMillis())}","store_url":null,"first_seen_at":"2026-08-01T00:00:00Z",
            "global_caps":{"session":10,"day":10},"campaigns":[$asking,$following],"test_messages":[]}""").asJsonObject
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
            report = { _, name, properties -> reports += name to properties },
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
        controller.settings.listener = object : GrovsInAppListenerAdapter() {
            override fun onInAppMessageAnswered(campaignId: String, value: Any, comment: String?) {
                answered += Triple(campaignId, value, comment)
            }
        }
        presenter.onIdle = { controller.showWaiting() }
        controller.start()
        controller.onAppOpen()
        idle()
        return controller to presenter
    }

    private fun tap(presenter: InAppDialogPresenter, tag: String) =
        presenter.shownWindow!!.dialog.window!!.decorView.findViewWithTag<View>(tag).performClick()

    @Test
    fun anAnswerAndItsCommentAreReportedAsIntegersAndToldToTheHost() {
        val (_, presenter) = start()
        tap(presenter, "grovs-in-app-answer-5")
        val answer = reports.single { it.first == "in_app_answered" }.second
        assertEquals("5", answer.get("value").toString())
        assertEquals("q1", answer.get("question_id").asString)
        assertEquals("stars", answer.get("type").asString)
        assertEquals("1", answer.get("revision").toString())
        (presenter.shownWindow!!.dialog.findViewById<EditText>(R.id.grovs_in_app_comment)).setText("  Love it ")
        presenter.shownWindow!!.dialog.findViewById<View>(R.id.grovs_in_app_send).performClick()
        idle()
        val comment = reports.single { it.first == "in_app_commented" }.second
        assertEquals("Love it", comment.get("comment").asString)
        assertEquals("5", comment.get("value").toString())
        assertEquals(listOf(Triple("c1", 5 as Any, "Love it" as String?)), answered)
        assertTrue(reports.none { it.first == "in_app_dismissed" })
    }

    @Test
    fun theFollowUpShowsOnlyAfterTheReviewHadItsTurn() {
        val (_, presenter) = start()
        tap(presenter, "grovs-in-app-answer-5")
        presenter.shownWindow!!.dialog.findViewById<View>(R.id.grovs_in_app_skip).performClick()
        idle()
        assertEquals(1, reviews)
        assertEquals("c2", presenter.shownSession!!.campaign.id)
    }

    @Test
    fun aLowAnswerShowsTheFollowUpRightAway() {
        val (_, presenter) = start()
        tap(presenter, "grovs-in-app-answer-2")
        presenter.shownWindow!!.dialog.findViewById<View>(R.id.grovs_in_app_skip).performClick()
        idle()
        assertEquals(0, reviews)
        assertEquals("c2", presenter.shownSession!!.campaign.id)
    }

    @Test
    fun closingBeforeAnsweringIsADismissal() {
        val (_, presenter) = start()
        presenter.shownWindow!!.dialog.findViewById<View>(R.id.grovs_in_app_close).performClick()
        idle()
        assertEquals("close_button", reports.single { it.first == "in_app_dismissed" }.second.get("reason").asString)
        assertTrue(reports.none { it.first == "in_app_answered" })
    }

    @Test
    fun configureConnectsTheReviewEndToTheManagersHost() {
        E2ETestUtils.resetGrovsSingleton()
        try {
            E2ETestUtils.setupTestApplication(app)
            configure()
            val presenter = presenter()
            val host = spyHost()

            presenter.onIdle()

            verify(exactly = 1) { host.screenFree() }
        } finally {
            E2ETestUtils.resetGrovsSingleton()
        }
    }

    @Test
    fun aReconfigureKeepsTheReviewEndOnTheNewHost() {
        E2ETestUtils.resetGrovsSingleton()
        try {
            E2ETestUtils.setupTestApplication(app)
            configure()
            val first = presenter()
            val closedHost = spyHost()
            configure()
            val second = presenter()
            val host = spyHost()
            assertNotSame(first, second)

            first.onIdle()
            second.onIdle()

            verify(exactly = 0) { closedHost.screenFree() }
            verify(exactly = 2) { host.screenFree() }
        } finally {
            E2ETestUtils.resetGrovsSingleton()
        }
    }

    private fun configure() {
        Grovs.configure(app, "in-app-idle-key", useTestEnvironment = true, baseURL = "http://127.0.0.1:9/")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun presenter(): InAppDialogPresenter =
        Grovs::class.java.getDeclaredField("inAppPresenter").apply { isAccessible = true }
            .get(E2ETestUtils.getGrovsInstance()!!) as InAppDialogPresenter

    /** Swaps the current manager's host for a spy, so a call to screenFree() can be seen. */
    private fun spyHost(): InAppHost {
        val manager = E2ETestUtils.getGrovsManager() as GrovsManager
        val field = GrovsManager::class.java.getDeclaredField("inApp").apply { isAccessible = true }
        return spyk(field.get(manager) as InAppHost).also { field.set(manager, it) }
    }
}
