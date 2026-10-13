package io.grovs.inapp.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.widget.EditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.grovs.R
import io.grovs.inapp.InAppImages
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.InAppPresentation
import io.grovs.inapp.InAppPresenter
import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppAnswer
import io.grovs.inapp.model.InAppOrientation
import io.grovs.inapp.model.InAppQuestionType
import io.grovs.inapp.model.InAppTemplate
import java.lang.ref.WeakReference

/**
 * Shows one message at a time above the app's resumed Activity. The message's state outlives the
 * Activity: when it goes away the window is dropped without an outcome, and the next resumed
 * Activity draws the same message again.
 */
internal class InAppDialogPresenter(
    private val activity: () -> Activity?,
    private val images: InAppImages,
    private val actions: InAppActions,
) : InAppActivityCallbacks(), InAppPresenter, ComponentCallbacks {
    /** Called when something the controller does not see ends, so what waits may show. */
    var onIdle: () -> Unit = {}

    private var session: InAppMessageSession? = null
    private var window: InAppWindow? = null
    private var alert: InAppAlert? = null
    private var host: WeakReference<Activity>? = null

    internal val shownSession: InAppMessageSession? get() = session
    internal val shownWindow: InAppWindow? get() = window

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        application.registerComponentCallbacks(this)
    }

    fun unregister(application: Application) {
        application.unregisterActivityLifecycleCallbacks(this)
        application.unregisterComponentCallbacks(this)
    }

    override fun present(presentation: InAppPresentation, completion: (InAppOutcome) -> Unit): Boolean {
        dismiss()
        // The dismissal's report may already have shown something else.
        if (session != null) return false
        if (presentation.campaign.template == InAppTemplate.HTML) return false
        val target = activity() ?: return false
        if (!fits(presentation.campaign.content.orientation, target.resources.configuration)) return false
        val next = InAppMessageSession(presentation) { outcome -> finish(presentation, outcome, completion) }
        session = next
        if (!attach(target, next)) {
            session = null
            return false
        }
        return true
    }

    override fun dismiss() {
        session?.leave("app")
    }

    /** Closes the window, reports, and only then does what the outcome asks for. */
    private fun finish(presentation: InAppPresentation, outcome: InAppOutcome, completion: (InAppOutcome) -> Unit) {
        detach()
        session = null
        completion(outcome)
        when (outcome) {
            is InAppOutcome.Clicked -> perform(outcome.action, presentation)
            is InAppOutcome.Answered -> review(outcome, presentation)
            else -> Unit
        }
    }

    /** Stars at or above `review_from` are followed by the store's review prompt; the controller holds what waits until then. */
    private fun review(outcome: InAppOutcome.Answered, presentation: InAppPresentation) {
        val stars = (outcome.value as? InAppAnswer.Stars)?.number ?: return
        val question = presentation.campaign.content.question ?: return
        val from = question.reviewFrom ?: return
        if (question.type != InAppQuestionType.STARS || stars < from) return
        if (presentation.isTest || !actions.canRequestReview) return onIdle()
        actions.requestReview { onIdle() }
    }

    private fun perform(action: InAppAction, presentation: InAppPresentation) {
        when (action.type) {
            InAppActionType.OPEN_URL -> action.url?.let(actions::open)
            InAppActionType.OPEN_STORE -> presentation.storeUrl?.let(actions::openStore)
            InAppActionType.REQUEST_APP_RATING -> actions.requestReview {}
            InAppActionType.REQUEST_PUSH_PERMISSION -> actions.requestPushPermission {}
            InAppActionType.CLOSE, InAppActionType.KEY_VALUE -> Unit
        }
    }

    private fun attach(target: Activity, current: InAppMessageSession): Boolean {
        if (target.isFinishing || target.isDestroyed) return false
        host = WeakReference(target)
        val shown = if (current.campaign.template == InAppTemplate.ALERT) {
            InAppAlert(target, current).also { alert = it }.show()
        } else {
            attachCard(target, current)
        }
        if (!shown) detach()
        return shown
    }

    private fun attachCard(target: Activity, current: InAppMessageSession): Boolean {
        val night = (target.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val view = InAppCardView(target, current, images, night)
        view.screenHeight = target.window?.decorView?.height?.takeIf { it > 0 } ?: target.resources.displayMetrics.heightPixels
        val template = current.campaign.template
        val edge = when (template) {
            InAppTemplate.HEADER -> InAppWindow.Edge.TOP
            InAppTemplate.FOOTER -> InAppWindow.Edge.BOTTOM
            else -> InAppWindow.Edge.FULL
        }
        val shown = InAppWindow(
            target, view, edge,
            onBack = { if (current.campaign.content.dismissible) current.leave("close_button") },
            onInsets = view::applyInsets,
        ).also { window = it }
        current.redraw = {
            view.rebuild()
            applyStep(shown, view, current)
        }
        // Only cover and header sit under the status bar; a picture's colour is unknown.
        val lightIcons = if (template == InAppTemplate.COVER || template == InAppTemplate.HEADER) {
            InAppStyle.wantsLightIcons(view.palette.card)
        } else null
        if (!shown.show(lightIcons)) return false
        applyStep(shown, view, current)
        return true
    }

    /** A banner takes the keyboard only while its comment box is up; the box gets the focus. */
    private fun applyStep(shown: InAppWindow, view: InAppCardView, current: InAppMessageSession) {
        val commenting = current.step is InAppStep.Comment
        if (current.campaign.template.isBanner) shown.setFocusable(commenting)
        if (!commenting) return
        val box = view.findViewById<EditText>(R.id.grovs_in_app_comment) ?: return
        box.requestFocus()
        shown.dialog.window?.let { WindowCompat.getInsetsController(it, box).show(WindowInsetsCompat.Type.ime()) }
    }

    /** Drops what is drawn without an outcome; the session stays for the next Activity. */
    private fun detach() {
        window?.close()
        window = null
        alert?.close()
        alert = null
        host = null
        session?.redraw = {}
    }

    override fun onActivityResumed(activity: Activity) {
        val current = session ?: return
        if (host?.get() === activity && (window != null || alert != null)) return
        detach()
        if (!fits(current.campaign.content.orientation, activity.resources.configuration)) return current.leave("orientation")
        attach(activity, current)
    }

    override fun onActivityStopped(activity: Activity) {
        if (host?.get() === activity) detach()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host?.get() === activity) detach()
    }

    /** A message held to one orientation closes when the device turns away; the device is never turned for it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        val current = session ?: return
        if (host == null) return
        if (!fits(current.campaign.content.orientation, newConfig)) current.leave("orientation")
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {}

    private fun fits(orientation: InAppOrientation, configuration: Configuration): Boolean {
        val held = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) InAppOrientation.LANDSCAPE
        else InAppOrientation.PORTRAIT
        return orientation.fits(held)
    }
}
