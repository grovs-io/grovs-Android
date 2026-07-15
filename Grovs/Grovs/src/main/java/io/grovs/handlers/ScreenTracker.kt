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
 */
internal class ScreenTracker(
    private val customEventsManager: ICustomEventsManager,
) {

    private var aliases: Map<String, String> = emptyMap()
    private var lastDedupKey: String? = null
    private var lastScreenAt: InstantCompat? = null

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
     */
    suspend fun trackScreen(
        rawName: String,
        properties: Map<String, Any>? = null,
        dedupKey: String? = null,
    ) {
        if (shouldSkip(rawName)) return

        val resolved = aliases[rawName] ?: rawName
        val key = if (dedupKey != null && !aliases.containsKey(rawName)) dedupKey else resolved

        if (isDuplicate(key)) {
            DebugLogger.instance.log(
                LogLevel.INFO,
                "Skipping duplicate screen view within dedup window: $resolved"
            )
            return
        }

        lastDedupKey = key
        lastScreenAt = InstantCompat.now()

        customEventsManager.track(
            name = SCREEN_VIEW_EVENT,
            properties = properties.orEmpty() + (SCREEN_NAME_PROPERTY to resolved),
            tags = null,
        )
    }

    /** Called on session rotation so the first screen of a new session always fires. */
    fun resetDedup() {
        lastDedupKey = null
        lastScreenAt = null
    }

    private fun isDuplicate(key: String): Boolean {
        if (lastDedupKey != key) return false
        val at = lastScreenAt ?: return false
        return (InstantCompat.now().toEpochMilli() - at.toEpochMilli()) < DEDUP_WINDOW_MS
    }
}
