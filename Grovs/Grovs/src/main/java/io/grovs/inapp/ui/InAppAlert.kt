package io.grovs.inapp.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import io.grovs.R
import io.grovs.inapp.model.InAppCampaign

/** The alert template is the platform's own alert, so it looks and reads like one. */
internal class InAppAlert(private val activity: Activity, private val session: InAppMessageSession) {
    private var dialog: AlertDialog? = null

    fun show(): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val presentation = session.presentation
        val buttons = session.campaign.content.buttons
        fun title(index: Int) = presentation.rendered.buttons.getOrNull(index) ?: buttons[index].text
        val builder = AlertDialog.Builder(themed(activity))
            .setTitle(presentation.rendered.title)
            .setMessage(presentation.rendered.message)
        // The schema allows two buttons: the first is positive, the second neutral, Close negative.
        if (buttons.isNotEmpty()) builder.setPositiveButton(title(0)) { _, _ -> session.clickButton(0) }
        if (buttons.size > 1) builder.setNeutralButton(title(1)) { _, _ -> session.clickButton(1) }
        val close = hasClose(session.campaign)
        if (close) builder.setNegativeButton(R.string.grovs_in_app_close) { _, _ -> session.leave("close_button") }
        builder.setCancelable(close).setOnCancelListener { session.leave("close_button") }
        val created = builder.create()
        created.setCanceledOnTouchOutside(false)
        return try {
            created.show()
            dialog = created
            true
        } catch (e: WindowManager.BadTokenException) {
            false
        }
    }

    fun close() {
        dialog?.takeIf { it.isShowing && it.window?.decorView?.parent != null }?.dismiss()
        dialog = null
    }

    companion object {
        /** With no button, Close is the only way out and is always there. */
        fun hasClose(campaign: InAppCampaign): Boolean =
            campaign.content.buttons.isEmpty() || (campaign.content.dismissible && InAppStyle.closeAsked(campaign))

        /** AppCompat's alert needs an AppCompat theme; a host without one gets AppCompat's own DayNight. */
        private fun themed(activity: Activity): Context {
            val found = activity.theme.resolveAttribute(androidx.appcompat.R.attr.windowActionBar, TypedValue(), true)
            return if (found) activity else ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_DayNight)
        }
    }
}
