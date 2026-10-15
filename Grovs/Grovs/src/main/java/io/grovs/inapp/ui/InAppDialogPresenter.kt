package io.grovs.inapp.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
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
    private val makeWebView: (Context) -> WebView = InAppHtmlView.NEW_WEB_VIEW,
) : InAppActivityCallbacks(), InAppPresenter, ComponentCallbacks {
    /** Called when something the controller does not see ends, so what waits may show. */
    var onIdle: () -> Unit = {}

    private var session: InAppMessageSession? = null
    private var window: InAppWindow? = null
    private var alert: InAppAlert? = null
    private var host: WeakReference<Activity>? = null
    /** The shown window raised the keyboard for its comment box. */
    private var typing = false
    private var page: InAppHtmlView? = null
    private var pageWindow: InAppWindow? = null
    private var pageHost: WeakReference<Activity>? = null

    internal val shownSession: InAppMessageSession? get() = session
    internal val shownWindow: InAppWindow? get() = window
    internal val shownPage: InAppHtmlView? get() = page
    internal val shownPageWindow: InAppWindow? get() = pageWindow

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
        if (session != null || page != null) return false
        if (presentation.campaign.template == InAppTemplate.HTML) return presentPage(presentation, completion)
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
        // A report may present again; what it presents must survive this dismissal.
        val native = session
        val html = page
        native?.leave("app")
        html?.leave("app")
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
        if (presentation.isTest || outcome.byApp || !actions.canRequestReview) return onIdle()
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
        // Hidden while the window still has focus, so a banner gives it up after.
        if (typing && !commenting) hideKeyboard(shown)
        typing = commenting
        if (current.campaign.template.isBanner) shown.setFocusable(commenting)
        if (!commenting) return
        val box = view.findViewById<EditText>(R.id.grovs_in_app_comment) ?: return
        box.requestFocus()
        shown.dialog.window?.let { WindowCompat.getInsetsController(it, box).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun hideKeyboard(shown: InAppWindow) {
        val token = shown.dialog.window?.decorView?.windowToken ?: return
        val keyboard = shown.dialog.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        keyboard?.hideSoftInputFromWindow(token, 0)
    }

    /** Drops what is drawn without an outcome; the session stays for the next Activity. */
    private fun detach() {
        window?.let { if (typing) hideKeyboard(it) }
        typing = false
        window?.close()
        window = null
        alert?.close()
        alert = null
        host = null
        session?.redraw = {}
    }

    override fun onActivityResumed(activity: Activity) {
        page?.let { return resumePage(activity, it) }
        val current = session ?: return
        if (host?.get() === activity && (window != null || alert != null)) return
        detach()
        if (!fits(current.campaign.content.orientation, activity.resources.configuration)) return current.leave("orientation")
        attach(activity, current)
    }

    override fun onActivityStopped(activity: Activity) {
        if (host?.get() === activity) detach()
        if (pageHost?.get() === activity) detachPage()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host?.get() === activity) detach()
        if (pageHost?.get() === activity) detachPage()
    }

    /** A message held to one orientation closes when the device turns away; the device is never turned for it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        page?.let {
            if (pageHost != null && !fits(it.orientation, newConfig)) it.leave("orientation")
            return
        }
        val current = session ?: return
        if (host == null) return
        if (!fits(current.campaign.content.orientation, newConfig)) current.leave("orientation")
    }

    // html: one page in its own window, following the Activity like the native session.

    private fun presentPage(presentation: InAppPresentation, completion: (InAppOutcome) -> Unit): Boolean {
        val target = activity() ?: return false
        if (!fits(presentation.campaign.content.orientation, target.resources.configuration)) return false
        val next = InAppHtmlView(presentation, actions, finish = { outcome -> finishPage(presentation, outcome, completion) }, makeWebView = makeWebView)
        page = next
        if (!attachPage(target, next)) {
            page = null
            next.discard()
            return false
        }
        return true
    }

    private fun attachPage(target: Activity, current: InAppHtmlView): Boolean {
        if (target.isFinishing || target.isDestroyed) return false
        val shown = InAppWindow(target, current.attach(target), current.edge, onBack = current::onBack, onInsets = current::applyInsets)
        // Takes no touch or focus from the app until bind() applies the page's own state.
        shown.setTouchable(false)
        shown.setFocusable(false)
        if (!shown.show(current.lightStatusIcons)) {
            current.detach()
            return false
        }
        current.bind(shown)
        pageWindow = shown
        pageHost = WeakReference(target)
        return true
    }

    /** Drops the page's window without an outcome; the page and its WebView wait for the next Activity. */
    private fun detachPage() {
        pageWindow?.let(::hideKeyboard)
        page?.detach()
        pageWindow?.close()
        pageWindow = null
        pageHost = null
    }

    private fun resumePage(activity: Activity, current: InAppHtmlView) {
        if (pageHost?.get() === activity && pageWindow != null) return
        detachPage()
        if (!fits(current.orientation, activity.resources.configuration)) return current.leave("orientation")
        attachPage(activity, current)
    }

    /** Closes the window, reports, and only then does what the page asked for. */
    private fun finishPage(presentation: InAppPresentation, outcome: InAppOutcome, completion: (InAppOutcome) -> Unit) {
        detachPage()
        page = null
        completion(outcome)
        if (outcome is InAppOutcome.Clicked) perform(outcome.action, presentation)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {}

    private fun fits(orientation: InAppOrientation, configuration: Configuration): Boolean {
        val held = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) InAppOrientation.LANDSCAPE
        else InAppOrientation.PORTRAIT
        return orientation.fits(held)
    }
}
