package io.grovs.inapp.ui

import io.grovs.inapp.model.InAppAnswer

/** Which part of a native message is on screen. */
internal sealed class InAppStep {
    object Message : InAppStep()

    /** `text` is what has been typed so far, kept here so a new Activity shows it again. */
    data class Comment(val answer: InAppAnswer, val prompt: String, val text: String) : InAppStep()

    data class Thanks(val answer: InAppAnswer, val comment: String?) : InAppStep()
}
