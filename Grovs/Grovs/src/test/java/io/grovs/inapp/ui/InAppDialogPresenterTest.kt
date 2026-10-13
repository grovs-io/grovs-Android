package io.grovs.inapp.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import io.grovs.R
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppDialogPresenterTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<TestActivity>
    private var current: Activity? = null
    private lateinit var presenter: InAppDialogPresenter
    private val outcomes = mutableListOf<InAppOutcome>()
    private val opened = mutableListOf<String>()
    private val events = mutableListOf<String>()

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

    private fun present(changes: String = "{}", presentation: InAppPresentation? = null): Boolean {
        val shown = presenter.present(presentation ?: InAppUiFixtures.presentation(InAppUiFixtures.campaign(changes))) {
            events += "completion"
            outcomes += it
        }
        idle()
        return shown
    }

    private fun shownView(id: Int): View? = presenter.shownWindow?.dialog?.findViewById(id)

    @Test
    fun aCardIsShownInItsOwnWindowOnTheCurrentActivity() {
        assertTrue(present())
        assertTrue(presenter.shownWindow!!.dialog.isShowing)
        assertNotNull(shownView(R.id.grovs_in_app_card))
    }

    @Test
    fun withNoActivityThereIsNowhereToDraw() {
        current = null
        assertFalse(present())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aFinishingActivityIsNowhereToDraw() {
        current!!.finish()
        assertFalse(present())
    }

    @Test
    fun anHtmlCampaignIsNotDrawnHere() {
        assertFalse(present("""{"template":"html","content":{"html":"<p>Hi</p>","layout":"modal"}}"""))
    }

    @Test
    fun theCloseButtonClosesTheWindowThenReports() {
        present()
        val window = presenter.shownWindow!!
        shownView(R.id.grovs_in_app_close)!!.performClick()
        idle()
        assertFalse(window.dialog.isShowing)
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.single())
        assertNull(presenter.shownSession)
    }

    @Test
    fun backClosesADismissibleMessageAndNotAnUndismissibleOne() {
        present()
        presenter.shownWindow!!.dialog.onBackPressedDispatcher.onBackPressed()
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.single())
        outcomes.clear()
        present("""{"content":{"dismissible":false,"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}""")
        presenter.shownWindow!!.dialog.onBackPressedDispatcher.onBackPressed()
        assertTrue(outcomes.isEmpty())
        assertTrue(presenter.shownWindow!!.dialog.isShowing)
    }

    @Test
    fun dismissReportsAnAppDismissal() {
        present()
        presenter.dismiss()
        idle()
        assertEquals(InAppOutcome.Dismissed("app"), outcomes.single())
    }

    @Test
    fun dismissAfterACloseReportsNothingMore() {
        present()
        shownView(R.id.grovs_in_app_close)!!.performClick()
        presenter.dismiss()
        assertEquals(1, outcomes.size)
    }

    @Test
    fun presentingAgainClosesWhatIsShownAsAnAppDismissal() {
        present()
        present("""{"id":"c2"}""")
        assertEquals(InAppOutcome.Dismissed("app"), outcomes.single())
        assertEquals("c2", presenter.shownSession!!.campaign.id)
    }

    @Test
    fun aClickedLinkIsOpenedAfterTheReport() {
        val changes = """{"content":{"buttons":[{"id":"b1","text":"Go","action":{"type":"open_url","url":"https://acme.example/x"}}]}}"""
        val campaign = InAppUiFixtures.campaign(changes)
        present(presentation = InAppUiFixtures.presentation(campaign, urls = listOf("https://acme.example/x")))
        presenter.shownWindow!!.dialog.window!!.decorView.findViewWithTag<View>("grovs-in-app-button-b1").performClick()
        idle()
        assertEquals(InAppActionType.OPEN_URL, (outcomes.single() as InAppOutcome.Clicked).action.type)
        assertEquals("https://acme.example/x", shadowOf(current).nextStartedActivity.dataString)
    }

    @Test
    fun theStoreActionOpensTheSyncedStoreAddress() {
        val changes = """{"content":{"buttons":[{"id":"b1","text":"Rate","action":{"type":"open_store"}}]}}"""
        val store = "https://play.google.com/store/apps/details?id=io.acme"
        present(presentation = InAppUiFixtures.presentation(InAppUiFixtures.campaign(changes), storeUrl = store))
        presenter.shownWindow!!.dialog.window!!.decorView.findViewWithTag<View>("grovs-in-app-button-b1").performClick()
        idle()
        assertEquals(store, shadowOf(current).nextStartedActivity.dataString)
    }

    @Test
    fun activityRecreationRedrawsTheMessageAndReportsNothing() {
        present()
        val before = presenter.shownWindow
        controller.recreate()
        current = controller.get()
        idle()
        assertTrue(outcomes.isEmpty())
        assertNotSame(before, presenter.shownWindow)
        assertTrue(presenter.shownWindow!!.dialog.isShowing)
        assertNotNull(shownView(R.id.grovs_in_app_card))
    }

    @Test
    fun navigationMovesTheMessageToTheNewActivity() {
        present()
        val first = presenter.shownWindow!!
        val next = Robolectric.buildActivity(TestActivity::class.java).setup()
        current = next.get()
        controller.pause().stop()
        idle()
        assertFalse(first.dialog.isShowing)
        assertTrue(presenter.shownWindow!!.dialog.isShowing)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun backgroundAndBackKeepsTheMessage() {
        present()
        controller.pause().stop()
        current = null
        idle()
        assertNull(presenter.shownWindow)
        assertNotNull(presenter.shownSession)
        current = controller.get()
        controller.restart().resume()
        idle()
        assertTrue(presenter.shownWindow!!.dialog.isShowing)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun turningAwayFromAHeldOrientationClosesWithOrientation() {
        present("""{"content":{"orientation":"portrait"}}""")
        val land = Configuration(app.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        presenter.onConfigurationChanged(land)
        idle()
        assertEquals(InAppOutcome.Dismissed("orientation"), outcomes.single())
    }

    @Test
    fun turningDoesNotCloseAMessageForAnyOrientation() {
        present()
        val land = Configuration(app.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        presenter.onConfigurationChanged(land)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun anAlertIsShownAndFollowsTheActivityToo() {
        present("""{"template":"alert"}""")
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        controller.recreate()
        current = controller.get()
        idle()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertTrue(outcomes.isEmpty())
        presenter.dismiss()
        assertEquals(InAppOutcome.Dismissed("app"), outcomes.single())
    }
}
