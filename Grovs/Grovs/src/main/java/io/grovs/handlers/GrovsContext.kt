package io.grovs.handlers

import android.content.Context
import io.grovs.settings.GrovsSettings
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.WebViewUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.UUID

class GrovsContext @OptIn(ExperimentalCoroutinesApi::class) constructor(
    /// Runs all SDK work in order. Tests inject a controllable dispatcher here.
    val serialDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    val settings = GrovsSettings()
    var grovsId: String? = null

    // Written by the host app on whatever thread it likes and read from the SDK's own coroutines,
    // so the write has to be visible to the reader without a lock between them.
    @Volatile
    var identifier: String? = null

    @Volatile
    var pushToken: String? = null

    @Volatile
    var attributes: Map<String, Any>? = null
    var lastSeen: InstantCompat? = null

    /// The current analytics session. Attached to every event, system and custom.
    var sessionId: String = UUID.randomUUID().toString()
        private set

    /// When the app was last backgrounded, or null if it has not been backgrounded this session.
    var backgroundedAt: InstantCompat? = null
        private set

    companion object {
        /// A background longer than this starts a new session.
        const val SESSION_TIMEOUT_MINUTES = 30L
        private const val SESSION_TIMEOUT_MS = SESSION_TIMEOUT_MINUTES * 60 * 1000
    }

    /// Called when the app moves to the background.
    fun markBackgrounded() {
        backgroundedAt = InstantCompat.now()
    }

    /// Called when the app moves to the foreground. Starts a new session if the app was
    /// backgrounded for longer than the timeout.
    fun rotateSessionIfNeeded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null

        val elapsed = InstantCompat.now().toEpochMilli() - since.toEpochMilli()
        if (elapsed > SESSION_TIMEOUT_MS) {
            sessionId = UUID.randomUUID().toString()
        }
    }

    fun getAppDetails(context: Context): AppDetailsHelper = AppDetailsHelper(context)
    fun getUserAgent(context: Context): String = WebViewUtils.getUserAgent(context)
}
