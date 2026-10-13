package io.grovs.inapp.ui

/** Which part of a native message is on screen. */
internal sealed class InAppStep {
    object Message : InAppStep()
}
