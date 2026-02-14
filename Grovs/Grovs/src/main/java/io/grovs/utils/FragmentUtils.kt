package io.grovs.utils

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import io.grovs.R

/**
 * Applies system bar insets (status bar, navigation bar) as padding to the given view.
 * This ensures content doesn't go under system bars on Android API 35+ with edge-to-edge display.
 *
 * @param view The view to apply insets to. Defaults to the fragment's root view.
 * @param applyLeft Whether to apply left inset. Defaults to true.
 * @param applyTop Whether to apply top inset. Defaults to true.
 * @param applyRight Whether to apply right inset. Defaults to true.
 * @param applyBottom Whether to apply bottom inset. Defaults to true.
 */
fun Fragment.applySystemBarInsets(
    view: View? = this.view,
    applyLeft: Boolean = true,
    applyTop: Boolean = true,
    applyRight: Boolean = true,
    applyBottom: Boolean = true
) {
    view?.let { v ->
        ViewCompat.setOnApplyWindowInsetsListener(v) { targetView, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            targetView.updatePadding(
                left = if (applyLeft) insets.left else targetView.paddingLeft,
                top = if (applyTop) insets.top else targetView.paddingTop,
                right = if (applyRight) insets.right else targetView.paddingRight,
                bottom = if (applyBottom) insets.bottom else targetView.paddingBottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }
}

fun Fragment.showProgressBar(tag:String? = null, infoText:String? = null) {
    hideProgressBar(tag)

    val layoutInflater = LayoutInflater.from(context!!)
    val view = layoutInflater.inflate(R.layout.loading_view, view as ViewGroup?)
    val infoTextView = view.findViewById<TextView>(R.id.loadingInfoTextView)
    infoText?.let {
        infoTextView.visibility = View.VISIBLE
        infoTextView.text = it
    } ?: run {
        infoTextView.visibility = View.INVISIBLE
    }
    val childView = view?.findViewById<ConstraintLayout>(R.id.progressBarContainer)
    childView?.tag = tag
}

fun Fragment.hideProgressBar(tag:String? = null) {
    val view = view?.findViewById<ConstraintLayout>(R.id.progressBarContainer)
    tag?.let {
        if (view?.tag == tag) {
            (view?.parent as ViewGroup?)?.removeView(view)
        }
    } ?: run {
        (view?.parent as ViewGroup?)?.removeView(view)
    }
}