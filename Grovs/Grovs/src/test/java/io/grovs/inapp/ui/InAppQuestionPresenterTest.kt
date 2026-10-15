package io.grovs.inapp.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import io.grovs.R
import io.grovs.e2e.TestActivity
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.model.InAppAnswer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppQuestionPresenterTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<TestActivity>
    private var current: Activity? = null
    private lateinit var presenter: InAppDialogPresenter
    private val outcomes = mutableListOf<InAppOutcome>()
    private val events = mutableListOf<String>()
    private var reviewAvailable = true

    private val stars = """{"id":"q1","type":"stars","options":[],
        "comment":{"split":3,"low":"What went wrong?","high":"What did you like?"},"thanks":null,"review_from":4}"""
    private val starsWithThanks = stars.replace(""""thanks":null""", """"thanks":"Thanks!"""")
    private val imm get() = app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val keyboard get() = shadowOf(imm)
    private fun raiseKeyboard() = imm.showSoftInput(controller.get().window.decorView, 0)

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        current = controller.get()
        presenter = build()
    }

    private fun build(): InAppDialogPresenter {
        val actions = InAppActions(app, { current }, reviewAvailable = reviewAvailable,
            review = { _, done -> events += "review"; done() })
        return InAppDialogPresenter({ current }, InAppUiFixtures.images(folder.newFolder()), actions).also {
            it.register(app)
            it.onIdle = { events += "idle" }
        }
    }

    @After
    fun tearDown() {
        presenter.dismiss()
        idle()
        presenter.unregister(app)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun present(template: String = "interstitial", isTest: Boolean = false, question: String = stars) {
        val campaign = InAppUiFixtures.campaign("""{"template":"$template","content":{"question":$question}}""")
        presenter.present(InAppUiFixtures.presentation(campaign, isTest = isTest)) {
            events += "outcome"
            outcomes += it
        }
        idle()
    }

    private fun view(id: Int): View = presenter.shownWindow!!.dialog.findViewById(id)
    private fun tap(tag: String) = presenter.shownWindow!!.dialog.window!!.decorView.findViewWithTag<View>(tag).performClick()

    private fun flags() = presenter.shownWindow!!.dialog.window!!.attributes.flags

    @Test
    fun theCommentStepAndItsTextSurviveRecreation() {
        present()
        tap("grovs-in-app-answer-5")
        (view(R.id.grovs_in_app_comment) as EditText).setText("Half typed")
        controller.recreate()
        current = controller.get()
        idle()
        assertTrue(outcomes.isEmpty())
        assertEquals("Half typed", (view(R.id.grovs_in_app_comment) as EditText).text.toString())
        view(R.id.grovs_in_app_send).performClick()
        idle()
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), "Half typed"), outcomes.single())
    }

    @Test
    fun aBannerTakesTheKeyboardOnlyForTheComment() {
        present(template = "footer")
        assertTrue(flags() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        tap("grovs-in-app-answer-2")
        assertEquals(0, flags() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(view(R.id.grovs_in_app_comment).isFocused)
        view(R.id.grovs_in_app_skip).performClick()
        idle()
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(2), null), outcomes.single())
    }

    @Test
    fun aBannerCommentStepStaysFocusableAfterRecreation() {
        present(template = "header")
        tap("grovs-in-app-answer-1")
        controller.recreate()
        current = controller.get()
        idle()
        assertEquals(0, flags() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    @Test
    fun aHighStarAnswerAsksForAReviewAfterTheReport() {
        present()
        tap("grovs-in-app-answer-4")
        view(R.id.grovs_in_app_skip).performClick()
        idle()
        assertEquals(listOf("outcome", "review", "idle"), events)
    }

    @Test
    fun theAppClosingAHighStarAnswerAsksForNoReviewButFreesTheScreen() {
        present()
        tap("grovs-in-app-answer-5")
        presenter.dismiss()
        idle()
        assertEquals(listOf("outcome", "idle"), events)
    }

    @Test
    fun aLowStarAnswerAsksForNoReview() {
        present()
        tap("grovs-in-app-answer-3")
        view(R.id.grovs_in_app_skip).performClick()
        idle()
        assertEquals(listOf("outcome"), events)
    }

    @Test
    fun aTestMessageNeverAsksForAReviewButFreesTheScreen() {
        present(isTest = true)
        tap("grovs-in-app-answer-5")
        view(R.id.grovs_in_app_skip).performClick()
        idle()
        assertEquals(listOf("outcome", "idle"), events)
    }

    @Test
    fun withoutPlayReviewTheScreenIsFreedAtOnce() {
        presenter.unregister(app)
        reviewAvailable = false
        presenter = build()
        present()
        tap("grovs-in-app-answer-5")
        view(R.id.grovs_in_app_skip).performClick()
        idle()
        assertEquals(listOf("outcome", "idle"), events)
    }

    @Test
    fun theThanksClosesAfterTwoSeconds() {
        val campaign = InAppUiFixtures.campaign(
            """{"content":{"question":{"id":"q1","type":"stars","options":[],"comment":null,"thanks":"Thanks!","review_from":null}}}""")
        presenter.present(InAppUiFixtures.presentation(campaign)) { outcomes += it }
        idle()
        tap("grovs-in-app-answer-5")
        shadowOf(Looper.getMainLooper()).idleFor(1_900, TimeUnit.MILLISECONDS)
        assertTrue(outcomes.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), null), outcomes.single())
    }

    @Test
    fun anUndismissibleQuestionClosesOnlyThroughItsAnswer() {
        val campaign = InAppUiFixtures.campaign(
            """{"content":{"dismissible":false,"question":{"id":"q1","type":"stars","options":[],"comment":null,"thanks":null,"review_from":null}}}""")
        presenter.present(InAppUiFixtures.presentation(campaign)) { outcomes += it }
        idle()
        presenter.shownWindow!!.dialog.onBackPressedDispatcher.onBackPressed()
        assertTrue(outcomes.isEmpty())
        tap("grovs-in-app-answer-3")
        idle()
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(3), null), outcomes.single())
    }

    @Test
    fun aBannerLetsTheKeyboardGoAgainForTheThanks() {
        present(template = "footer", question = starsWithThanks)
        tap("grovs-in-app-answer-2")
        view(R.id.grovs_in_app_skip).performClick()
        idle()
        assertTrue(outcomes.isEmpty())
        assertTrue(flags() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }

    @Test
    fun theThanksHidesTheKeyboardTheCommentRaised() {
        present(question = starsWithThanks)
        tap("grovs-in-app-answer-5")
        raiseKeyboard()
        view(R.id.grovs_in_app_skip).performClick()
        idle()
        assertTrue(outcomes.isEmpty())
        assertFalse(keyboard.isSoftInputVisible)
    }

    @Test
    fun closingDuringTheCommentHidesTheKeyboard() {
        present()
        tap("grovs-in-app-answer-5")
        raiseKeyboard()
        presenter.dismiss()
        idle()
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), null, byApp = true), outcomes.single())
        assertFalse(keyboard.isSoftInputVisible)
    }

    @Test
    fun aQuestionWithoutACommentLeavesTheAppsKeyboardAlone() {
        val question = """{"id":"q1","type":"stars","options":[],"comment":null,"thanks":"Thanks!","review_from":null}"""
        present(template = "footer", question = question)
        raiseKeyboard()
        tap("grovs-in-app-answer-5")
        idle()
        assertTrue(keyboard.isSoftInputVisible)
        shadowOf(Looper.getMainLooper()).idleFor(2_100, TimeUnit.MILLISECONDS)
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), null), outcomes.single())
        assertTrue(keyboard.isSoftInputVisible)
    }
}
