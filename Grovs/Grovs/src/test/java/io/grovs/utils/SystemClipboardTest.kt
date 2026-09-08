package io.grovs.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.grovs.handlers.ActivityProvider
import io.grovs.GrovsNotificationsListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SystemClipboardTest {

    private lateinit var context: Context
    private lateinit var manager: ClipboardManager
    private lateinit var clipboard: SystemClipboard

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard = SystemClipboard(context = context, activityProvider = null)
    }

    @Test
    fun `describe reports NO_CONTENT on an empty clipboard`() = runTest {
        assertEquals(ClipDescriptionResult.NO_CONTENT, clipboard.describe())
    }

    @Test
    fun `describe reports MAYBE_URL for plain text below API 31`() = runTest {
        manager.setPrimaryClip(ClipData.newPlainText("label", "https://x.sqd.link/abc?gd=1"))
        assertEquals(ClipDescriptionResult.MAYBE_URL, clipboard.describe())
    }

    @Test
    fun `describe reports NOT_URL for a non-text mime type`() = runTest {
        manager.setPrimaryClip(ClipData.newIntent("label", android.content.Intent("x")))
        assertEquals(ClipDescriptionResult.NOT_URL, clipboard.describe())
    }

    @Test
    fun `readText returns the primary clip text`() {
        manager.setPrimaryClip(ClipData.newPlainText("label", "https://x.sqd.link/abc?gd=1"))
        assertEquals("https://x.sqd.link/abc?gd=1", clipboard.readText())
    }

    @Test
    fun `readText falls back to the item uri`() {
        manager.setPrimaryClip(ClipData.newRawUri("label", android.net.Uri.parse("https://x.sqd.link/u?gd=1")))
        assertEquals("https://x.sqd.link/u?gd=1", clipboard.readText())
    }

    @Test
    fun `clear empties the clipboard on API 28`() {
        manager.setPrimaryClip(ClipData.newPlainText("label", "text"))
        clipboard.clear()
        assertTrue(!manager.hasPrimaryClip() || manager.primaryClip?.getItemAt(0)?.text.isNullOrEmpty())
    }

    @Test
    @Config(sdk = [26])
    fun `clear replaces the clipboard with an empty clip below API 28`() {
        manager.setPrimaryClip(ClipData.newPlainText("label", "text"))
        clipboard.clear()
        assertTrue(manager.primaryClip?.getItemAt(0)?.text.isNullOrEmpty())
    }

    @Test
    fun `awaitAccess is unconditionally true below API 29`() = runTest {
        // sdk 28 in this class: access is unconditional. The API 29 branch is exercised below.
        assertTrue(clipboard.awaitAccess(timeoutMs = 10))
    }

    @Test
    @Config(sdk = [29])
    fun `awaitAccess without an activity provider is false on API 29`() = runTest {
        assertEquals(false, clipboard.awaitAccess(timeoutMs = 10))
    }

    // On API 29+ hasPrimaryClip() is focus-gated exactly like the description and the read, so an
    // empty answer must not be reported as NO_CONTENT unless a focused window proves it.

    @Test
    @Config(sdk = [29])
    fun `describe reports INACCESSIBLE with no clip and no window focus on API 29`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.windowFocusChanged(false)
        val focusless = SystemClipboard(context, FixedActivityProvider(controller.get()))

        assertEquals(ClipDescriptionResult.INACCESSIBLE, focusless.describe())
    }

    @Test
    @Config(sdk = [29])
    fun `describe reports NO_CONTENT with no clip while the window has focus on API 29`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.windowFocusChanged(true)
        val focused = SystemClipboard(context, FixedActivityProvider(controller.get()))

        assertEquals(ClipDescriptionResult.NO_CONTENT, focused.describe())
    }

    @Test
    @Config(sdk = [29])
    fun `describe reports INACCESSIBLE with no clip and no activity on API 29`() = runTest {
        val noActivity = SystemClipboard(context, FixedActivityProvider(null))

        assertEquals(ClipDescriptionResult.INACCESSIBLE, noActivity.describe())
    }

    @Test
    @Config(sdk = [29])
    fun `a real window focus callback resumes a waiting clipboard read`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            controller.windowFocusChanged(false)
            manager.setPrimaryClip(ClipData.newPlainText("referral", "https://demo.sqd.link/a?gd=1"))
            val clipboard = SystemClipboard(context, FixedActivityProvider(controller.get()))
            val waiting = async { clipboard.awaitAccess(3_000) }
            runCurrent()
            assertEquals(false, waiting.isCompleted)

            controller.windowFocusChanged(true)
            runCurrent()
            assertTrue("A window focus event must wake the installed listener", waiting.await())
            assertEquals("https://demo.sqd.link/a?gd=1", clipboard.readText())
        } finally {
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    private class FixedActivityProvider(private val activity: Activity?) : ActivityProvider {
        override fun requireActivity(): Activity? = activity
        override fun requireNotificationsListener(): GrovsNotificationsListener? = null
    }
}
