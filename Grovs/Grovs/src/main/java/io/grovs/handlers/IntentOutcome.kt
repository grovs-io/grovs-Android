package io.grovs.handlers

import io.grovs.model.DeeplinkDetails

/**
 * What one intent produced. [details] is what to deliver, if anything. [tappedLink] is the new
 * link this call looked up, or null for a repeated intent, a fingerprint lookup, or a lookup that
 * went stale before it got its answer: those must never touch the pending link. [failure] is set
 * when the tapped link got no answer, classified so the caller knows whether it may heal.
 */
internal class IntentOutcome(
    val details: DeeplinkDetails?,
    val tappedLink: String?,
    val failure: RequestFailure?,
) {
    companion object {
        val NOTHING = IntentOutcome(details = null, tappedLink = null, failure = null)
    }
}
