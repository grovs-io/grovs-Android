package io.grovs.inapp.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import com.google.gson.JsonObject
import io.grovs.e2e.TestActivity
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.InAppPresentation
import io.grovs.inapp.model.InAppActionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppHtmlPresenterTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<TestActivity>
    private var current: Activity? = null
    private lateinit var presenter: InAppDialogPresenter
    private val outcomes = mutableListOf<InAppOutcome>()

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        current = controller.get()
        presenter = InAppDialogPresenter(
            activity = { current },
            images = InAppUiFixtures.images(folder.newFolder("img")),
            actions = InAppActions(app, { current }, reviewAvailable = false),
        )
        presenter.register(app)
    }

    @After
    fun tearDown() {
        presenter.unregister(app)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun presentPage(content: JsonObject = JsonObject()): Boolean {
        val presentation = InAppPresentation(InAppHtmlFixtures.campaign("modal", content = content), InAppHtmlFixtures.RENDERED)
        val shown = presenter.present(presentation) { outcomes += it }
        idle()
        return shown
    }

    private fun send(type: String, args: String) {
        val page = presenter.shownPage!!
        val channel = shadowOf(page.webView).getJavascriptInterface(InAppHtmlDocument.CHANNEL) as InAppJavascriptChannel
        channel.postMessage("{\"v\":1,\"token\":\"${page.session.token}\",\"id\":\"1\",\"type\":\"$type\",\"args\":$args}")
        idle()
    }

    private fun showPage() {
        send("ready", "{\"missing\":false}")
        send("height", "{\"value\":80}")
    }

    private fun flags() = presenter.shownPageWindow!!.dialog.window!!.attributes.flags

    @Test
    fun anHtmlCampaignIsDrawnInItsOwnWindowHiddenUntilReady() {
        assertTrue(presentPage())
        assertNull("not the native slot", presenter.shownWindow)
        val window = presenter.shownPageWindow!!
        assertTrue(window.dialog.isShowing)
        assertTrue(flags() and FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags() and FLAG_NOT_FOCUSABLE != 0)
        presenter.dismiss()
        idle()
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Unshown("app")), outcomes)
        assertFalse(window.dialog.isShowing)
        assertNull(presenter.shownPage)
    }

    @Test
    fun anOpenFromThePageClosesThenOpensTheLink() {
        presentPage()
        val window = presenter.shownPageWindow!!
        showPage()
        assertEquals(0, flags() and FLAG_NOT_TOUCHABLE)
        send("open", "{\"url\":\"https://a.example/x\"}")
        val clicked = outcomes.single() as InAppOutcome.Clicked
        assertEquals(InAppActionType.OPEN_URL, clicked.action.type)
        assertEquals("https://a.example/x", clicked.action.url)
        assertFalse(window.dialog.isShowing)
        assertEquals("https://a.example/x", shadowOf(current).nextStartedActivity.dataString)
    }

    @Test
    fun backClosesAShownDismissiblePage() {
        presentPage()
        showPage()
        presenter.shownPageWindow!!.dialog.onBackPressedDispatcher.onBackPressed()
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.single())
    }

    @Test
    fun aRecreatedActivityGetsTheSameWebView() {
        presentPage()
        showPage()
        val web = presenter.shownPage!!.webView
        val before = presenter.shownPageWindow
        controller.recreate()
        current = controller.get()
        idle()
        assertTrue(outcomes.isEmpty())
        assertSame(web, presenter.shownPage!!.webView)
        assertNotSame(before, presenter.shownPageWindow)
        assertTrue(presenter.shownPageWindow!!.dialog.isShowing)
        assertEquals("still shown, so touchable", 0, flags() and FLAG_NOT_TOUCHABLE)
    }

    @Test
    fun theBackgroundKeepsThePage() {
        presentPage()
        showPage()
        controller.pause().stop()
        current = null
        idle()
        assertNull(presenter.shownPageWindow)
        assertNotNull(presenter.shownPage)
        current = controller.get()
        controller.restart().resume()
        idle()
        assertTrue(presenter.shownPageWindow!!.dialog.isShowing)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun turningAwayFromAHeldOrientationClosesThePage() {
        presentPage(JsonObject().apply { addProperty("orientation", "portrait") })
        showPage()
        val land = Configuration(app.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        presenter.onConfigurationChanged(land)
        idle()
        assertEquals(InAppOutcome.Dismissed("orientation"), outcomes.single())
    }

    @Test
    fun aNativeMessageReplacesThePage() {
        presentPage()
        assertTrue(presenter.present(InAppUiFixtures.presentation(InAppUiFixtures.campaign())) { outcomes += it })
        idle()
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Unshown("app")), outcomes)
        assertNull(presenter.shownPage)
        assertNotNull(presenter.shownWindow)
    }

    @Test
    fun aFinishingActivityHasNowhereToDrawAndLeavesNothingBehind() {
        current!!.finish()
        assertFalse(presentPage())
        assertNull(presenter.shownPage)
        assertNull(presenter.shownPageWindow)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aPageWithNoWebViewEndsUnshownAndClosesItsWindow() {
        presenter.unregister(app)
        presenter = InAppDialogPresenter(
            activity = { current },
            images = InAppUiFixtures.images(folder.newFolder("img2")),
            actions = InAppActions(app, { current }, reviewAvailable = false),
            makeWebView = { throw IllegalStateException("WebView is updating") },
        )
        presenter.register(app)
        val presentation = InAppPresentation(InAppHtmlFixtures.campaign("modal"), InAppHtmlFixtures.RENDERED)
        assertTrue(presenter.present(presentation) { outcomes += it })
        val window = presenter.shownPageWindow!!
        idle()
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Unshown("app")), outcomes)
        assertFalse(window.dialog.isShowing)
        assertNull(presenter.shownPage)
        assertNull(presenter.shownPageWindow)
    }

    @Test
    fun theViewIsCountedOnlyOnceThePageShows() {
        var counted = 0
        val presentation = InAppPresentation(InAppHtmlFixtures.campaign("modal"), InAppHtmlFixtures.RENDERED, onShown = { counted++ })
        presenter.present(presentation) { outcomes += it }
        idle()
        assertEquals(0, counted)
        showPage()
        assertEquals(1, counted)
    }

    @Test
    fun anUnshownPageCountsNothing() {
        var counted = 0
        val presentation = InAppPresentation(InAppHtmlFixtures.campaign("modal"), InAppHtmlFixtures.RENDERED, onShown = { counted++ })
        presenter.present(presentation) { outcomes += it }
        idle()
        presenter.dismiss()
        idle()
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Unshown("app")), outcomes)
        assertEquals(0, counted)
    }

    @Test
    fun aPageHeldToTheOtherOrientationIsNotDrawn() {
        assertFalse(presentPage(JsonObject().apply { addProperty("orientation", "landscape") }))
        assertNull(presenter.shownPage)
        assertNull(presenter.shownPageWindow)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aPagePresentedFromTheNativeDismissalReportWins() {
        val reentrant = InAppPresentation(InAppHtmlFixtures.campaign("modal"), InAppHtmlFixtures.RENDERED)
        var presentedAgain = false
        assertTrue(presenter.present(InAppUiFixtures.presentation(InAppUiFixtures.campaign())) {
            outcomes += it
            presentedAgain = presenter.present(reentrant) { outcome -> outcomes += outcome }
        })
        idle()
        assertFalse(presenter.present(InAppUiFixtures.presentation(InAppUiFixtures.campaign())) { outcomes += it })
        idle()
        assertTrue(presentedAgain)
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Dismissed("app")), outcomes)
        assertNotNull(presenter.shownPage)
        assertTrue(presenter.shownPageWindow!!.dialog.isShowing)
        assertNull(presenter.shownSession)
    }
}
