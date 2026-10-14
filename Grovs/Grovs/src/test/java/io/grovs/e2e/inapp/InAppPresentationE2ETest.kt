package io.grovs.e2e.inapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import io.grovs.Grovs
import io.grovs.R
import io.grovs.e2e.TestActivity
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.button
import io.grovs.e2e.contract.InAppCampaigns.event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppPresentationE2ETest : InAppTestBase() {

    private fun rotateToLandscape() {
        RuntimeEnvironment.setQualifiers("+land")
        hostController().configurationChange(
            Configuration(application.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE },
        )
        pump()
    }

    /** The activity the message window is attached to. */
    private fun hostOf(): Activity? {
        var context: Context? = window().dialog.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        return context as? Activity
    }

    private fun windowFlags() = window().dialog.window!!.attributes.flags

    // "one presenter ... attached to the current resumed Activity"
    @Test
    fun `I18 every native template shows on the resumed activity`() {
        val image = "https://img.example/p.png"
        seedImage(image)
        val templates = listOf("cover", "interstitial", "half_interstitial", "header", "footer", "cover_image", "interstitial_image", "half_interstitial_image")
        start(*templates.mapIndexed { i, t ->
            InAppCampaigns.campaign().id(t).template(t).triggers(event("show_$i")).apply { if (t.endsWith("_image")) images(image, null) }.build()
        }.toTypedArray())
        templates.forEachIndexed { i, t ->
            Grovs.track("show_$i")
            pumpUntil("$t shown") { recorder.shown.lastOrNull() == t }
            assertNotNull("$t drew a window", presenter().shownWindow)
            assertSame("$t sits on the resumed launcher", launcher(), hostOf())
            closeShown()
            assertEquals(t to "close_button", recorder.dismissed.last())
        }
        assertEquals(templates, recorder.shown)
    }

    // "first button positive, second neutral, Close negative"; Close added "when dismissible and close_button is not false"
    @Test
    fun `I18 I23 the alert template uses a platform alert with Close as the negative button`() {
        showOne(InAppCampaigns.campaign().template("alert").buttons(button("ok", "OK"), button("later", "Later")).build())
        val alert = alert()
        assertTrue(alert.isShowing)
        assertEquals("OK", alert.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        assertEquals("Later", alert.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString())
        assertEquals(View.VISIBLE, alert.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)
        alert.getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); pump()
        pumpUntil("closed") { recorder.dismissed.isNotEmpty() }
        assertEquals("c1" to "close_button", recorder.dismissed.single())
    }

    // README: "An alert with no button always has Close, because the system alert has no tap action", even with close_button false
    @Test
    fun `I23 an alert with no button still has Close even when close_button is false`() {
        showOne(InAppCampaigns.campaign().template("alert").closeButton(false).build())
        val close = alert().getButton(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(View.VISIBLE, close.visibility)
        close.performClick(); pump()
        pumpUntil("Close closes it") { recorder.dismissed.isNotEmpty() }
        assertEquals("c1" to "close_button", recorder.dismissed.single())
    }

    // "if blocked, keep it as the single waiting message"; "One message at a time"
    @Test
    fun `I19 one message at a time and a waiting higher priority shows after`() {
        start(
            InAppCampaigns.campaign().id("first").priority(1).triggers(event("a")).build(),
            InAppCampaigns.campaign().id("second").priority(5).triggers(event("b")).build(),
        )
        Grovs.track("a"); pumpUntil("first") { recorder.shown == listOf("first") }
        Grovs.track("b"); settle(2_000)
        assertEquals("waits while one is on screen", listOf("first"), recorder.shown)
        closeShown()
        pumpUntil("the waiting one") { recorder.shown == listOf("first", "second") }
        assertEquals(listOf("first", "second"), reportedWhen("in_app_viewed", 2).map { it.getJSONObject("properties").getString("campaign_id") })
    }

    // banners: "FLAG_NOT_TOUCH_MODAL so touches outside reach the app"
    @Test
    fun `I20 banner windows let touches outside pass through`() {
        start(
            InAppCampaigns.campaign().id("header").template("header").triggers(event("h")).build(),
            InAppCampaigns.campaign().id("interstitial").triggers(event("i")).build(),
        )
        Grovs.track("h"); pumpUntil("header shown") { recorder.shown == listOf("header") }
        assertTrue("header passes outside touches", windowFlags() and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        closeShown()
        Grovs.track("i"); pumpUntil("interstitial shown") { recorder.shown == listOf("header", "interstitial") }
        assertEquals("interstitial keeps touches", 0, windowFlags() and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    // back: "Dismissible: close with close_button"
    @Test
    fun `I21 back closes a dismissible message with close_button`() {
        showOne(InAppCampaigns.campaign().build())
        pressBack()
        pumpUntil("closed") { recorder.dismissed.isNotEmpty() }
        assertEquals("c1" to "close_button", recorder.dismissed.single())
        assertEquals("close_button", reportedWhen("in_app_dismissed").single().getJSONObject("properties").getString("reason"))
    }

    // README: "dismissible false: No close button is drawn whatever close_button says ... A button or the tap action closes it"
    @Test
    fun `I21 I22 a non-dismissible message ignores back and has no close button`() {
        showOne(InAppCampaigns.campaign().dismissible(false).closeButton(true).buttons(button("ok", "OK")).build())
        pressBack(); settle(1_000)
        assertTrue(recorder.dismissed.isEmpty())
        assertNotNull("still on screen", presenter().shownWindow)
        assertTrue("no close view", root().findViewById<View>(R.id.grovs_in_app_close)?.visibility != View.VISIBLE)
        tapButton("ok")
        pumpUntil("the button closes it") { recorder.clicked.isNotEmpty() }
        assertEquals("c1", recorder.clicked.single().first)
        assertEquals("ok", recorder.clicked.single().second)
        assertEquals("a button close is a click, not a dismissal", 0, reported("in_app_dismissed").size)
    }

    // "close_button: false hides the button only"
    @Test
    fun `I22 close_button false hides only the button`() {
        showOne(InAppCampaigns.campaign().closeButton(false).build())
        assertTrue(root().findViewById<View>(R.id.grovs_in_app_close)?.visibility != View.VISIBLE)
        pressBack()
        pumpUntil("still dismissible by back") { recorder.dismissed.isNotEmpty() }
        assertEquals("c1" to "close_button", recorder.dismissed.single())
    }

    // activity changes: "dismisses the Dialog silently (no outcome) and rebuilds it on the new Activity"
    @Test
    fun `I24 an activity change moves the message and reports nothing`() {
        showOne(InAppCampaigns.campaign().build())
        assertSame(launcher(), hostOf())
        // The new activity starts before the launcher stops, so the app never goes to the background.
        val second = Robolectric.buildActivity(TestActivity::class.java).create().start()
        background()
        second.resume()
        pump(); settle(1_000)
        assertTrue(recorder.dismissed.isEmpty())
        assertSame("the message now sits on the new activity", second.get(), hostOf())
        assertTrue(window().dialog.isShowing)
        assertEquals("control: the view was reported", 1, reportedWhen("in_app_viewed").size)
        assertEquals(0, reported("in_app_dismissed").size)
        assertEquals(listOf("c1"), recorder.shown)
    }

    // "recreation on rotation ... rebuilds it on the new Activity from its state" (step, answer, comment text)
    @Test
    fun `I24 a configuration change keeps the question step and comment`() {
        showOne(InAppCampaigns.campaign().question(InAppCampaigns.stars(comment = true, low = "Why?", high = "Nice!")).build())
        tapAnswer("2")
        view<EditText>(R.id.grovs_in_app_comment).setText("half typed")
        val before = launcher()
        rotateToLandscape()
        assertTrue("the launcher was recreated", before !== launcher())
        assertSame("the message moved to the new launcher", launcher(), hostOf())
        assertEquals("still the comment step", "Why?", view<TextView>(R.id.grovs_in_app_prompt).text.toString())
        assertEquals("half typed", view<EditText>(R.id.grovs_in_app_comment).text.toString())
        assertTrue(recorder.dismissed.isEmpty())
        assertEquals(listOf("c1"), recorder.shown)
        view<View>(R.id.grovs_in_app_send).performClick(); pump()
        pumpUntil("the answer to settle") { recorder.answered.isNotEmpty() }
        assertEquals(Triple("c1", 2 as Any, "half typed"), recorder.answered.single())
        val commented = reportedWhen("in_app_commented").single().getJSONObject("properties")
        assertEquals(2, commented.get("value"))
        assertEquals("half typed", commented.getString("comment"))
        assertEquals("the answer was reported once, before the rotation", 1, reported("in_app_answered").size)
        assertTrue(recorder.dismissed.isEmpty())
    }

    // README Orientation: "closes, reported as in_app_dismissed with reason orientation"
    @Test
    fun `I25 turning into a disallowed orientation closes with orientation`() {
        showOne(InAppCampaigns.campaign().orientation("portrait").build())
        rotateToLandscape()
        pumpUntil("closed") { recorder.dismissed.isNotEmpty() }
        assertEquals("c1" to "orientation", recorder.dismissed.single())
        assertEquals("orientation", reportedWhen("in_app_dismissed").single().getJSONObject("properties").getString("reason"))
    }

    // README Orientation: "The SDK never rotates the device or changes the app's orientations"
    @Test
    fun `I25 the SDK never sets requestedOrientation`() {
        start(InAppCampaigns.campaign().orientation("portrait").build())
        val before = launcher().requestedOrientation
        Grovs.track("cart_viewed")
        pumpUntil("the message to show") { recorder.shown == listOf("c1") }
        assertEquals(before, launcher().requestedOrientation)
        assertEquals(before, hostOf()!!.requestedOrientation)
    }
}
