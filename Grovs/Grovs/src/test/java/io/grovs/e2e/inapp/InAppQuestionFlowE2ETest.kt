package io.grovs.e2e.inapp

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.testing.FakeReviewManager
import com.google.gson.JsonObject
import io.grovs.Grovs
import io.grovs.R
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.choice
import io.grovs.e2e.contract.InAppCampaigns.event
import io.grovs.e2e.contract.InAppCampaigns.filter
import io.grovs.e2e.contract.InAppCampaigns.stars
import io.grovs.inapp.ui.InAppPlayReview
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppQuestionFlowE2ETest : InAppTestBase() {

    private var reviews = 0

    @After
    fun restoreReview() { InAppPlayReview.managerFactory = { ReviewManagerFactory.create(it) } }

    // The Play review seam: counts each review flow the SDK starts.
    private fun countReviews() { InAppPlayReview.managerFactory = { reviews++; FakeReviewManager(it) } }

    private fun questionCampaign(question: JsonObject) = InAppCampaigns.campaign().id("q1").priority(2).question(question).build()

    /** Starts with campaign "q1" asking [question] (plus [more]) and shows it. */
    private fun ask(question: JsonObject, vararg more: JsonObject) {
        start(questionCampaign(question), *more)
        Grovs.track("cart_viewed")
        pumpUntil("the question to show") { recorder.shown == listOf("q1") }
    }

    private fun onScreen(): Boolean = presenter().shownWindow != null

    private fun prompt() = view<TextView>(R.id.grovs_in_app_prompt).text.toString()

    private fun comment() = view<EditText>(R.id.grovs_in_app_comment)

    private fun showsText(text: String): Boolean {
        fun search(v: View): Boolean = (v is TextView && v.isShown && v.text.toString() == text) ||
            (v is ViewGroup && (0 until v.childCount).any { search(v.getChildAt(it)) })
        return search(root())
    }

    private fun properties(event: JSONObject): JSONObject = event.getJSONObject("properties")

    // shared README Questions, Answering: "It is reported at once as in_app_answered, before anything else is asked"
    @Test
    fun `I27 one tap answers and reports at once, before anything else is asked`() {
        ask(stars(comment = true, low = "Why?"))
        tapAnswer("4")
        val answered = properties(reportedWhen("in_app_answered").single())
        assertEquals("q1", answered.getString("campaign_id"))
        assertEquals("q", answered.getString("question_id"))
        assertEquals("stars", answered.getString("type"))
        assertEquals(4, answered.getInt("value"))
        assertTrue("the comment step is still on screen", onScreen())
        assertEquals("Why?", prompt())
    }

    // The backend wants a whole number: a 4.0 is refused
    @Test
    fun `I27 a star answer is sent as a whole number`() {
        ask(stars())
        tapAnswer("4")
        val value = properties(reportedWhen("in_app_answered").single()).get("value")
        assertTrue("value is $value (${value.javaClass})", value is Int || value is Long)
        assertEquals(4, (value as Number).toInt())
    }

    // shared README Questions, events: value is "the option's id for choice"
    @Test
    fun `I27 a choice answer reports the option id`() {
        ask(choice("a" to "Alpha", "b" to "Beta"))
        tapAnswer("b")
        val answered = properties(reportedWhen("in_app_answered").single())
        assertEquals("choice", answered.getString("type"))
        assertEquals("b", answered.getString("value"))
    }

    // shared README Questions, Once: "recorded as answered on the device and is not shown again"
    @Test
    fun `I27 an answered campaign never shows again, even after a restart`() {
        val control = InAppCampaigns.campaign().id("ctl").priority(1).build()
        ask(stars(), control)
        tapAnswer("5")
        pumpUntil("the question to close") { recorder.answered.size == 1 }

        Grovs.track("cart_viewed")
        pumpUntil("the control to show") { recorder.shown == listOf("q1", "ctl") }
        closeShown()

        restart()
        Grovs.track("cart_viewed")
        pumpUntil("the control to show after the restart") { recorder.shown == listOf("q1", "ctl", "ctl") }
    }

    // shared README Questions, comment: "an answer at or below it is asked low and one above it high"; "trimmed text"
    @Test
    fun `I28 low stars show the low prompt and Send reports the trimmed comment`() {
        ask(stars(comment = true, split = 3, low = "What went wrong?", high = "What did you like?"))
        tapAnswer("3")
        assertEquals("What went wrong?", prompt())
        comment().setText("  slow  ")
        view<View>(R.id.grovs_in_app_send).performClick(); pump()
        val commented = properties(reportedWhen("in_app_commented").single())
        assertEquals("slow", commented.getString("comment"))
        assertEquals("q", commented.getString("question_id"))
        assertEquals("stars", commented.getString("type"))
        assertEquals(3, commented.get("value"))
        pumpUntil("the answered callback") { recorder.answered.size == 1 }
        assertEquals(Triple<String, Any, String?>("q1", 3, "slow"), recorder.answered.single())
    }

    @Test
    fun `I28 high stars show the high prompt`() {
        ask(stars(comment = true, split = 3, low = "What went wrong?", high = "What did you like?"))
        tapAnswer("4")
        assertEquals("What did you like?", prompt())
    }

    // shared README Questions, Comment: "Send reports in_app_commented at once, before any thanks is shown"
    @Test
    fun `I28 Send reports the comment before the thanks closes the message`() {
        ask(stars(comment = true, low = "Why?", thanks = "Thank you!"))
        tapAnswer("2")
        comment().setText("ok")
        view<View>(R.id.grovs_in_app_send).performClick(); pump()
        assertEquals("ok", properties(reportedWhen("in_app_commented").single()).getString("comment"))
        assertTrue("the thanks is still on screen", onScreen() && showsText("Thank you!"))
    }

    // shared README Questions, Comment: "Skip and closing report nothing more"
    @Test
    fun `I28 skip reports no comment`() {
        ask(stars(comment = true, low = "Why?"))
        tapAnswer("1")
        comment().setText("typed but skipped")
        view<View>(R.id.grovs_in_app_skip).performClick(); pump()
        pumpUntil("the message to close") { recorder.answered.size == 1 }
        assertEquals("the answer was reported", 1, reportedWhen("in_app_answered").size)
        assertTrue(reported("in_app_commented").isEmpty())
        assertNull("skip gives the host no comment", recorder.answered.single().third)
    }

    // The backend refuses a comment that is blank after trimming
    @Test
    fun `I28 a blank Send reports no comment`() {
        ask(stars(comment = true, low = "Why?"))
        tapAnswer("1")
        comment().setText("   ")
        view<View>(R.id.grovs_in_app_send).performClick(); pump()
        if (onScreen()) tapClose()
        pumpUntil("the message to close") { recorder.answered.size == 1 }
        assertEquals("the answer was reported", 1, reportedWhen("in_app_answered").size)
        assertTrue(reported("in_app_commented").isEmpty())
    }

    // shared README Questions, Comment: "at most 500 UTF-16 units"; "a paste is clipped without splitting a character"
    @Test
    fun `I28 a pasted comment is clipped to 500 UTF-16 units without splitting a pair`() {
        ask(stars(comment = true, low = "Why?"))
        tapAnswer("3")
        comment().setText("a".repeat(498) + "😀😀")
        assertEquals("a".repeat(498) + "😀", comment().text.toString())
        comment().setText("a".repeat(499) + "😀😀")
        assertEquals("a".repeat(499), comment().text.toString())
        view<View>(R.id.grovs_in_app_send).performClick(); pump()
        assertEquals("a".repeat(499), properties(reportedWhen("in_app_commented").single()).getString("comment"))
    }

    // shared README Questions, Thanks: "shown for two seconds, then the message closes"; "Closing after the answer keeps the answer outcome"
    @Test
    fun `I29 thanks shows for 2 seconds, then an answered close calls answered once and no dismissal`() {
        ask(stars(thanks = "Thank you!"))
        tapAnswer("5")
        assertTrue("the thanks shows", showsText("Thank you!"))
        idleFor(1_500)
        assertTrue("still showing thanks", onScreen() && recorder.answered.isEmpty())
        idleUntil("closed after thanks", 1_500) { recorder.answered.size == 1 }
        assertEquals(Triple<String, Any, String?>("q1", 5, null), recorder.answered.single())
        assertTrue(recorder.dismissed.isEmpty())
        assertEquals("the answer was reported", 1, reportedWhen("in_app_answered").size)
        assertTrue(reported("in_app_dismissed").isEmpty())
    }

    // shared README Questions, Not answered: "a dismissal, reported as one, and the campaign may be shown again"
    @Test
    fun `I29 closing before answering is a dismissal and it asks again later`() {
        ask(stars())
        closeShown()
        assertEquals("q1", properties(reportedWhen("in_app_dismissed").single()).getString("campaign_id"))
        Grovs.track("cart_viewed")
        pumpUntil("asked again") { recorder.shown == listOf("q1", "q1") }
        assertTrue(reported("in_app_answered").isEmpty())
    }

    // shared README Questions, Follow-up: "Another campaign may use it as its trigger, with filters on those properties"
    @Test
    fun `I30 the answer triggers a follow-up once the first closes, by its filters`() {
        val five = InAppCampaigns.campaign().id("five").priority(4)
            .triggers(event("in_app_answered", filter("campaign_id", "eq", "q1"), filter("value", "eq", 5))).build()
        val four = InAppCampaigns.campaign().id("four").priority(3)
            .triggers(event("in_app_answered", filter("campaign_id", "eq", "q1"), filter("value", "eq", 4))).build()
        ask(stars(thanks = "Thanks"), five, four)
        tapAnswer("4")
        idleFor(1_000)
        assertEquals("the follow-up waits while the question is on screen", listOf("q1"), recorder.shown)
        idleUntil("the follow-up", 5_000) { recorder.shown == listOf("q1", "four") }
        assertEquals(1, recorder.answered.size)
        val order = recorder.calls.map { it.kind to it.campaignId }
        assertTrue("q1 closes before four shows: $order", order.indexOf("answered" to "q1") < order.indexOf("shown" to "four"))
    }

    // shared README Questions, Review: "at or above review_from ... asked for once the message has closed"
    @Test
    fun `I31 stars at review_from ask for a Play review after close`() {
        countReviews()
        ask(stars(reviewFrom = 4, thanks = "Thanks"))
        tapAnswer("4")
        idleFor(1_000)
        assertEquals("no review while the message is on screen", 0, reviews)
        idleUntil("review requested", 5_000) { reviews == 1 }
        assertNotNull(recorder.answered.singleOrNull())
    }

    @Test
    fun `I31 stars below review_from never ask for a review`() {
        countReviews()
        ask(stars(reviewFrom = 4))
        tapAnswer("3")
        pumpUntil("answered") { recorder.answered.size == 1 }
        idleFor(5_000)
        assertEquals(0, reviews)
    }
}
