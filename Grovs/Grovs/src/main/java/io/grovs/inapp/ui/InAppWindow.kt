package io.grovs.inapp.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import io.grovs.R

/** The message's own window above the Activity: full screen, or a banner the app stays usable around. */
internal class InAppWindow(
    private val activity: Activity,
    private val content: View,
    private val edge: Edge,
    onBack: () -> Unit,
    private val onInsets: (safe: Insets, imeBottom: Int) -> Unit,
) {
    enum class Edge { FULL, TOP, BOTTOM }

    val dialog = ComponentDialog(activity, R.style.GrovsInAppWindow)

    init {
        dialog.setCanceledOnTouchOutside(false)
        // Back is the Android form of the escape gesture; a message that cannot be dismissed ignores it.
        dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        dialog.setContentView(content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            deliver(insets)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setWindowInsetsAnimationCallback(content,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    deliver(insets)
                    return insets
                }
            })
    }

    private fun deliver(insets: WindowInsetsCompat) {
        onInsets(
            insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()),
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
        )
    }

    /** `lightStatusIcons` null keeps the app's own status bar look. False when there is nowhere to draw. */
    fun show(lightStatusIcons: Boolean?): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val window = dialog.window ?: return false
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Below Android 11 the keyboard's insets reach an edge-to-edge window only in resize mode.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (edge == Edge.FULL) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        } else {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            window.setGravity(if (edge == Edge.TOP) Gravity.TOP else Gravity.BOTTOM)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        val app = activity.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val own = WindowCompat.getInsetsController(window, window.decorView)
        own.isAppearanceLightStatusBars = lightStatusIcons?.not() ?: (app?.isAppearanceLightStatusBars ?: false)
        own.isAppearanceLightNavigationBars = app?.isAppearanceLightNavigationBars ?: false
        try {
            dialog.show()
        } catch (e: WindowManager.BadTokenException) {
            return false
        }
        // A banner never takes focus, so the system does not announce its window.
        content.post {
            if (dialog.isShowing) content.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        }
        return true
    }

    fun setFocusable(focusable: Boolean) {
        val window = dialog.window ?: return
        if (focusable) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    fun setTouchable(touchable: Boolean) {
        val window = dialog.window ?: return
        if (touchable) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    fun close() {
        if (dialog.isShowing) dialog.dismiss()
    }
}
