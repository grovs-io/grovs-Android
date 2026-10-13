package io.grovs.inapp.ui

import io.grovs.inapp.InAppCancellable
import io.grovs.inapp.InAppMainTimer
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.InAppPresentation
import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppAnswer
import io.grovs.inapp.model.InAppCampaign

/** One message's state, kept outside its views so a new Activity draws the same step. */
internal class InAppMessageSession(
    val presentation: InAppPresentation,
    private val schedule: (delayMillis: Long, work: () -> Unit) -> InAppCancellable = InAppMainTimer::after,
    private val finish: (InAppOutcome) -> Unit,
) {
    val campaign: InAppCampaign get() = presentation.campaign
    var step: InAppStep = InAppStep.Message
    var answer: InAppAnswer? = null
    var comment: String? = null
    var closing: InAppCancellable? = null
    var finished = false
        private set

    /** Draws the current step again in whatever view shows the message now. */
    var redraw: () -> Unit = {}

    /** Reports once, however many ways the message is closed at the same moment. */
    fun complete(outcome: InAppOutcome) {
        if (finished) return
        finished = true
        closing?.cancel()
        closing = null
        finish(outcome)
    }

    /** After an answer the outcome stays the answer; before it, a dismissal for this reason. */
    fun leave(reason: String) {
        complete(answer?.let { InAppOutcome.Answered(it, comment) } ?: InAppOutcome.Dismissed(reason))
    }

    fun clickButton(index: Int) {
        val button = campaign.content.buttons.getOrNull(index) ?: return
        complete(InAppOutcome.Clicked(button.id, filled(button.action, index)))
    }

    fun clickTap() {
        val action = campaign.content.tapAction ?: return
        complete(InAppOutcome.Clicked(null, filled(action, campaign.content.buttons.size)))
    }

    /** Links are listed buttons first, then the tap action; `position` is the action's place in that order. */
    fun filled(action: InAppAction, position: Int): InAppAction {
        if (action.type != InAppActionType.OPEN_URL) return action
        val before = campaign.content.actions.take(position).count { it.type == InAppActionType.OPEN_URL }
        return InAppAction(InAppActionType.OPEN_URL, presentation.rendered.urls.getOrNull(before), null)
    }

    /** One tap answers; a second tap, or one after the message closed, does nothing. */
    fun answer(value: InAppAnswer) {
        val question = campaign.content.question ?: return
        if (answer != null || finished) return
        answer = value
        presentation.onAnswer?.invoke(value)
        if (finished) return
        val prompt = question.prompt(value)
        if (prompt != null) show(InAppStep.Comment(value, prompt, "")) else thank()
    }

    fun draft(text: String) {
        val current = step as? InAppStep.Comment ?: return
        step = current.copy(text = InAppCommentLimit.clip(text))
    }

    /** Told at once, so a comment counts even if the app closes the message during the thanks. */
    fun send() {
        val current = step as? InAppStep.Comment ?: return
        if (finished) return
        val written = current.text.trim()
        comment = if (written.isEmpty()) null else InAppCommentLimit.clip(written)
        comment?.let { presentation.onComment?.invoke(it) }
        if (finished) return
        thank()
    }

    fun skip() {
        if (step is InAppStep.Comment && !finished) thank()
    }

    private fun thank() {
        val given = answer ?: return
        val thanks = campaign.content.question?.thanks
        if (thanks.isNullOrEmpty()) return leave("close_button")
        show(InAppStep.Thanks(given, comment))
        closing = schedule(THANKS_MILLIS) { leave("close_button") }
    }

    private fun show(next: InAppStep) {
        step = next
        redraw()
    }

    companion object {
        const val THANKS_MILLIS = 2_000L
    }
}
