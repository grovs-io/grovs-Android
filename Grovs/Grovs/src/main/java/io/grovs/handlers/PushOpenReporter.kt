package io.grovs.handlers

import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.HttpStatusException
import io.grovs.service.IGrovsService
import io.grovs.utils.LSResult

/**
 * Reports campaign opens to push_open. An open made before authentication, or whose report
 * failed, is held in memory and sent after the next authentication or foreground.
 */
internal class PushOpenReporter(
    private val service: IGrovsService,
    private val consent: ConsentController,
    private val isAuthenticated: () -> Boolean,
) {
    private class Pending(val sessionId: String, val failures: Int, val token: ConsentToken)

    private val pending = LinkedHashMap<String, Pending>()

    /** [sessionId] is the session of the tap; a retry keeps it even after a new session starts. */
    suspend fun onTap(campaignId: String, sessionId: String, token: ConsentToken) {
        hold(campaignId, Pending(sessionId, failures = 0, token = token))
        reportPending()
    }

    suspend fun reportPending() {
        if (!isAuthenticated()) return
        val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
        batch.forEach { (campaignId, open) -> report(campaignId, open) }
    }

    fun clear() {
        synchronized(pending) { pending.clear() }
    }

    private suspend fun report(campaignId: String, open: Pending) {
        if (!consent.isCurrent(open.token)) return
        val result = service.pushOpen(campaignId, open.sessionId)
        if (result !is LSResult.Error) {
            DebugLogger.instance.log(LogLevel.INFO, "Push open reported for campaign $campaignId")
            return
        }
        val failures = open.failures + 1
        when {
            result.exception is HttpStatusException ->
                DebugLogger.instance.log(LogLevel.ERROR, "Push open refused for campaign $campaignId: ${result.exception.message}")
            !consent.isCurrent(open.token) -> Unit
            failures >= MAX_ATTEMPTS ->
                DebugLogger.instance.log(LogLevel.ERROR, "Push open for campaign $campaignId dropped after $failures attempts")
            else -> hold(campaignId, Pending(open.sessionId, failures, open.token))
        }
    }

    /** Keeps the first entry, so a newer tap never resets an older one's failure count. */
    private fun hold(campaignId: String, open: Pending) {
        // Not putIfAbsent: that is API 24 and minSdk is 21.
        synchronized(pending) { if (!pending.containsKey(campaignId)) pending[campaignId] = open }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
