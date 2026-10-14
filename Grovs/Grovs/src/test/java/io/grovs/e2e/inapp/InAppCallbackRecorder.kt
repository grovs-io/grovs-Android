package io.grovs.e2e.inapp

import android.os.Looper
import io.grovs.GrovsInAppListener
import java.util.concurrent.CopyOnWriteArrayList

/** Records every in-app listener call in order, with the thread it arrived on. */
internal class InAppCallbackRecorder : GrovsInAppListener {
    /** [kind]: should, shown, clicked, dismissed, answered or failed. */
    data class Call(val kind: String, val campaignId: String, val args: List<Any?>, val onMain: Boolean)

    val calls = CopyOnWriteArrayList<Call>()

    /** The answer to shouldShowInAppMessage. */
    @Volatile var veto: (String) -> Boolean = { true }

    private fun record(kind: String, id: String, vararg args: Any?) {
        calls += Call(kind, id, args.toList(), Looper.myLooper() == Looper.getMainLooper())
    }

    fun of(kind: String) = calls.filter { it.kind == kind }

    /** Campaign ids, in the order they were shown. */
    val shown get() = of("shown").map { it.campaignId }
    /** (campaign id, button id or null, payload or null). */
    val clicked get() = of("clicked").map { Triple(it.campaignId, it.args[0] as String?, @Suppress("UNCHECKED_CAST") (it.args[1] as Map<String, Any?>?)) }
    /** (campaign id, reason). */
    val dismissed get() = of("dismissed").map { it.campaignId to it.args[0] as String }
    /** (campaign id, value, comment). */
    val answered get() = of("answered").map { Triple(it.campaignId, it.args[0]!!, it.args[1] as String?) }
    /** (campaign id, message). */
    val failed get() = of("failed").map { it.campaignId to it.args[0] as String }

    override fun shouldShowInAppMessage(campaignId: String): Boolean { record("should", campaignId); return veto(campaignId) }
    override fun onInAppMessageShown(campaignId: String) = record("shown", campaignId)
    override fun onInAppMessageClicked(campaignId: String, buttonId: String?, payload: Map<String, Any?>?) = record("clicked", campaignId, buttonId, payload)
    override fun onInAppMessageDismissed(campaignId: String, reason: String) = record("dismissed", campaignId, reason)
    override fun onInAppMessageAnswered(campaignId: String, value: Any, comment: String?) = record("answered", campaignId, value, comment)
    override fun onInAppMessageFailed(campaignId: String, message: String) = record("failed", campaignId, message)
}
