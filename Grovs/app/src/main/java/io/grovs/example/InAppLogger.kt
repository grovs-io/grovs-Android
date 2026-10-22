package io.grovs.example

import io.grovs.GrovsInAppListener
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.callCode

object LoggingInAppListener : GrovsInAppListener {

    override fun shouldShowInAppMessage(campaignId: String): Boolean {
        val show = !AppGraph.blockInAppMessages
        if (!show) {
            log(LogState.WARNING, "Blocked $campaignId", "shouldShowInAppMessage returned false",
                callCode("shouldShowInAppMessage", "campaignId" to campaignId))
        }
        return show
    }

    override fun onInAppMessageShown(campaignId: String) =
        log(LogState.OK, "Message $campaignId shown", "", callCode("onInAppMessageShown", "campaignId" to campaignId))

    override fun onInAppMessageClicked(campaignId: String, buttonId: String?, payload: Map<String, Any?>?) =
        log(
            LogState.OK, "Clicked ${buttonId ?: "message"}",
            listOfNotNull(campaignId, payload?.keys?.joinToString()).joinToString(" · "),
            callCode("onInAppMessageClicked", "campaignId" to campaignId, "buttonId" to buttonId, "payload" to payload),
        )

    override fun onInAppMessageDismissed(campaignId: String, reason: String) =
        log(LogState.NEUTRAL, "Dismissed $campaignId", reason,
            callCode("onInAppMessageDismissed", "campaignId" to campaignId, "reason" to reason))

    override fun onInAppMessageAnswered(campaignId: String, value: Any, comment: String?) =
        log(LogState.OK, "Answered $campaignId", listOfNotNull(value.toString(), comment).joinToString(" · "),
            callCode("onInAppMessageAnswered", "campaignId" to campaignId, "value" to value, "comment" to comment))

    override fun onInAppMessageFailed(campaignId: String, message: String) =
        log(LogState.ERROR, "Message $campaignId failed", message,
            callCode("onInAppMessageFailed", "campaignId" to campaignId, "message" to message))

    private fun log(state: LogState, title: String, detail: String, code: String) {
        AppGraph.log.add(LogType.IN_APP, state, title, detail, code)
    }
}
