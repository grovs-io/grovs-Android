package io.grovs.inapp.ui

import android.app.Activity
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppWindowTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity: Activity = controller.get()
    private var backs = 0
    private var insets: Pair<Insets, Int>? = null

    private fun window(edge: InAppWindow.Edge, content: View = View(activity)) =
        InAppWindow(activity, content, edge, onBack = { backs++ }, onInsets = { s, ime -> insets = s to ime })

    private fun insetsController(window: InAppWindow) =
        WindowCompat.getInsetsController(window.dialog.window!!, window.dialog.window!!.decorView)

    private fun flags(window: InAppWindow) = window.dialog.window!!.attributes.flags

    @Test
    fun aFullScreenWindowFillsTheScreenTakesTouchesAndDoesNotDim() {
        val window = window(InAppWindow.Edge.FULL)
        assertTrue(window.show(lightStatusIcons = null))
        val attributes = window.dialog.window!!.attributes
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, attributes.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, attributes.height)
        assertEquals(0, flags(window) and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        assertEquals(0, flags(window) and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= 28) {
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, attributes.layoutInDisplayCutoutMode)
        }
        val resize = attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        assertEquals(Build.VERSION.SDK_INT < 30, resize == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    @Test
    fun aTopBannerSitsAtTheTop() {
        val window = window(InAppWindow.Edge.TOP)
        window.show(lightStatusIcons = null)
        assertEquals(android.view.Gravity.TOP, window.dialog.window!!.attributes.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK)
    }

    @Test
    fun aBannerWindowWrapsAtItsEdgeAndLetsTouchesThrough() {
        val window = window(InAppWindow.Edge.BOTTOM)
        window.show(lightStatusIcons = null)
        val attributes = window.dialog.window!!.attributes
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, attributes.height)
        assertEquals(android.view.Gravity.BOTTOM, attributes.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK)
        assertTrue(flags(window) and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags(window) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        window.setFocusable(true)
        assertEquals(0, flags(window) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    @Test
    fun backIsAlwaysTakenByTheMessage() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = null)
        window.dialog.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, backs)
        assertTrue(window.dialog.isShowing)
    }

    @Test
    fun insetsReachTheContentAndAreConsumed() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = null)
        val given = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
            .build()
        val content = window.dialog.findViewById<View>(android.R.id.content)
        ViewCompat.dispatchApplyWindowInsets((content as android.view.ViewGroup).getChildAt(0), given)
        assertEquals(Insets.of(0, 24, 0, 48), insets!!.first)
        // Below API 30 built insets cannot carry the keyboard; it is read from the root view instead.
        if (Build.VERSION.SDK_INT >= 30) assertEquals(300, insets!!.second)
    }

    @Test
    fun theStatusBarIconsFollowTheCardWhenAsked() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = true)
        val controller = WindowCompat.getInsetsController(window.dialog.window!!, window.dialog.window!!.decorView)
        assertFalse(controller.isAppearanceLightStatusBars)
    }

    @Test
    fun darkStatusIconsWhenTheCardIsLight() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = false)
        assertTrue(insetsController(window).isAppearanceLightStatusBars)
    }

    @Test
    fun withoutACardColourTheStatusBarKeepsTheAppsLook() {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = true
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = null)
        assertTrue(insetsController(window).isAppearanceLightStatusBars)
    }

    @Test
    fun closeDismissesWithoutAnyCallback() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = null)
        window.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(window.dialog.isShowing)
        assertEquals(0, backs)
    }

    @Test
    fun aFinishingActivityHasNowhereToDraw() {
        activity.finish()
        assertFalse(window(InAppWindow.Edge.FULL).show(lightStatusIcons = null))
    }

    @Test
    fun aDestroyedActivityHasNowhereToDraw() {
        controller.pause().stop().destroy()
        assertFalse(window(InAppWindow.Edge.FULL).show(lightStatusIcons = null))
    }

    @Test
    fun closeAfterTheActivityIsDestroyedDoesNotThrow() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = null)
        controller.pause().stop().destroy()
        // What the framework does with windows an Activity leaks on destroy.
        activity.windowManager.removeViewImmediate(window.dialog.window!!.decorView)
        window.close()
        assertEquals(0, backs)
    }

    @Test
    @Config(sdk = [28])
    fun belowAndroid11TheKeyboardIsReadFromTheResizedWindow() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = null)
        @Suppress("DEPRECATION")
        val resized = WindowInsetsCompat.Builder().setSystemWindowInsets(Insets.of(0, 24, 0, 300)).build()
        val content = window.dialog.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.dispatchApplyWindowInsets(content, resized)
        assertEquals(300, insets!!.second)
    }

    @Test
    fun aBannerIsAnnouncedOnceItIsShown() {
        assertEquals(listOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED), announcedOnShow(InAppWindow.Edge.BOTTOM))
    }

    @Test
    fun aFullScreenWindowIsLeftToTheSystemToAnnounce() {
        assertEquals(emptyList<Int>(), announcedOnShow(InAppWindow.Edge.FULL))
    }

    private fun announcedOnShow(edge: InAppWindow.Edge): List<Int> {
        val announced = mutableListOf<Int>()
        val content = View(activity)
        content.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                announced += eventType
            }
        }
        window(edge, content).show(lightStatusIcons = null)
        shadowOf(Looper.getMainLooper()).idle()
        return announced
    }

    @Test
    fun aWindowCanStopTakingTouches() {
        val window = window(InAppWindow.Edge.FULL)
        window.show(lightStatusIcons = null)
        window.setTouchable(false)
        assertTrue(flags(window) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        window.setTouchable(true)
        assertEquals(0, flags(window) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
}
