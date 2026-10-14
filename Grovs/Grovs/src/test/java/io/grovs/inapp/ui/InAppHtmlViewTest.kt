package io.grovs.inapp.ui

import android.app.NotificationManager
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.JsonObject
import io.grovs.R
import io.grovs.inapp.FakeTimer
import io.grovs.inapp.InAppBridge
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.InAppPresentation
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboWebSettings

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppHtmlViewTest {
    private val outcomes = mutableListOf<InAppOutcome>()
    private val timers = mutableListOf<FakeTimer>()
    private var shownCount = 0
    private lateinit var activity: ComponentActivity

    @Before
    fun setUp() {
        outcomes.clear(); timers.clear(); shownCount = 0
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    private class Shown(val view: InAppHtmlView, val window: InAppWindow)

    /** Draws the page the way the presenter does (Task 5): attach, a window for its edge, show, bind. */
    private fun present(
        layout: String = "modal", html: String = "<p>Hi</p>", content: JsonObject = JsonObject(),
        makeWebView: (Context) -> WebView = { WebView(it) },
    ): Shown {
        val presentation = InAppPresentation(
            campaign = InAppHtmlFixtures.campaign(layout, html, content),
            rendered = InAppHtmlFixtures.RENDERED,
            user = obj("first_name" to "Ana"),
            onShown = { shownCount++ },
        )
        val view = InAppHtmlView(
            presentation, InAppActions(activity, { activity }, reviewAvailable = false), { outcomes += it },
            schedule = { delay, work -> FakeTimer(delay, work).also { timers += it } },
            makeWebView = makeWebView,
        )
        return draw(view, activity)
    }

    private fun draw(view: InAppHtmlView, on: ComponentActivity): Shown {
        val window = InAppWindow(on, view.attach(on), view.edge, onBack = view::onBack, onInsets = view::applyInsets)
        assertTrue(window.show(view.lightStatusIcons))
        view.bind(window)
        idle()
        return Shown(view, window)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun post(view: InAppHtmlView, type: String, args: JsonObject = JsonObject()) {
        val channel = shadowOf(view.webView).getJavascriptInterface(InAppHtmlDocument.CHANNEL) as InAppJavascriptChannel
        val message = JsonObject().apply {
            addProperty("v", 1); addProperty("token", view.session.token); addProperty("id", "$type-1")
            addProperty("type", type); add("args", args)
        }
        channel.postMessage(message.toString())
        idle()
    }

    private fun show(view: InAppHtmlView) {
        post(view, "ready", obj("missing" to false))
        post(view, "height", obj("value" to 120))
    }

    private fun has(shown: Shown, flag: Int) = shown.window.dialog.window!!.attributes.flags and flag != 0
    private fun dp(value: Float) = value * activity.resources.displayMetrics.density

    @Test
    fun loadsThePageWithTheBootScriptAndNoBaseUrl() {
        val shown = present(html = "<p>Hi {{ user.first_name }}</p>")
        val load = shadowOf(shown.view.webView).lastLoadDataWithBaseURL
        assertNull(load.baseUrl)
        assertEquals("text/html", load.mimeType)
        assertEquals("utf-8", load.encoding)
        assertTrue(load.data.startsWith("<!doctype html><meta name=\"viewport\""))
        assertTrue(load.data.contains(shown.view.session.token))
        assertTrue(load.data.contains(InAppBridge.SCRIPT))
        assertTrue(load.data.endsWith("<p>Hi {{ user.first_name }}</p>"))
        assertTrue(shown.view.webView!!.context is MutableContextWrapper)
    }

    @Test
    fun theWebViewKeepsNothingAndOpensNoWindows() {
        val web = present().view.webView!!
        val settings = web.settings
        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(settings.supportMultipleWindows())
        assertTrue(settings.mediaPlaybackRequiresUserGesture)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        assertTrue(settings.useWideViewPort)
        assertFalse((settings as RoboWebSettings).geolocationEnabled)
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web))
    }

    @Test
    fun staysHiddenAndLetsTouchesAndBackThroughUntilReady() {
        val shown = present()
        assertEquals(InAppWindow.Edge.FULL, shown.view.edge)
        assertEquals(0f, shown.view.root!!.alpha, 0f)
        assertTrue(has(shown, FLAG_NOT_TOUCHABLE))
        assertTrue("back and the keyboard stay with the app", has(shown, FLAG_NOT_FOCUSABLE))
        shown.window.dialog.onBackPressedDispatcher.onBackPressed()
        assertTrue(outcomes.isEmpty())
        post(shown.view, "ready", obj("missing" to false))
        assertEquals("a modal waits for its height", 0, shownCount)
        post(shown.view, "height", obj("value" to 120))
        assertEquals(1, shownCount)
        assertEquals(1f, shown.view.root!!.alpha, 0f)
        assertFalse(has(shown, FLAG_NOT_TOUCHABLE))
        assertFalse(has(shown, FLAG_NOT_TOUCH_MODAL))
        assertFalse(has(shown, FLAG_NOT_FOCUSABLE))
    }

    @Test
    fun aBannerKeepsTheAppUsableAndTakesFocusOnlyWhenTouched() {
        val shown = present("banner_top")
        assertEquals(InAppWindow.Edge.TOP, shown.view.edge)
        assertEquals(Gravity.TOP, shown.window.dialog.window!!.attributes.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, shown.window.dialog.window!!.attributes.height)
        show(shown.view)
        assertFalse(has(shown, FLAG_NOT_TOUCHABLE))
        assertTrue(has(shown, FLAG_NOT_TOUCH_MODAL))
        assertTrue(has(shown, FLAG_NOT_FOCUSABLE))
        shown.view.webView!!.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 4f, 4f, 0))
        assertFalse(has(shown, FLAG_NOT_FOCUSABLE))
        assertTrue(has(shown, FLAG_NOT_TOUCH_MODAL))
    }

    @Test
    fun alwaysDrawsALabelledCloseThatClosesWhateverTheCampaignSays() {
        val content = obj("close_button" to false, "dismissible" to false)
        for (layout in listOf("full", "modal", "banner_top", "banner_bottom")) {
            outcomes.clear()
            val shown = present(layout, content = content)
            val close = shown.view.root!!.findViewById<View>(R.id.grovs_in_app_close)
            assertNotNull(layout, close)
            assertEquals(layout, "Close", close.contentDescription)
            assertTrue(layout, close.layoutParams.width >= dp(48f).toInt())
            assertFalse("not inside the page", close.parent === shown.view.webView)
            show(shown.view)
            close.performClick()
            assertEquals(layout, InAppOutcome.Dismissed("close_button"), outcomes.single())
        }
    }

    @Test
    fun backFollowsDismissible() {
        val dismissible = present()
        show(dismissible.view)
        dismissible.window.dialog.onBackPressedDispatcher.onBackPressed()
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.single())

        outcomes.clear()
        val stuck = present(content = obj("dismissible" to false))
        show(stuck.view)
        stuck.window.dialog.onBackPressedDispatcher.onBackPressed()
        assertTrue(outcomes.isEmpty())
        assertTrue(stuck.window.dialog.isShowing)
    }

    @Test
    fun aModalClosesFromTheBackdropOnlyWhenDismissible() {
        val shown = present()
        show(shown.view)
        val root = shown.view.root!!
        assertEquals(0x8C000000.toInt(), (root.background as ColorDrawable).color)
        root.performClick()
        assertEquals(InAppOutcome.Dismissed("backdrop"), outcomes.single())

        outcomes.clear()
        val fixed = present(content = obj("dismissible" to false))
        show(fixed.view)
        fixed.view.root!!.performClick()
        assertTrue(outcomes.isEmpty())

        val clear = present(content = obj("backdrop" to "none"))
        assertNull("no dim", clear.view.root!!.background)
    }

    @Test
    fun clampsTheHeightToTheLayoutAndStartsAtItsLargest() {
        val shown = present()
        val web = shown.view.webView!!
        val metrics = activity.resources.displayMetrics
        assertTrue("starts at its largest, not collapsed", web.layoutParams.height > dp(44f))
        show(shown.view)
        assertEquals(dp(120f), web.layoutParams.height.toFloat(), 1f)
        post(shown.view, "height", obj("value" to 5000))
        assertTrue(web.layoutParams.height <= (metrics.heightPixels * 0.9f).toInt() + 1)
        assertTrue(web.layoutParams.height > dp(120f))
        post(shown.view, "height", obj("value" to 1))
        assertEquals("never below the close", dp(44f), web.layoutParams.height.toFloat(), 1f)

        val full = present("full")
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, full.view.webView!!.layoutParams.height)
    }

    @Test
    fun keepsThePageInsideTheSafeAreaAndAboveTheKeyboard() {
        val safe = Insets.of(0, 48, 0, 32)
        val full = present("full")
        full.view.applyInsets(safe, 0)
        val margins = (full.view.webView!!.parent as FrameLayout).layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(48, margins.topMargin)
        assertEquals(32, margins.bottomMargin)
        full.view.applyInsets(safe, 600)
        assertEquals("lifted above the keyboard", 600, margins.bottomMargin)

        val top = present("banner_top")
        top.view.applyInsets(safe, 0)
        assertEquals(48, (top.view.webView!!.parent as FrameLayout).paddingTop)

        val bottom = present("banner_bottom")
        bottom.view.applyInsets(safe, 600)
        assertEquals(600, (bottom.view.webView!!.parent as FrameLayout).paddingBottom)
    }

    @Test
    fun followsTheThemeColourUnderTheStatusBar() {
        val dark = present("full", html = "<head><meta name=\"theme-color\" content=\"#0B1220\"></head><p>Hi</p>")
        assertNull("the app's own until the page shows", dark.view.lightStatusIcons)
        post(dark.view, "ready", obj("missing" to false))
        assertEquals(true, dark.view.lightStatusIcons)
        val window = dark.window.dialog.window!!
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
        assertEquals(0xFF0B1220.toInt(), (dark.view.root!!.background as ColorDrawable).color)

        val light = present("full", html = "<head><meta name=\"theme-color\" content=\"#FFFFFF\"></head><p>Hi</p>")
        post(light.view, "ready", obj("missing" to false))
        val lightWindow = light.window.dialog.window!!
        assertTrue(WindowCompat.getInsetsController(lightWindow, lightWindow.decorView).isAppearanceLightStatusBars)

        val modal = present("modal", html = "<head><meta name=\"theme-color\" content=\"#0B1220\"></head><p>Hi</p>")
        show(modal.view)
        assertNull("a modal is not under the status bar", modal.view.lightStatusIcons)
    }

    @Test
    fun answersAPushRequestBeforeItShowsWithNotShown() {
        val shown = present()
        post(shown.view, "requestPushPermission")
        val script = shadowOf(shown.view.webView).lastEvaluatedJavascript
        assertTrue(script.startsWith("window.__grovsReply("))
        assertTrue(script.contains("\"error\":\"not_shown\""))
    }

    @Test
    fun aPageAskingForPushWhileDeniedIsAnsweredWithoutOpeningTheSettings() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val actions = InAppActions(app, { activity }, sdkInt = 28, reviewAvailable = false)
        var result: String? = null
        actions.requestPushPermission(openSettingsWhenDenied = false) { result = it }
        assertEquals("denied", result)
        assertNull(shadowOf(activity).nextStartedActivity)
        actions.requestPushPermission { result = it }
        assertNotNull("a button's action still opens the settings", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun theDeadlineEndsItUnshownAndTearsTheWebViewDown() {
        val shown = present()
        val web = shown.view.webView!!
        timers.single { it.delay == 5_000L }.work()
        assertEquals(InAppOutcome.Unshown("timeout"), outcomes.single())
        assertNull(shown.view.webView)
        assertNull(web.parent)
        idle()
        assertNull(shadowOf(web).getJavascriptInterface(InAppHtmlDocument.CHANNEL))
    }

    @Test
    fun aWebViewThatCannotBeCreatedEndsUnshown() {
        val shown = present(makeWebView = { throw IllegalStateException("WebView is updating") })
        assertTrue("it was still drawn, so the presenter got content", shown.window.dialog.isShowing)
        assertNull(shown.view.webView)
        assertEquals(InAppOutcome.Unshown("app"), outcomes.single())
    }

    @Test
    fun discardTearsDownWithoutAnOutcome() {
        val shown = present()
        shown.view.discard()
        idle()
        assertNull(shown.view.webView)
        timers.single { it.delay == 5_000L }.work()
        shown.view.leave("app")
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun movesToANewActivityKeepingTheSameWebView() {
        val shown = present()
        show(shown.view)
        val web = shown.view.webView!!
        shown.view.detach()
        shown.window.close()
        assertNull(web.parent)

        val next = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val moved = draw(shown.view, next)

        assertSame(web, shown.view.webView)
        assertSame(next, (web.context as MutableContextWrapper).baseContext)
        assertNotNull(web.parent)
        assertEquals("still shown", 1f, shown.view.root!!.alpha, 0f)
        assertFalse(has(moved, FLAG_NOT_TOUCHABLE))
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aCrashedPageClosesTheMessageAndKeepsTheAppAlive() {
        val shown = present()
        show(shown.view)
        val web = shown.view.webView!!
        val client = shadowOf(web).webViewClient
        assertTrue(client.onRenderProcessGone(web, mockk<RenderProcessGoneDetail>(relaxed = true)))
        assertEquals(InAppOutcome.Dismissed("app"), outcomes.single())
        assertNull(shown.view.webView)
        assertNull("off the screen at once", web.parent)
        assertTrue("destroyed at once", shadowOf(web).wasDestroyCalled())
    }

    @Test
    fun aRendererThatDiesAfterTheMessageEndedStillKeepsTheAppAlive() {
        val shown = present()
        val web = shown.view.webView!!
        val client = shadowOf(web).webViewClient
        shown.view.leave("app")
        assertTrue(client.onRenderProcessGone(web, mockk<RenderProcessGoneDetail>(relaxed = true)))
        assertEquals(InAppOutcome.Unshown("app"), outcomes.single())
        assertTrue("destroyed at once", shadowOf(web).wasDestroyCalled())
        idle()
        assertNotNull("never touched again", shadowOf(web).getJavascriptInterface(InAppHtmlDocument.CHANNEL))
    }

    @Test
    fun aWebViewThatFailsWhileBeingSetUpEndsUnshown() {
        var made: WebView? = null
        val shown = present(makeWebView = { context ->
            object : WebView(context) {
                override fun getSettings() = throw IllegalStateException("WebView provider is updating")
            }.also { made = it }
        })
        assertNull(shown.view.webView)
        assertEquals(InAppOutcome.Unshown("app"), outcomes.single())
        assertTrue(shadowOf(made).wasDestroyCalled())
    }

    @Test
    fun blocksMainFrameRequestsAndLetsSubframesThrough() {
        val shown = present()
        val web = shown.view.webView!!
        val client = shadowOf(web).webViewClient
        fun request(address: String, mainFrame: Boolean, gesture: Boolean = false): WebResourceRequest = mockk {
            every { url } returns Uri.parse(address)
            every { isForMainFrame } returns mainFrame
            every { hasGesture() } returns gesture
            every { method } returns "POST"
        }
        assertTrue(client.shouldOverrideUrlLoading(web, request("https://a.example", mainFrame = true)))
        assertFalse(client.shouldOverrideUrlLoading(web, request("https://embed.example", mainFrame = false)))
        assertEquals(204, client.shouldInterceptRequest(web, request("https://a.example/form", mainFrame = true))!!.statusCode)
        assertNull(client.shouldInterceptRequest(web, request("https://embed.example/x", mainFrame = false)))
        assertNull(client.shouldInterceptRequest(web, request("data:text/html,x", mainFrame = true)))
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aModalStaysAboveTheKeyboardAndGrowsBackWhenItCloses() {
        val shown = present()
        show(shown.view)
        val web = shown.view.webView!!
        val root = shown.view.root!!
        val safe = Insets.of(0, 24, 0, 0)
        post(shown.view, "height", obj("value" to 5000))
        shown.view.applyInsets(safe, 200)
        idle()
        val height = if (root.height > 0) root.height else activity.resources.displayMetrics.heightPixels
        val withKeyboard = web.layoutParams.height
        assertTrue("$withKeyboard fits in ${height - root.paddingTop - root.paddingBottom}",
            withKeyboard <= height - root.paddingTop - root.paddingBottom)
        shown.view.applyInsets(safe, 0)
        idle()
        assertTrue("grows back", web.layoutParams.height > withKeyboard)
    }

    @Test
    fun aBannerIsAtMost45PercentOnAShortScreen() {
        val shown = present("banner_top")
        show(shown.view)
        post(shown.view, "height", obj("value" to 5000))
        assertEquals(safeHeight() * 0.45f, shown.view.webView!!.layoutParams.height.toFloat(), 2f)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun aBannerIsAtMost30PercentOnATallScreen() {
        val shown = present("banner_bottom")
        show(shown.view)
        post(shown.view, "height", obj("value" to 5000))
        assertEquals(safeHeight() * 0.3f, shown.view.webView!!.layoutParams.height.toFloat(), 2f)
    }

    private fun safeHeight(): Float {
        val decor = activity.window.decorView
        val height = if (decor.height > 0) decor.height else activity.resources.displayMetrics.heightPixels
        val bars = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return (height - (bars?.top ?: 0) - (bars?.bottom ?: 0)).toFloat()
    }

    @Test
    fun aSecondMainFramePageStartIsStopped() {
        var stops = 0
        val shown = present(makeWebView = { context ->
            object : WebView(context) {
                override fun stopLoading() {
                    stops++
                    super.stopLoading()
                }
            }
        })
        val web = shown.view.webView!!
        val client = shadowOf(web).webViewClient
        client.onPageStarted(web, "about:blank", null)
        assertEquals("the page's own load runs", 0, stops)
        client.onPageStarted(web, "https://a.example", null)
        assertEquals(1, stops)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aFinishedMessageMakesNoNewWebViewAndKeepsNoActivity() {
        var made = 0
        val shown = present(makeWebView = { made++; WebView(it) })
        shown.view.leave("app")
        assertNull(shown.view.root)
        shown.view.attach(activity)
        assertEquals(1, made)
        assertNull(shown.view.webView)
    }

    @Test
    fun aMessageClosedByTheAppBeforeItShowedCountsAsNeverShown() {
        present().view.leave("app")
        assertEquals(InAppOutcome.Unshown("app"), outcomes.single())
    }
}
