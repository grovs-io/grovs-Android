package io.grovs.inapp

import android.app.Activity
import android.app.Application
import android.content.MutableContextWrapper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.R
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppRendered
import io.grovs.inapp.ui.InAppActions
import io.grovs.inapp.ui.InAppDialogPresenter
import io.grovs.inapp.ui.InAppHtmlDocument
import io.grovs.inapp.ui.InAppHtmlView
import io.grovs.inapp.ui.InAppHtmlTransport
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class InAppHtmlDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as Application
    private val server = MockWebServer()
    private val outcomes = LinkedBlockingQueue<InAppOutcome>()
    private val tracks = LinkedBlockingQueue<String>()
    private lateinit var shown: CountDownLatch
    private lateinit var scenario: ActivityScenario<InAppHostActivity>
    private lateinit var presenter: InAppDialogPresenter
    @Volatile private var current: Activity? = null

    @Before
    fun setUp() {
        server.start()
        scenario = ActivityScenario.launch(InAppHostActivity::class.java)
        scenario.onActivity { current = it }
        presenter = onMain {
            InAppDialogPresenter(
                activity = { current },
                images = InAppImages(File(context.cacheDir, "in-app-device-test"), { _, done -> done(null) }, { true }),
                actions = InAppActions(context, { current }),
            ).also { it.register(application) }
        }
    }

    @After
    fun tearDown() {
        onMain {
            presenter.dismiss()
            presenter.unregister(application)
        }
        scenario.close()
        server.shutdown()
    }

    // Harness

    private fun campaign(layout: String, html: String): InAppCampaign {
        val raw = JsonParser.parseString(BASE).asJsonObject
        raw.add("content", JsonObject().apply { addProperty("html", html); addProperty("layout", layout) })
        return InAppDefinition.readCampaign(raw) ?: error("the html fixture must read as a campaign")
    }

    private fun present(html: String, layout: String = "modal"): InAppHtmlView {
        shown = CountDownLatch(1)
        val presentation = InAppPresentation(
            campaign = campaign(layout, html),
            rendered = InAppRendered(null, null, emptyList(), emptyList()),
            user = JsonObject().apply { addProperty("first_name", "Ana") },
            onShown = { shown.countDown() },
            onTrack = { name, _ -> tracks.add(name) },
            canShow = { true },
        )
        return onMain {
            assertTrue(presenter.present(presentation) { outcomes.add(it) })
            presenter.shownPage!!
        }
    }

    private fun awaitShown() = assertTrue("the page never said it was ready", shown.await(10, TimeUnit.SECONDS))

    private fun <T> onMain(block: () -> T): T {
        var value: Any? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun eval(view: InAppHtmlView, script: String): String? {
        val result = LinkedBlockingQueue<String>()
        onMain { view.webView?.evaluateJavascript(script) { result.add(it ?: "null") } }
        return result.poll(5, TimeUnit.SECONDS)
    }

    private fun waitFor(view: InAppHtmlView, script: String, done: (String?) -> Boolean): String? {
        val end = SystemClock.uptimeMillis() + 5_000
        var last: String? = null
        while (SystemClock.uptimeMillis() < end) {
            last = eval(view, script)
            if (done(last)) return last
            Thread.sleep(100)
        }
        return last
    }

    /**
     * The system refuses a tap that would land on another app's window. A system window over ours (the
     * notification shade, a "not responding" dialog) is closed once, and the point is read again since closing
     * it can move the page; a window that stays is named in the failure.
     */
    private fun tapScreen(point: () -> Pair<Float, Float>) {
        if (inject(onMain(point))) return
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        Thread.sleep(1_000)
        val (x, y) = onMain(point)
        if (!inject(x to y)) throw AssertionError("the tap at $x,$y would reach another app's window: ${windowsAboveOurs()}")
    }

    private fun inject(point: Pair<Float, Float>): Boolean {
        val (x, y) = point
        val down = SystemClock.uptimeMillis()
        return try {
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun windowsAboveOurs(): List<String> = shell("dumpsys window windows").split("  Window #").drop(1)
        .takeWhile { "package=${context.packageName} " !in it }
        .filter { "isOnScreen=true" in it }
        .map { it.substringAfter(" u0 ").substringBefore("}") }

    private fun tapPage(view: InAppHtmlView, xDp: Float, yDp: Float) = tapScreen {
        val web = view.webView!!
        val at = IntArray(2)
        web.getLocationOnScreen(at)
        val density = web.resources.displayMetrics.density
        (at[0] + xDp * density) to (at[1] + yDp * density)
    }

    private fun tapView(target: View) = tapScreen {
        val at = IntArray(2)
        target.getLocationOnScreen(at)
        (at[0] + target.width / 2f) to (at[1] + target.height / 2f)
    }

    private fun noOutcomeFor(millis: Long) = assertNull(outcomes.poll(millis, TimeUnit.MILLISECONDS))

    private fun safeHeightPx(activity: Activity): Int {
        val decor = activity.window.decorView
        val safe = ViewCompat.getRootWindowInsets(decor)!!
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return decor.height - safe.top - safe.bottom
    }

    private fun until(check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun nextOfType(messages: LinkedBlockingQueue<String>, type: String): JsonObject? {
        while (true) {
            val raw = messages.poll(5, TimeUnit.SECONDS) ?: return null
            val message = JsonParser.parseString(raw).asJsonObject
            if (message.get("type").asString == type) return message
        }
    }

    /**
     * A busy loop that ends: every WebView in the app shares one renderer, so an endless one would freeze the
     * pages of every later test. The test then sleeps until the loop is over.
     */
    private fun spin(millis: Long) = "var end=Date.now()+$millis;while(Date.now()<end){}"

    /** Runs a shell command and waits for it to finish. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** The system's own answer, the same before and after the page closes, whichever window holds the focus. */
    private fun keyboardShown(): Boolean = shell("dumpsys input_method").contains("mInputShown=true")

    /** Polls a size until it is non-zero and has held still for half a second. */
    private fun settled(read: () -> Int): Int {
        val end = SystemClock.uptimeMillis() + 5_000
        var last = -1
        var since = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < end) {
            val now = onMain(read)
            if (now != last) {
                last = now
                since = SystemClock.uptimeMillis()
            } else if (now > 0 && SystemClock.uptimeMillis() - since >= 500) {
                return now
            }
            Thread.sleep(50)
        }
        return last
    }

    // Tests

    @Test
    fun aRealPageRunsTheBridgeFillsAFieldAndShows() {
        val view = present("<p id=\"n\">Hi {{ user.first_name }}</p>")
        awaitShown()
        assertEquals("\"Hi Ana\"", eval(view, "document.getElementById('n').textContent"))
        assertEquals("the handoff is gone", "\"undefined\"", eval(view, "typeof window.__grovs"))
        assertEquals("the boot script removed itself", "0", eval(view, "document.querySelectorAll('script').length"))
        noOutcomeFor(300)
    }

    @Test
    fun aTallModalIsClampedAndABannerTakesItsPagesHeight() {
        val modal = present("<style>body{margin:0}</style><div style=\"height:5000px\">tall</div>")
        awaitShown()
        val height = settled { modal.webView!!.height }
        val (safe, density) = onMain { safeHeightPx(current!!) to modal.webView!!.resources.displayMetrics.density }
        // The modal keeps 16 dp from the safe top and bottom, and takes 90 % of what is left.
        val room = safe - 2 * 16 * density
        assertEquals("$height is 90 % of $room", (room * 0.9f).roundToInt().toFloat(), height.toFloat(), 2f)
        onMain { presenter.dismiss() }
        outcomes.poll(2, TimeUnit.SECONDS)

        val banner = present("<style>body{margin:0}</style><div style=\"height:60px\">short</div>", "banner_bottom")
        awaitShown()
        val bannerHeight = settled { banner.webView!!.height }
        assertEquals((60 * density).roundToInt().toFloat(), bannerHeight.toFloat(), 2 * density)
    }

    @Test
    fun theDataActionsTrackAndClose() {
        val view = present("<button id=\"t\" data-grovs-action=\"track\" data-grovs-event=\"chose_plan\">Pick</button>" +
            "<button id=\"c\" data-grovs-action=\"close\">Close</button>")
        awaitShown()
        eval(view, "document.getElementById('t').click()")
        assertEquals("chose_plan", tracks.poll(3, TimeUnit.SECONDS))
        noOutcomeFor(300)
        eval(view, "document.getElementById('c').click()")
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun openNeedsARealTapAndThenCloses() {
        val view = present("<style>body{margin:0}#o{display:block;width:100%;height:160px}</style>" +
            "<button id=\"o\" onclick=\"grovs.open('grovs-test://opened')\">Open</button>")
        awaitShown()
        eval(view, "document.getElementById('o').click()")
        noOutcomeFor(1_000)
        tapPage(view, 40f, 80f)
        val clicked = outcomes.poll(3, TimeUnit.SECONDS) as InAppOutcome.Clicked
        assertEquals(InAppActionType.OPEN_URL, clicked.action.type)
        assertEquals("grovs-test://opened", clicked.action.url)
    }

    @Test
    fun requestPushPermissionAnswersNotShownEarlyThenTheState() {
        val view = present("<script>grovs.requestPushPermission().then(function(r){document.title='early:'+r}," +
            "function(e){document.title='early-error:'+e.message})</script>" +
            "<button id=\"p\" onclick=\"grovs.requestPushPermission().then(function(r){document.title='late:'+r})\">Ask</button>")
        awaitShown()
        assertEquals("\"early-error:not_shown\"", waitFor(view, "document.title") { it == "\"early-error:not_shown\"" })
        eval(view, "document.getElementById('p').click()")
        val late = waitFor(view, "document.title") { it?.startsWith("\"late:") == true }
        assertTrue(late, late in setOf("\"late:granted\"", "\"late:denied\"", "\"late:unsupported\""))
        noOutcomeFor(300)
    }

    @Test
    fun fragmentLinksScrollAndOpenNothing() {
        val view = present("<style>body{margin:0}</style><a id=\"down\" href=\"#end\" style=\"display:block;height:160px\">Down</a>" +
            "<div style=\"height:3000px\"></div><p id=\"end\">End</p>")
        awaitShown()
        tapPage(view, 40f, 80f)
        assertEquals("true", waitFor(view, "window.scrollY > 0") { it == "true" })
        noOutcomeFor(500)
    }

    @Test
    fun mainFrameNavigationIsCancelledForGetAndPost() {
        val url = server.url("/landing").toString()
        val view = present("<p id=\"m\">stay</p><form id=\"f\" method=\"post\" action=\"$url\"><input name=\"a\" value=\"1\"></form>")
        awaitShown()
        eval(view, "location.href = '$url'")
        Thread.sleep(1_500)
        assertEquals("\"stay\"", eval(view, "document.getElementById('m').textContent"))
        eval(view, "document.getElementById('f').submit()")
        Thread.sleep(1_500)
        assertEquals("\"stay\"", eval(view, "document.getElementById('m').textContent"))
        eval(view, "window.open('$url')")
        Thread.sleep(1_000)
        assertEquals(0, server.requestCount)
        noOutcomeFor(300)
    }

    @Test
    fun subframesLoadButCannotSpeakForThePage() {
        val listener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val token = LinkedBlockingQueue<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/embed") return MockResponse().setResponseCode(204)
                // Served once the page shows, so the embed's close is not dropped as too early.
                shown.await(10, TimeUnit.SECONDS)
                // The listener knows the frame, so the embed gets this show's token; the fallback has only the token as a guard.
                val sent = if (listener) token.poll(5, TimeUnit.SECONDS) else "forged"
                return MockResponse().setBody("<p>embed</p><script>" +
                    "new Image().src='/seen?bridge='+typeof window.grovsAndroid;" +
                    "if(window.grovsAndroid){grovsAndroid.postMessage(JSON.stringify({v:1,token:'$sent',id:'1',type:'close',args:{}}));" +
                    "new Image().src='/posted';}</script>")
            }
        }
        val view = present("<iframe src=\"${server.url("/embed")}\"></iframe>")
        token.add(onMain { view.session.token })
        awaitShown()
        assertEquals("/embed", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        // The two pings load in parallel, so they may arrive in either order.
        val pings = (1..2).mapNotNull { server.takeRequest(5, TimeUnit.SECONDS)?.path }.toSet()
        assertEquals("the embed's script ran, saw the channel and posted its close", setOf("/seen?bridge=object", "/posted"), pings)
        noOutcomeFor(1_000)
        assertNotNull(onMain { view.webView })
    }

    @Test
    fun aPageStuckInAnEndlessLoopStillClosesWithTheSdkClose() {
        val view = present("<p>Hi</p><script>setTimeout(function(){${spin(4_000)}},500)</script>")
        awaitShown()
        Thread.sleep(1_000)
        val close = onMain { view.root!!.findViewById<View>(R.id.grovs_in_app_close) }
        tapView(close)
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.poll(5, TimeUnit.SECONDS))
        Thread.sleep(4_000)
    }

    @Test
    fun aPageThatNeverGetsReadyTimesOut() {
        present("<script>${spin(7_000)}</script><p>never</p>")
        assertEquals(InAppOutcome.Unshown("timeout"), outcomes.poll(8, TimeUnit.SECONDS))
        assertEquals(1L, shown.count)
        Thread.sleep(3_000)
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun aCrashedPageClosesWithoutTakingTheAppDown() {
        val view = present("<p>Hi</p>")
        awaitShown()
        onMain { view.webView!!.loadUrl("chrome://crash") }
        assertEquals(InAppOutcome.Dismissed("app"), outcomes.poll(5, TimeUnit.SECONDS))
        scenario.onActivity { assertTrue(!it.isFinishing) }
    }

    @Test
    fun theMessageSurvivesARecreatedActivity() {
        val view = present("<p>Hi</p>")
        awaitShown()
        val before = onMain { view.webView }
        eval(view, "window.__mark = 42")
        scenario.recreate()
        scenario.onActivity { current = it }
        assertTrue("the page never moved to the new Activity", until {
            onMain {
                val web = view.webView
                (web?.context as? MutableContextWrapper)?.baseContext === current && web?.isAttachedToWindow == true
            }
        })
        assertSame(view, onMain { presenter.shownPage })
        assertSame(before, onMain { view.webView })
        assertTrue(onMain { view.webView!!.isAttachedToWindow })
        assertEquals("42", eval(view, "window.__mark"))
        noOutcomeFor(500)
    }

    @Test
    fun theListenerBridgeIsThereBeforeThePagesFirstScriptOnAnOpaqueOrigin() {
        assumeTrue("this WebView has no WebMessageListener", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val view = present("<script>window.seen = typeof window.grovsAndroid + ':' + typeof window.grovsAndroid.postMessage</script><p>Hi</p>")
        // The session takes ready only from the main frame, so showing proves isMainFrame was true.
        awaitShown()
        assertEquals("\"object:function\"", eval(view, "window.seen"))
        assertEquals("\"about:blank\"", eval(view, "location.href"))
        assertEquals("\"null\"", eval(view, "window.origin"))
    }

    @Test
    fun theListenerPostIsBoundBeforeThePageCanReplaceTheChannel() {
        val view = present("<script>window.grovsAndroid = {fake: true, postMessage: function () {}};" +
            "window.replaced = window.grovsAndroid.fake === true</script>" +
            "<button id=\"t\" data-grovs-action=\"track\" data-grovs-event=\"chose_plan\">Pick</button>")
        awaitShown()
        assertEquals("true", eval(view, "window.replaced"))
        eval(view, "document.getElementById('t').click()")
        assertEquals("chose_plan", tracks.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun theJavascriptInterfaceFallbackCarriesTheBoundPost() {
        val messages = LinkedBlockingQueue<String>()
        val token = "device-test-token"
        val page = "<script>window.grovsAndroid = {fake: true, postMessage: function () {}};" +
            "window.replaced = window.grovsAndroid.fake === true</script>" +
            "<button id=\"t\" data-grovs-action=\"track\" data-grovs-event=\"chose_plan\">Pick</button>"
        var remove: () -> Unit = {}
        val web = onMain {
            val activity = current!!
            WebView(activity).also { web ->
                web.settings.javaScriptEnabled = true
                remove = InAppHtmlTransport.install(web, useListener = false) { raw, mainFrame -> if (mainFrame) messages.add(raw) }
                activity.addContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val script = InAppHtmlDocument.injection(token, JsonObject(), JsonObject(), "unsupported")
                web.loadDataWithBaseURL(null, InAppHtmlDocument.document(page, script), "text/html", "utf-8", null)
            }
        }
        try {
            val ready = nextOfType(messages, "ready")
            assertNotNull("the page never posted ready through the interface", ready)
            assertEquals(token, ready!!.get("token").asString)
            val replaced = LinkedBlockingQueue<String>()
            onMain { web.evaluateJavascript("window.replaced") { replaced.add(it) } }
            assertEquals("true", replaced.poll(5, TimeUnit.SECONDS))
            onMain { web.evaluateJavascript("document.getElementById('t').click()", null) }
            val track = nextOfType(messages, "track")
            assertNotNull("the bound post did not reach the interface", track)
            assertEquals("chose_plan", track!!.getAsJsonObject("args").get("name").asString)
        } finally {
            onMain {
                remove()
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
    }

    @Test
    fun theKeyboardThePageOpenedGoesAwayWithIt() {
        val setting = "secure show_ime_with_hard_keyboard"
        val original = shell("settings get $setting").trim()
        shell("settings put $setting 1")
        try {
            val view = present("<style>body{margin:0}input{display:block;width:100%;height:120px}</style><input id=\"i\">")
            awaitShown()
            tapPage(view, 40f, 60f)
            assumeTrue("no soft keyboard came up on this device", until { keyboardShown() })
            val close = onMain { view.root!!.findViewById<View>(R.id.grovs_in_app_close) }
            // The keyboard lifts the modal; tap the close once it has stopped moving.
            settled { IntArray(2).also(close::getLocationOnScreen)[1] + 1 }
            tapView(close)
            assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.poll(5, TimeUnit.SECONDS))
            assertTrue("the keyboard stayed up after the page closed", until { !keyboardShown() })
        } finally {
            shell(if (original == "null") "settings delete $setting" else "settings put $setting $original")
        }
    }

    private companion object {
        const val BASE = """{"format":1,"id":"c1","revision":1,"created_at":"2026-09-01T00:00:00Z","priority":1,"template":"html","content":{},"triggers":[{"type":"event","name":"cart_viewed"}],"conditions":[],"caps":{"session":null,"day":null,"week":null,"lifetime":null,"ignore_global":false},"schedule":{"start_at":"2026-09-01T00:00:00Z","end_at":null,"days":null,"from":null,"to":null},"delay_seconds":0,"delivery_mode":"device","variants":null,"control_group":null,"conversion":null}"""
    }
}
