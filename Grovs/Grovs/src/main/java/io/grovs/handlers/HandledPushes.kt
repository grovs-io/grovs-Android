package io.grovs.handlers

import android.content.Intent
import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap

/**
 * Pushes already delivered or dropped, so a re-read launcher intent is not handled twice. Intents
 * are matched by identity; message ids of delivered pushes also survive process death.
 */
internal class HandledPushes(private val prefs: () -> SharedPreferences?) {

    private val intents: MutableSet<Intent> = Collections.newSetFromMap(WeakHashMap())
    private val messageIds = LinkedHashSet<String>()

    @Synchronized
    fun contains(push: GrovsPushPayload, intent: Intent): Boolean {
        if (intent in intents) return true
        val id = push.messageId ?: return false
        return id in messageIds || id in stored()
    }

    @Synchronized
    fun markHandled(push: GrovsPushPayload, intent: Intent) {
        ignore(push, intent)
        val id = push.messageId ?: return
        val ids = (stored() - id + id).takeLast(MAX_STORED)
        prefs()?.edit()?.putString(KEY, ids.joinToString(SEPARATOR))?.apply()
    }

    /** For a push dropped while consent is withdrawn: nothing is written to disk then. */
    @Synchronized
    fun ignore(push: GrovsPushPayload, intent: Intent) {
        intents += intent
        push.messageId?.let { messageIds += it }
    }

    @Synchronized
    fun reset() {
        intents.clear()
        messageIds.clear()
    }

    private fun stored(): List<String> =
        prefs()?.getString(KEY, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        const val PREFS_NAME = "grovs_prefs"
        const val MAX_STORED = 50
        private const val KEY = "handled_push_message_ids"
        private const val SEPARATOR = "\n"
    }
}
