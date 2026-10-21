package io.grovs.handlers

import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.HttpStatusException
import io.grovs.service.IGrovsService
import io.grovs.storage.PushOpen
import io.grovs.storage.PushOpenStorage
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult

/**
 * Reports campaign opens to push_open. Every open is saved to disk first and removed only once the
 * backend accepts or refuses it, so an open made before authentication, or whose report failed,
 * survives the app closing and is sent after the next authentication or foreground.
 */
internal class PushOpenReporter(
    private val service: IGrovsService,
    private val consent: ConsentController,
    private val storage: PushOpenStorage,
    private val isAuthenticated: () -> Boolean,
) {
    private val lock = Any()

    /** Consent of opens tapped in this run. An open read back from an earlier run has none. */
    private val tapTokens = HashMap<PushOpen, ConsentToken>()
    private val sending = HashSet<PushOpen>()

    /** [sessionId] is the session of the tap; a retry keeps it even after a new session starts. */
    suspend fun onTap(campaignId: String, sessionId: String, token: ConsentToken) {
        val open = PushOpen(campaignId, sessionId, InstantCompat.now())
        if (storage.addIfAbsent(open)) {
            synchronized(lock) { tapTokens[open] = token }
            // Consent withdrawn while saving: its clear may have run before the write landed.
            if (!consent.isCurrent(token)) forget(open)
        }
        reportPending(token)
    }

    /** An open from an earlier run is reported under [token], the consent of the caller. */
    suspend fun reportPending(token: ConsentToken) {
        if (!isAuthenticated()) return
        storage.getAll().forEach { open ->
            if (!synchronized(lock) { sending.add(open) }) return@forEach
            try {
                report(open, token)
            } finally {
                synchronized(lock) { sending.remove(open) }
            }
        }
    }

    fun clear() {
        synchronized(lock) { tapTokens.clear() }
        storage.clear()
    }

    private suspend fun report(open: PushOpen, callerToken: ConsentToken) {
        val tapToken = synchronized(lock) { tapTokens[open] }
        val token = tapToken ?: callerToken
        if (!consent.isCurrent(token)) {
            // An open tapped under withdrawn consent is never sent.
            if (tapToken != null) forget(open)
            return
        }
        val result = service.pushOpen(open.campaignId, open.sessionId)
        val campaignId = open.campaignId
        when {
            result !is LSResult.Error -> {
                DebugLogger.instance.log(LogLevel.INFO, "Push open reported for campaign $campaignId")
                forget(open)
            }
            result.exception is HttpStatusException -> {
                DebugLogger.instance.log(LogLevel.ERROR, "Push open refused for campaign $campaignId: ${result.exception.message}")
                forget(open)
            }
            !consent.isCurrent(token) -> forget(open)
            else -> DebugLogger.instance.log(LogLevel.INFO, "Push open for campaign $campaignId failed, keeping for retry")
        }
    }

    private suspend fun forget(open: PushOpen) {
        synchronized(lock) { tapTokens.remove(open) }
        storage.remove(open)
    }
}
