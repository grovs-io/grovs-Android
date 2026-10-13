package io.grovs.inapp.ui

import android.content.DialogInterface
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import io.grovs.e2e.TestActivity
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.model.InAppActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppAlertTest {
    private val outcomes = mutableListOf<InAppOutcome>()
    private val two = """{"template":"alert","content":{"buttons":[
        {"id":"b1","text":"Open","action":{"type":"open_url","url":"https://acme.example/{{user.plan}}"}},
        {"id":"b2","text":"Later","action":{"type":"close"}}]}}"""

    private fun show(changes: String, host: android.app.Activity = Robolectric.buildActivity(TestActivity::class.java).setup().get(),
                     urls: List<String> = emptyList()): AlertDialog {
        val campaign = InAppUiFixtures.campaign(changes)
        val session = InAppMessageSession(InAppUiFixtures.presentation(campaign, urls = urls)) { outcomes += it }
        assertTrue(InAppAlert(host, session).show())
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun twoButtonsAndCloseMapToPositiveNeutralAndNegative() {
        val dialog = show(two, urls = listOf("https://acme.example/pro"))
        assertEquals("Open", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text)
        assertEquals("Later", dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text)
        assertEquals("Close", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val clicked = outcomes.single() as InAppOutcome.Clicked
        assertEquals("b1", clicked.buttonId)
        assertEquals(InAppActionType.OPEN_URL, clicked.action.type)
        assertEquals("https://acme.example/pro", clicked.action.url)
    }

    @Test
    fun closeIsAlwaysThereWithoutButtons() {
        val dialog = show("""{"template":"alert","content":{"close_button":false}}""")
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.single())
    }

    @Test
    fun closeCanBeLeftOutWhenThereIsAButton() {
        val noClose = """{"template":"alert","content":{"close_button":false,"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}"""
        assertFalse(InAppAlert.hasClose(InAppUiFixtures.campaign(noClose)))
        val undismissible = """{"template":"alert","content":{"dismissible":false,"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}"""
        assertFalse(InAppAlert.hasClose(InAppUiFixtures.campaign(undismissible)))
        val dialog = show(noClose)
        assertTrue(dialog.getButton(DialogInterface.BUTTON_NEGATIVE) == null ||
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility != android.view.View.VISIBLE)
    }

    @Test
    fun backClosesOnlyWhenCloseIsShown() {
        show(two).onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.single())
        outcomes.clear()
        val dialog = show("""{"template":"alert","content":{"close_button":false,"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}""")
        dialog.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(outcomes.isEmpty())
        assertTrue(dialog.isShowing)
    }

    @Test
    fun aTapOutsideDoesNothing() {
        val dialog = show(two)
        assertFalse(shadowOf(dialog).isCancelableOnTouchOutside)
    }

    @Test
    fun alertWorksOnAnActivityWithoutAnAppCompatTheme() {
        val plain = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val dialog = show(two, host = plain)
        assertTrue(dialog.isShowing)
    }

    @Test
    fun closingFromTheAppReportsNothing() {
        val campaign = InAppUiFixtures.campaign(two)
        val session = InAppMessageSession(InAppUiFixtures.presentation(campaign)) { outcomes += it }
        val alert = InAppAlert(Robolectric.buildActivity(TestActivity::class.java).setup().get(), session)
        alert.show()
        alert.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(outcomes.isEmpty())
        assertNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing })
    }
}
