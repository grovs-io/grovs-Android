package io.grovs.handlers

import android.content.Context
import io.grovs.settings.GrovsSettings
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.NetworkMonitor
import io.grovs.utils.WebViewUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class GrovsContext @OptIn(ExperimentalCoroutinesApi::class) constructor(
    /// Runs all SDK work in order. Tests inject a controllable dispatcher here.
    val serialDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    val settings = GrovsSettings()

    @get:JvmSynthetic
    internal val consent: ConsentController get() = settings.consent
    var grovsId: String? = null

    /// What the host app wants the backend to hold for this user. Written on whatever thread the
    /// host likes; each setter below is one atomic update, and GrovsManager syncs every change.
    internal val userAttributes = MutableStateFlow(UserAttributes())

    var identifier: String?
        get() = userAttributes.value.identifier
        set(value) {
            hostSetIdentifier = true
            userAttributes.update { it.copy(identifier = value) }
        }

    var pushToken: String?
        get() = userAttributes.value.pushToken
        set(value) = userAttributes.update { it.copy(pushToken = value) }

    var attributes: Map<String, Any>?
        get() = userAttributes.value.attributes
        set(value) {
            hostSetAttributes = true
            userAttributes.update { it.copy(attributes = value) }
        }

    // Whether the host set a field in this process, so authentication never replaces it with the
    // backend's value. Set before the write, so a concurrent adoption retries and sees it.
    @Volatile
    internal var hostSetIdentifier = false
        private set
    @Volatile
    internal var hostSetAttributes = false
        private set
    @Volatile
    internal var isForeground: Boolean = false

    /// Whether the phone has a network. Null before configure(), which the SDK treats as online.
    @Volatile
    internal var networkMonitor: NetworkMonitor? = null

    /// The configuration whose authentication produced [grovsId]. Changed only by
    /// [markAuthenticated] and [clearAuthentication], so the two always move together.
    @Volatile
    internal var authenticatedConfiguration: ConsentConfiguration? = null
        private set

    /// Records a successful authentication of [configuration] and the device id it returned.
    internal fun markAuthenticated(grovsId: String?, configuration: ConsentConfiguration) {
        this.grovsId = grovsId
        authenticatedConfiguration = configuration
    }

    /// Forgets the device id and the configuration it belongs to; the next one authenticates again.
    internal fun clearAuthentication() {
        authenticatedConfiguration = null
        grovsId = null
    }

    var lastSeen: InstantCompat? = null

    /// The current analytics session. Attached to every event, system and custom.
    var sessionId: String = UUID.randomUUID().toString()
        private set

    /// When the app was last backgrounded, or null if it has not been backgrounded this session.
    var backgroundedAt: InstantCompat? = null
        private set

    /// The session the app was in when it was last backgrounded, so a foreground can tell whether
    /// the session changed even when an earlier caller already rotated it.
    var sessionAtBackground: String? = null
        private set

    companion object {
        /// A background longer than this starts a new session.
        const val SESSION_TIMEOUT_MINUTES = 30L
        private const val SESSION_TIMEOUT_MS = SESSION_TIMEOUT_MINUTES * 60 * 1000
    }

    /// Called when the app moves to the background.
    fun markBackgrounded() {
        backgroundedAt = InstantCompat.now()
        sessionAtBackground = sessionId
    }

    /// Called when the app moves to the foreground. Starts a new session if the app was
    /// backgrounded for longer than the timeout.
    fun rotateSessionIfNeeded() {
        rotateSessionIfExpired()
        backgroundedAt = null
    }

    /// Starts a new session if the background has outlasted the timeout. A shorter background
    /// keeps its clock running, so a later foreground still measures it.
    fun rotateSessionIfExpired() {
        val since = backgroundedAt ?: return
        val elapsed = InstantCompat.now().toEpochMilli() - since.toEpochMilli()
        if (elapsed > SESSION_TIMEOUT_MS) {
            sessionId = UUID.randomUUID().toString()
            backgroundedAt = null
        }
    }

    fun getAppDetails(context: Context): AppDetailsHelper = AppDetailsHelper(context)
    fun getUserAgent(context: Context): String = WebViewUtils.getUserAgent(context)
}

/// The user attributes the backend should hold. Compared by value, so rewriting an unchanged value
/// is not sent again.
internal data class UserAttributes(
    val identifier: String? = null,
    val pushToken: String? = null,
    val attributes: Map<String, Any>? = null,
)
