package io.grovs.inapp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppAnswer
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppRendered

internal interface InAppCancellable {
    fun cancel()
}

internal data class InAppPresentation(
    val campaign: InAppCampaign,
    val rendered: InAppRendered,
    /** Sent from the dashboard to look at: nothing it does is counted or followed up. */
    val isTest: Boolean = false,
    /** The app's store page, https only, from the last sync. */
    val storeUrl: String? = null,
    /** At the tap that answers the question, before any comment or thanks. */
    val onAnswer: ((InAppAnswer) -> Unit)? = null,
    /** At Send, with the trimmed comment, never empty. */
    val onComment: ((String) -> Unit)? = null,
    /** html: what grovs.user and grovs.event hold. */
    val user: JsonObject = JsonObject(),
    val event: JsonObject = JsonObject(),
    /** html: the page is ready and visible, so its view counts now. */
    val onShown: (() -> Unit)? = null,
    val onTrack: ((name: String, properties: JsonObject) -> Unit)? = null,
    val onAttribute: ((key: String, value: JsonElement) -> Unit)? = null,
    /** html: asked again just before a slow page is revealed. */
    val canShow: (() -> Boolean)? = null,
)

internal sealed class InAppOutcome {
    data class Clicked(val buttonId: String?, val action: InAppAction) : InAppOutcome()
    /** close_button, backdrop, swipe, app or orientation. */
    data class Dismissed(val reason: String) : InAppOutcome()
    /** The question was answered, however the message closed afterwards. */
    data class Answered(val value: InAppAnswer, val comment: String?) : InAppOutcome()
    /** An html page that never showed (timeout, missing, app): nothing is counted or reported. */
    data class Unshown(val reason: String) : InAppOutcome()
}

internal interface InAppPresenter {
    /** False when there is nowhere to draw: nothing shown, completion never called. Main thread. */
    fun present(presentation: InAppPresentation, completion: (InAppOutcome) -> Unit): Boolean

    /** Closes what is on screen; its completion reports Dismissed("app"). */
    fun dismiss()
}

/** Stands in until a presenter that draws exists: every message reaches the failure callback. */
internal object NoDrawPresenter : InAppPresenter {
    override fun present(presentation: InAppPresentation, completion: (InAppOutcome) -> Unit): Boolean = false
    override fun dismiss() {}
}
