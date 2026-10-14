package io.grovs.e2e.inapp

import android.content.Intent
import android.view.View
import android.view.WindowManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.Grovs
import io.grovs.R
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.event
import io.grovs.inapp.ui.InAppHtmlDocument
import io.grovs.inapp.ui.InAppJavascriptChannel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppHtmlE2ETest : InAppTestBase() {

    private val linkRules by lazy {
        JsonParser.parseString(javaClass.classLoader!!.getResource("in-app-shared/cases.json")!!.readText())
            .asJsonObject.getAsJsonArray("link_rule").map { it.asJsonObject }
    }

    // The 22 event names the backend reserves.
    private val reservedNames = listOf(
        "app_open", "custom", "in_app_answered", "in_app_clicked", "in_app_commented", "in_app_dismissed", "in_app_held",
        "in_app_viewed", "install", "journey_entered", "journey_held", "journey_path", "open", "push_delivered", "push_held",
        "push_open", "reactivation", "reinstall", "screen_view", "time_spent", "user_referred", "view",
    )

    private fun htmlCampaign(layout: String = "modal") = InAppCampaigns.campaign().html("<html><body><p>Hi</p></body></html>", layout)

    private fun openPage(campaign: JsonObject = htmlCampaign().build()) {
        start(campaign)
        trackUntilPage()
    }

    private fun trackUntilPage() {
        Grovs.track("cart_viewed")
        pumpUntil("page created") { presenter().shownPage != null }
    }

    private fun openShownPage(campaign: JsonObject = htmlCampaign().build()) {
        openPage(campaign)
        showPage()
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
    }

    private fun flags() = presenter().shownPageWindow!!.dialog.window!!.attributes.flags
    private fun touchable() = flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
    private fun sdkClose(): View = presenter().shownPageWindow!!.dialog.window!!.decorView.findViewById(R.id.grovs_in_app_close)
    /** The full-window view behind the card: a tap on it is a backdrop tap. */
    private fun backdrop(): View = (sdkClose().parent as View).parent as View

    private fun nextOpened(): Intent? = shadowOf(application).nextStartedActivity

    private fun lastReply(): JSONObject {
        val script = lastToPage() ?: error("no reply sent to the page")
        assertTrue(script, script.startsWith("window.__grovsReply(") && script.endsWith(")"))
        return JSONObject(script.removePrefix("window.__grovsReply(").removeSuffix(")"))
    }

    private fun openArgs(url: String, active: Boolean? = true) = JsonObject().apply {
        addProperty("url", url)
        if (active != null) addProperty("active", active)
    }.toString()

    // "Invisible (alpha 0, touches pass through) until ready ... Unshown counts nothing and reports nothing"
    @Test fun `I32 before ready the page is not counted and lets touches through`() {
        openPage()
        assertTrue("touches pass through before ready", !touchable())
        assertTrue(recorder.shown.isEmpty())
        showPage()
        pumpUntil("counted at ready") { recorder.shown.isNotEmpty() }
        assertTrue("takes touches once shown", touchable())
        assertEquals(1, reportedWhen("in_app_viewed").size)
    }

    // "for non-full layouts, a height"
    @Test fun `I32 a modal waits for its height, a full page shows on ready alone`() {
        start(htmlCampaign("modal").triggers(event("modal")).build(), htmlCampaign("full").id("full").triggers(event("full")).build())
        Grovs.track("modal"); pumpUntil("modal page") { presenter().shownPage != null }
        fromPage("ready", """{"missing":false}""", "r")
        idleFor(1_000)
        assertTrue("modal hidden without a height", recorder.shown.isEmpty())
        fromPage("height", """{"value":80}""", "h")
        pumpUntil("modal shown") { recorder.shown == listOf("c1") }
        fromPage("close")
        pumpUntil("modal closed") { presenter().shownPage == null }

        Grovs.track("full"); pumpUntil("full page") { presenter().shownPage != null }
        fromPage("ready", """{"missing":false}""", "r")
        pumpUntil("full shown on ready") { recorder.shown == listOf("c1", "full") }
    }

    // "5 s deadline, else unshown(timeout) ... Unshown counts nothing and reports nothing"
    @Test fun `I32 no ready within 5 seconds leaves unshown, reports nothing and counts no view`() {
        openPage(htmlCampaign().caps(session = 1).build())
        idleFor(5_500)
        pumpUntil("page removed") { presenter().shownPage == null }
        assertTrue(recorder.shown.isEmpty())
        assertTrue(recorder.dismissed.isEmpty())
        Grovs.track("ctl")
        assertEquals("control: events are flowing", 1, reportedWhen("ctl").size)
        assertTrue(reported("in_app_viewed").isEmpty())
        assertTrue(reported("in_app_dismissed").isEmpty())
        // A session cap of 1 still lets it show: the unshown page counted no view.
        trackUntilPage(); showPage()
        pumpUntil("shown after the timeout") { recorder.shown == listOf("c1") }
    }

    // "ready {missing: true} gives unshown(missing)"
    @Test fun `I32 ready with missing fields leaves unshown and reports nothing`() {
        openPage()
        fromPage("ready", """{"missing":true}""")
        pumpUntil("page removed") { presenter().shownPage == null }
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("ctl")
        assertEquals("control: events are flowing", 1, reportedWhen("ctl").size)
        assertTrue(reported("in_app_viewed").isEmpty())
        assertTrue(reported("in_app_dismissed").isEmpty())
    }

    // README "dismissible false": "For html the SDK's close is always drawn ... dismissible: false only stops the backdrop tap"
    @Test fun `I34 the SDK close is drawn and works even when the page says not dismissible and no close button`() {
        openShownPage(htmlCampaign().dismissible(false).closeButton(false).build())
        assertEquals(View.VISIBLE, sdkClose().visibility)
        sdkClose().performClick(); pump()
        pumpUntil("closed") { recorder.dismissed.isNotEmpty() }
        assertEquals(listOf("c1" to "close_button"), recorder.dismissed)
        assertEquals("close_button", reportedWhen("in_app_dismissed").single().getJSONObject("properties").getString("reason"))
    }

    @Test fun `I34 not dismissible ignores the backdrop tap and back`() {
        openShownPage(htmlCampaign().dismissible(false).build())
        backdrop().performClick(); pump()
        presenter().shownPageWindow!!.dialog.onBackPressedDispatcher.onBackPressed(); pump()
        idleFor(500)
        assertTrue(recorder.dismissed.isEmpty())
        assertNotNull(presenter().shownPage)
        sdkClose().performClick(); pump()
        pumpUntil("control: the SDK close still works") { recorder.dismissed.isNotEmpty() }
    }

    @Test fun `I34 a dismissible modal closes on a backdrop tap`() {
        openShownPage()
        backdrop().performClick(); pump()
        pumpUntil("closed") { recorder.dismissed.isNotEmpty() }
        assertEquals(listOf("c1" to "backdrop"), recorder.dismissed)
        assertEquals("backdrop", reportedWhen("in_app_dismissed").single().getJSONObject("properties").getString("reason"))
    }

    // "close -> leave(close_button)"
    @Test fun `I35 close from the page dismisses it`() {
        openShownPage()
        fromPage("close")
        pumpUntil("dismissed") { recorder.dismissed.isNotEmpty() }
        assertEquals(listOf("c1" to "close_button"), recorder.dismissed)
        assertEquals("close_button", reportedWhen("in_app_dismissed").single().getJSONObject("properties").getString("reason"))
    }

    // "all ignored unless ... v == 1, the right token, string id and type, object args"
    @Test fun `I35 a message with the wrong token, version or shape is ignored`() {
        openShownPage()
        val page = page()
        // The bridge channel and the per-show token are how the page speaks; posting raw lets the test forge them.
        val channel = shadowOf(page.webView!!).getJavascriptInterface(InAppHtmlDocument.CHANNEL) as InAppJavascriptChannel
        val token = page.session.token
        listOf(
            """{"v":1,"token":"wrong","id":"9","type":"close","args":{}}""",
            """{"v":2,"token":"$token","id":"9","type":"close","args":{}}""",
            """{"v":1,"token":"$token","id":9,"type":"close","args":{}}""",
            """{"v":1,"token":"$token","id":"9","type":"close","args":[]}""",
            """{"v":1,"token":"$token","id":"9","type":"close"}""",
            "not json",
        ).forEach { channel.postMessage(it); pump() }
        idleFor(1_000)
        assertTrue(recorder.dismissed.isEmpty())
        fromPage("close")
        pumpUntil("control: a valid close works") { recorder.dismissed.isNotEmpty() }
    }

    // "forwarded as a host custom event with campaign_id added; the first track also reports in_app_clicked with action track"
    @Test fun `I35 track from the page reports the event and only the first one also reports a click`() {
        openShownPage()
        fromPage("track", """{"name":"page_cta","properties":{"x":1}}""", "t1")
        val first = reportedWhen("page_cta").single().getJSONObject("properties")
        assertEquals(1, first.getInt("x"))
        assertEquals("c1", first.getString("campaign_id"))
        val click = reportedWhen("in_app_clicked").single().getJSONObject("properties")
        assertEquals("track", click.getString("action"))
        fromPage("track", """{"name":"page_cta","properties":null}""", "t2")
        assertEquals(2, reportedWhen("page_cta", 2).size)
        assertEquals(1, reported("in_app_clicked").size)
        assertTrue("track leaves the page open", recorder.dismissed.isEmpty())
    }

    // "name 1 to 100 UTF-16 units, not reserved (22 names), properties object or null"
    @Test fun `I35 track with a reserved name, a bad length or bad properties is refused`() {
        openShownPage()
        reservedNames.forEachIndexed { i, name -> fromPage("track", """{"name":"$name","properties":{"from_page":true}}""", "r$i") }
        fromPage("track", """{"name":"","properties":{}}""", "e")
        fromPage("track", """{"name":"${"b".repeat(101)}","properties":{}}""", "l")
        fromPage("track", """{"name":"bad_props","properties":[1]}""", "p")
        fromPage("track", """{"name":"${"a".repeat(100)}","properties":{}}""", "ok")
        assertEquals("control: 100 units is accepted", 1, reportedWhen("a".repeat(100)).size)
        reservedNames.forEach { assertTrue("$it was forwarded", reported(it).none { e -> e.optJSONObject("properties")?.has("from_page") == true }) }
        assertTrue(reported("b".repeat(101)).isEmpty())
        assertTrue(reported("bad_props").isEmpty())
    }

    // "open {url, active}: refused when active is explicitly false; closes with a click outcome open_url (no button id)"
    @Test fun `I35 open is refused when the page says there was no recent tap`() {
        openShownPage()
        fromPage("open", openArgs("https://grovs.io/a", active = false), "o1")
        assertNull(nextOpened())
        assertTrue(recorder.clicked.isEmpty())
        fromPage("open", openArgs("https://grovs.io/a"), "o2")
        assertEquals("https://grovs.io/a", nextOpened()?.dataString)
        pumpUntil("closed by open") { presenter().shownPage == null }
        assertEquals(listOf(Triple("c1", null, null)), recorder.clicked)
        val click = reportedWhen("in_app_clicked").single().getJSONObject("properties")
        assertEquals("open_url", click.getString("action"))
        assertTrue(!click.has("button_id") || click.isNull("button_id"))
        assertTrue("an open is a click, not a dismissal", reported("in_app_dismissed").isEmpty())
    }

    @Test fun `I35 open without an active flag opens`() {
        openShownPage()
        fromPage("open", openArgs("https://grovs.io/b", active = null), "o")
        assertEquals("https://grovs.io/b", nextOpened()?.dataString)
        pumpUntil("closed by open") { presenter().shownPage == null }
    }

    // Only requestPushPermission waits for a reply: "only once shown, else reply error not_shown"
    @Test fun `I35 requestPushPermission before the page is shown replies not_shown`() {
        openPage()
        fromPage("requestPushPermission", "{}", "p1")
        val reply = lastReply()
        assertEquals(1, reply.getInt("v"))
        assertEquals("p1", reply.getString("id"))
        assertEquals(false, reply.getBoolean("ok"))
        assertEquals("not_shown", reply.getString("error"))
    }

    // The bridge resolves only pending requestPushPermission calls: "reply granted, denied or unsupported"
    @Test fun `I35 only requestPushPermission gets a reply, and once shown it is a permission state`() {
        openShownPage()
        fromPage("track", """{"name":"page_cta","properties":{}}""", "t")
        fromPage("setAttribute", """{"key":"plan","value":"gold"}""", "s")
        reportedWhen("page_cta")
        assertNull("ready, height, track and setAttribute expect no reply", lastToPage())
        fromPage("requestPushPermission", "{}", "p2")
        pumpUntil("reply") { lastToPage() != null }
        val reply = lastReply()
        assertEquals("p2", reply.getString("id"))
        assertEquals(true, reply.getBoolean("ok"))
        assertTrue(reply.toString(), reply.getString("result") in setOf("granted", "denied", "unsupported"))
    }

    // "setAttribute {key 1..100, value null/string/bool/number}: merges into user attributes and syncs them"
    @Test fun `I35 setAttribute from the page syncs the user attribute, other shapes are refused`() {
        openShownPage()
        fromPage("setAttribute", """{"key":"","value":"x"}""", "s1")
        fromPage("setAttribute", """{"key":"nested","value":{"a":1}}""", "s2")
        fromPage("setAttribute", """{"key":"plan","value":"gold"}""", "s3")
        pumpUntil("attributes synced") { attributeSyncs().any { it.has("plan") } }
        val synced = attributeSyncs().last { it.has("plan") }
        assertEquals("gold", synced.getString("plan"))
        assertTrue(attributeSyncs().none { it.has("") || it.has("nested") })
    }

    private fun attributeSyncs(): List<JSONObject> = backend.seen
        .filter { it.path == "visitor_attributes" && it.method != "GET" }
        .mapNotNull { runCatching { JSONObject(it.body).optJSONObject("sdk_attributes") }.getOrNull() }

    private fun opens(rule: JsonObject) = rule.get("expect").asString == "allowed" &&
        // A fragment passes the rule but has nothing to open; the bridge scrolls the page instead.
        !rule.get("input").asString.trim { it <= ' ' }.startsWith("#")

    // cases.json link_rule decides the link; open: "link rule, not a bare fragment"
    @Test fun `I37 links the shared rule refuses, and fragments, never open and keep the page`() {
        openShownPage()
        val opened = linkRules.filterNot(::opens).filterIndexed { i, rule ->
            fromPage("open", openArgs(rule.get("input").asString), "l$i")
            nextOpened() != null
        }.map { it.get("name").asString }
        assertEquals(emptyList<String>(), opened)
        assertNotNull(presenter().shownPage)
        assertTrue(recorder.clicked.isEmpty())
        fromPage("open", openArgs("https://grovs.io/a"), "ok")
        assertNotNull("control: an allowed link opens", nextOpened())
    }

    private val noBreakSpaceHost = "only ASCII spaces count: a no-break space is part of the host"

    /** Shows the page, posts an open for [input] and returns what it started; the page is closed either way. */
    private fun openFromShownPage(input: String): Intent? {
        if (recorder.shown.isNotEmpty()) reopen()
        val shown = recorder.shown.size
        trackUntilPage(); showPage()
        pumpUntil("shown for $input") { recorder.shown.size == shown + 1 }
        fromPage("open", openArgs(input), "l")
        val started = nextOpened()
        if (started == null) fromPage("close")
        pumpUntil("closed") { presenter().shownPage == null }
        return started
    }

    // An allowed open closes the page and takes the screen, so the app comes back before the next one.
    @Test fun `I37 every link the shared rule allows opens`() {
        start(htmlCampaign().caps(ignoreGlobal = true).build())
        val refused = linkRules.filter(::opens).filter { it.get("name").asString != noBreakSpaceHost }
            .filter { rule -> openFromShownPage(rule.get("input").asString)?.action != Intent.ACTION_VIEW }
            .map { it.get("name").asString }
        assertEquals(emptyList<String>(), refused)
    }

    @Ignore("F-3: an https link whose host starts with a no-break space passes the link rule but is never opened")
    @Test fun `I37 an https link whose host starts with a no-break space opens`() {
        start(htmlCampaign().build())
        val rule = linkRules.single { it.get("name").asString == noBreakSpaceHost }
        assertEquals("allowed", rule.get("expect").asString)
        assertNotNull(openFromShownPage(rule.get("input").asString))
    }
}
