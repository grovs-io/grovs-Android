package io.grovs.handlers

import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.utils.InstantCompat

/**
 * Emits screen_view events for Activities and Fragments, driven by the lifecycle hooks in Grovs.kt.
 *
 * The dedup window guards against the same screen being reported twice in quick succession (a
 * transient pause/resume, a Fragment re-attached without navigation). It does NOT deduplicate an
 * Activity against a Fragment it hosts — those have different screen names. Grovs.kt handles that.
 *
 * A configuration change re-reports the screen on display without any time bound, so two rules sit
 * above the window: a visit reported again under the same [visitId] (a restored NavController back
 * stack entry) is never new, and a host destroyed for a configuration change ([expectRecreationOf])
 * gets its replacement's first report of the same screen suppressed once.
 */
internal class ScreenTracker(
    private val customEventsManager: ICustomEventsManager,
) {

    private var aliases: Map<String, String> = emptyMap()
    private var lastConsentGeneration: Long? = null
    private var lastDedupKey: String? = null
    private var lastVisitId: String? = null
    private var lastScreenAt: InstantCompat? = null
    private val recreatingKeys = mutableSetOf<String>()

    companion object {
        const val SCREEN_VIEW_EVENT = "screen_view"
        const val SCREEN_NAME_PROPERTY = "screen_name"

        /** Suppresses the same screen being reported twice in quick succession. */
        const val DEDUP_WINDOW_MS = 1_000L

        /** The SDK's own UI must never appear in a consumer's screen analytics. */
        private val SDK_SCREENS = setOf(
            "NotificationsMainFragment",
            "NotificationsListFragment",
            "NotificationDetailsFragment",
            "AutoDisplayedNotificationFragment",
        )
    }

    /** True if this class is the SDK's own UI and must not be tracked. */
    fun shouldSkip(className: String): Boolean = SDK_SCREENS.contains(className)

    /** Maps class names to friendly dashboard names. Syncing to the backend is handled by [GrovsManager]. */
    fun setAliases(aliases: Map<String, String>) {
        this.aliases = aliases
    }

    /**
     * Emits a screen_view event, unless the same screen was already tracked within the dedup window.
     *
     * [dedupKey] disambiguates the dedup window by screen *identity* rather than display name. Auto
     * tracking passes the resolved class's fully-qualified name, so two distinct screens that happen
     * to share a simpleName (e.g. same-named tab fragments in different packages) are not wrongly
     * collapsed. When the screen is aliased (aliases are keyed by simpleName, so an aliased screen is
     * intentionally treated as one screen) or no key is supplied (manual [trackScreen] via
     * trackScreenView), the resolved name is used as the key — preserving prior behavior.
     *
     * [visitId] identifies one visit of the screen (a NavController back stack entry id). The same
     * visit reported again is the screen being restored, not viewed anew, however much time passed.
     *
     * [at] is when the screen was observed. Both the event's time and the dedup window use it, so
     * a backlog on the tracking queue neither shifts the event nor collapses two distinct visits.
     */
    suspend fun trackScreen(
        rawName: String,
        properties: Map<String, Any>? = null,
        dedupKey: String? = null,
        consentGeneration: Long? = null,
        visitId: String? = null,
        at: InstantCompat = InstantCompat.now(),
    ) {
        if (consentGeneration != null && consentGeneration != lastConsentGeneration) {
            resetDedup()
            lastConsentGeneration = consentGeneration
        }
        if (shouldSkip(rawName)) return

        val resolved = aliases[rawName] ?: rawName
        val key = if (dedupKey != null && !aliases.containsKey(rawName)) dedupKey else resolved
        val recreated = recreatingKeys.remove(key)

        if (isDuplicate(key, visitId, recreated, at)) {
            DebugLogger.instance.log(
                LogLevel.INFO,
                "Skipping duplicate screen view within dedup window: $resolved"
            )
            return
        }

        lastDedupKey = key
        lastVisitId = visitId
        lastScreenAt = at

        customEventsManager.track(
            name = SCREEN_VIEW_EVENT,
            properties = properties.orEmpty() + (SCREEN_NAME_PROPERTY to resolved),
            tags = null,
            createdAt = at,
        )
    }

    /**
     * Called when the host showing the screen keyed [dedupKey] is destroyed for a configuration
     * change. Its replacement re-reports that screen; if it is still the last one tracked, that
     * report is suppressed once. The mark is consumed by the next report of the same key.
     */
    fun expectRecreationOf(dedupKey: String) {
        recreatingKeys.add(dedupKey)
    }

    /** Called on session rotation so the first screen of a new session always fires. */
    fun resetDedup() {
        lastDedupKey = null
        lastVisitId = null
        lastScreenAt = null
        recreatingKeys.clear()
    }

    private fun isDuplicate(key: String, visitId: String?, recreated: Boolean, at: InstantCompat): Boolean {
        if (lastDedupKey != key) return false
        if (visitId != null && visitId == lastVisitId) return true
        if (recreated) return true
        val previous = lastScreenAt ?: return false
        return (at.toEpochMilli() - previous.toEpochMilli()) < DEDUP_WINDOW_MS
    }
}
