package io.grovs.inapp.ui

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import io.grovs.R
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.model.InAppAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InAppQuestionViewTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var activity: Activity
    private val outcomes = mutableListOf<InAppOutcome>()
    private val answers = mutableListOf<InAppAnswer>()
    private val width = 393
    private val height = 852

    private val stars = """{"id":"q1","type":"stars","options":[],
        "comment":{"split":3,"low":"What went wrong?","high":"What did you like?"},"thanks":"Thank you!","review_from":4}"""
    private val choice = """{"id":"q2","type":"choice","options":[{"id":"o1","text":"Yes"},
        {"id":"o2","text":"A rather long option that needs more than one line to be read whole"}],
        "comment":null,"thanks":null,"review_from":null}"""

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    private fun make(question: String, template: String = "interstitial", title: String? = "Hello",
                     message: String? = "Welcome", w: Int = width, h: Int = height): Pair<InAppCardView, InAppMessageSession> {
        val campaign = InAppUiFixtures.campaign("""{"template":"$template","content":{"question":$question}}""")
        val presentation = InAppUiFixtures.presentation(campaign, title = title, message = message)
            .copy(onAnswer = { answers += it })
        lateinit var view: InAppCardView
        val session = InAppMessageSession(presentation, schedule = { _, _ ->
            object : io.grovs.inapp.InAppCancellable { override fun cancel() {} }
        }) { outcomes += it }
        view = InAppCardView(activity, session, InAppUiFixtures.images(folder.newFolder()), night = false)
        session.redraw = { view.rebuild(); lay(view, w, h) }
        view.applyInsets(Insets.of(0, 24, 0, 48), 0)
        lay(view, w, h)
        return view to session
    }

    private fun lay(view: View, w: Int = width, h: Int = height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    /** Scrolls [target]'s scroller to its end; true when [target] then sits whole inside it, above [floor]. */
    private fun reachable(root: View, target: View, floor: Int): Boolean {
        var node = target.parent as View
        while (node !is ScrollView) node = node.parent as View
        node.scrollTo(0, node.getChildAt(0).height - node.height)
        val viewport = bounds(root, node)
        val shown = bounds(root, target)
        return viewport.bottom <= floor && shown.top >= viewport.top && shown.bottom <= viewport.bottom
    }

    private fun bounds(root: View, view: View): Rect {
        var x = 0
        var y = 0
        var node: View? = view
        while (node != null && node !== root) {
            val parent = node.parent as? View
            x += node.left - (parent?.scrollX ?: 0)
            y += node.top - (parent?.scrollY ?: 0)
            node = parent
        }
        return Rect(x, y, x + view.width, y + view.height)
    }

    @Test
    fun starsAreFiveButtonsAndOnlyTheFirstTapCounts() {
        val (view, _) = make(stars)
        val labels = (1..5).map { view.findViewWithTag<View>("grovs-in-app-answer-$it").contentDescription.toString() }
        assertEquals(listOf("1 of 5", "2 of 5", "3 of 5", "4 of 5", "5 of 5"), labels)
        view.findViewWithTag<View>("grovs-in-app-answer-4").performClick()
        assertNull(view.findViewWithTag<View>("grovs-in-app-answer-2"))
        assertEquals(listOf<InAppAnswer>(InAppAnswer.Stars(4)), answers)
    }

    @Test
    fun aChoiceIsOneFullWidthButtonPerOptionInOrder() {
        val (view, _) = make(choice)
        val first = view.findViewWithTag<TextView>("grovs-in-app-answer-o1")
        val second = view.findViewWithTag<TextView>("grovs-in-app-answer-o2")
        assertEquals("Yes", first.text)
        assertEquals(first.width, second.width)
        assertTrue(bounds(view, second).top > bounds(view, first).bottom)
        assertTrue(second.lineCount > 1)
        second.performClick()
        assertEquals(InAppAnswer.Option("o2"), answers.single())
    }

    @Test
    fun theQuestionSitsUnderTheText() {
        val (view, _) = make(stars)
        val text = bounds(view, view.findViewById(R.id.grovs_in_app_text))
        val area = bounds(view, view.findViewById(R.id.grovs_in_app_question))
        assertTrue(area.top >= text.bottom)
    }

    @Test
    fun aQuestionWithNoTextStartsBelowTheCloseButton() {
        val (view, _) = make(stars, title = null, message = null)
        val area = view.findViewById<View>(R.id.grovs_in_app_question)
        assertEquals(48, area.paddingTop)
        val close = bounds(view.card, view.findViewById(R.id.grovs_in_app_close))
        val firstStar = bounds(view.card, view.findViewWithTag("grovs-in-app-answer-1"))
        assertTrue(firstStar.top >= close.bottom - 10)
    }

    @Test
    fun theCommentStepShowsThePromptABoxAndSendAndSkip() {
        val (view, _) = make(stars)
        view.findViewWithTag<View>("grovs-in-app-answer-5").performClick()
        assertEquals("What did you like?", view.findViewById<TextView>(R.id.grovs_in_app_prompt).text)
        val box = view.findViewById<EditText>(R.id.grovs_in_app_comment)
        assertEquals(88, box.layoutParams.height)
        assertNotNull(view.findViewById<View>(R.id.grovs_in_app_send))
        assertNotNull(view.findViewById<View>(R.id.grovs_in_app_skip))
        assertEquals("Hello", view.findViewById<TextView>(R.id.grovs_in_app_title).text)
    }

    @Test
    fun typingIsKeptInTheSession() {
        val (view, session) = make(stars)
        view.findViewWithTag<View>("grovs-in-app-answer-5").performClick()
        view.findViewById<EditText>(R.id.grovs_in_app_comment).setText("Great app")
        assertEquals("Great app", (session.step as InAppStep.Comment).text)
    }

    @Test
    fun aPasteOverTheLimitKeepsWhatFits() {
        val (view, session) = make(stars)
        view.findViewWithTag<View>("grovs-in-app-answer-5").performClick()
        val box = view.findViewById<EditText>(R.id.grovs_in_app_comment)
        box.setText("x".repeat(400))
        box.text.append("y".repeat(99) + "😀" + "z".repeat(100))
        assertEquals(499, box.text.length)
        assertTrue(box.text.none { Character.isSurrogate(it) })
        assertEquals(499, (session.step as InAppStep.Comment).text.length)
    }

    @Test
    fun sendShowsTheThanksInPlaceOfTheText() {
        val (view, _) = make(stars)
        view.findViewWithTag<View>("grovs-in-app-answer-5").performClick()
        view.findViewById<EditText>(R.id.grovs_in_app_comment).setText("Great")
        view.findViewById<View>(R.id.grovs_in_app_send).performClick()
        assertEquals("Thank you!", view.findViewById<TextView>(R.id.grovs_in_app_title).text)
        assertNull(view.findViewById(R.id.grovs_in_app_message))
        assertNull(view.findViewById(R.id.grovs_in_app_question))
    }

    @Test
    fun aBannerCommentBoxIsShorter() {
        val (view, _) = make(stars, template = "footer")
        view.findViewWithTag<View>("grovs-in-app-answer-2").performClick()
        assertEquals(64, view.findViewById<EditText>(R.id.grovs_in_app_comment).layoutParams.height)
    }

    @Test
    fun theCloseAfterAnAnswerReportsTheAnswer() {
        val (view, _) = make(stars)
        view.findViewWithTag<View>("grovs-in-app-answer-2").performClick()
        view.findViewById<View>(R.id.grovs_in_app_close).performClick()
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(2), null), outcomes.single())
    }

    @Test
    fun everyTemplateKeepsTheQuestionInsideTheScreen() {
        for (template in listOf("cover", "interstitial", "half_interstitial", "header", "footer")) {
            val (view, _) = make(stars, template = template)
            val area = bounds(view, view.findViewById(R.id.grovs_in_app_question))
            assertTrue(template, area.top >= 0 && area.bottom <= height && area.right <= width)
        }
    }

    @Test
    fun inAShortWindowTheLastOptionCanBeScrolledIntoView() {
        val options = (1..5).joinToString(",") { """{"id":"$it","text":"Option $it"}""" }
        val five = """{"id":"q3","type":"choice","options":[$options],"comment":null,"thanks":null,"review_from":null}"""
        val (view, _) = make(five, w = 640, h = 240)
        assertTrue(reachable(view, view.findViewWithTag("grovs-in-app-answer-5"), floor = 240))
    }

    @Test
    fun inLandscapeWithTheKeyboardUpSendCanBeScrolledIntoView() {
        val (view, _) = make(stars, w = 640, h = 360)
        view.applyInsets(Insets.of(0, 24, 0, 0), 200)
        view.findViewWithTag<View>("grovs-in-app-answer-5").performClick()
        assertTrue(reachable(view, view.findViewById(R.id.grovs_in_app_send), floor = 360 - 200))
    }

    @Test
    fun aDarkCardDrawsLightStars() {
        val campaign = InAppUiFixtures.campaign(
            """{"content":{"colors":{"background":"#101010"},"question":$stars}}""")
        val session = InAppMessageSession(InAppUiFixtures.presentation(campaign)) {}
        val view = InAppCardView(activity, session, InAppUiFixtures.images(folder.newFolder()), night = false)
        val star = view.findViewWithTag<android.widget.ImageView>("grovs-in-app-answer-1")
        assertEquals(InAppStyle.WHITE, star.imageTintList!!.defaultColor)
    }

    @Test
    fun bannerAnswersAndCommentButtonsGetAFullTouchTarget() {
        val (view, _) = make(choice, template = "footer")
        val options = listOf("o1", "o2").map { view.findViewWithTag<View>("grovs-in-app-answer-$it") }
        assertTrue(view.card.smallTargets.containsAll(options))
        val (commenting, _) = make(stars, template = "footer")
        commenting.findViewWithTag<View>("grovs-in-app-answer-2").performClick()
        val buttons = listOf(R.id.grovs_in_app_send, R.id.grovs_in_app_skip).map { commenting.findViewById<View>(it) }
        assertTrue(commenting.card.smallTargets.containsAll(buttons))
    }

    @Test
    fun aBannerQuestionBesideTheCloseKeepsClearOfItAtTheEnd() {
        val (view, _) = make(stars, template = "footer", title = null, message = null)
        val area = view.findViewById<View>(R.id.grovs_in_app_question)
        assertEquals(36, area.paddingEnd)
        assertEquals(0, area.paddingStart)
    }

    @Test
    fun theCommentBoxIsLabelledByThePrompt() {
        val (view, _) = make(stars)
        view.findViewWithTag<View>("grovs-in-app-answer-1").performClick()
        assertEquals(R.id.grovs_in_app_comment, view.findViewById<TextView>(R.id.grovs_in_app_prompt).labelFor)
    }
}
