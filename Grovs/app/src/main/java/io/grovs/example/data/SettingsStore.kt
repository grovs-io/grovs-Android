package io.grovs.example.data

import android.content.Context
import androidx.core.content.edit
import io.grovs.example.actions.LinkHistory
import io.grovs.example.actions.LogEntry
import io.grovs.example.actions.decodeLinkEntries
import io.grovs.example.actions.encodeLinkEntries
import io.grovs.model.LogLevel
import org.json.JSONException

const val RECENT_EVENTS = "events"
const val RECENT_SCREENS = "screens"
const val RECENT_TRIGGERS = "triggers"

class SettingsStore(context: Context) : LinkHistory {
    private val prefs = context.getSharedPreferences("grovs_test_app", Context.MODE_PRIVATE)

    var profiles: List<Profile>
        get() = prefs.getString(K_PROFILES, null)
            ?.let { json -> try { decodeProfiles(json) } catch (e: JSONException) { null } }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROFILES
        set(value) = putNow(K_PROFILES, encodeProfiles(value))

    var activeProfileId: String
        get() = prefs.getString(K_ACTIVE, null) ?: profiles.first().id
        set(value) = putNow(K_ACTIVE, value)

    val activeProfile: Profile
        get() = profiles.let { all -> all.firstOrNull { it.id == activeProfileId } ?: all.first() }

    var linkForm: LinkForm
        get() = decodeLinkForm(prefs.getString(K_LINK_FORM, null))
        set(value) = put(K_LINK_FORM, encodeLinkForm(value))

    var eventForm: EventForm
        get() = decodeEventForm(prefs.getString(K_EVENT_FORM, null))
        set(value) = put(K_EVENT_FORM, encodeEventForm(value))

    var screenForm: ScreenForm
        get() = decodeScreenForm(prefs.getString(K_SCREEN_FORM, null))
        set(value) = put(K_SCREEN_FORM, encodeScreenForm(value))

    var purchaseForm: PurchaseForm
        get() = decodePurchaseForm(prefs.getString(K_PURCHASE_FORM, null))
        set(value) = put(K_PURCHASE_FORM, encodePurchaseForm(value))

    var identity: Identity
        get() = decodeIdentity(prefs.getString(K_IDENTITY, null), Identity(deviceName(), DEFAULT_ATTRIBUTES))
        set(value) = put(K_IDENTITY, encodeIdentity(value))

    var globalTags: List<String>
        get() = decodeStrings(prefs.getString(K_GLOBAL_TAGS, null))
        set(value) = put(K_GLOBAL_TAGS, encodeStrings(value))

    var screenAliases: List<KeyValue>
        get() = decodeKeyValues(prefs.getString(K_ALIASES, null)) ?: listOf(KeyValue("MainActivity", "Home", ValueType.TEXT))
        set(value) = put(K_ALIASES, encodeKeyValues(value))

    var excludedScreens: List<String>
        get() = prefs.getString(K_EXCLUDED, null)?.let(::decodeStrings) ?: listOf("SecondActivity")
        set(value) = put(K_EXCLUDED, encodeStrings(value))

    var inAppDebug: Boolean
        get() = prefs.getBoolean(K_IN_APP_DEBUG, true)
        set(value) = prefs.edit { putBoolean(K_IN_APP_DEBUG, value) }

    var debugLevel: LogLevel
        get() = LogLevel.values().firstOrNull { it.name == prefs.getString(K_DEBUG_LEVEL, null) } ?: LogLevel.INFO
        set(value) = put(K_DEBUG_LEVEL, value.name)

    var callStyle: CallStyle
        get() = CallStyle.values().firstOrNull { it.name == prefs.getString(K_CALL_STYLE, null) } ?: CallStyle.COROUTINE
        set(value) = put(K_CALL_STYLE, value.name)

    var triggerEvent: String
        get() = prefs.getString(K_TRIGGER, null) ?: "demo_tapped"
        set(value) = put(K_TRIGGER, value)

    fun recent(field: String): List<String> = decodeStrings(prefs.getString("recent_$field", null))

    fun remember(field: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        put("recent_$field", encodeStrings((listOf(v) + recent(field).filter { it != v }).take(5)))
    }

    override fun load(): List<LogEntry> = decodeLinkEntries(prefs.getString(K_LINKS, null))

    override fun save(entries: List<LogEntry>) = put(K_LINKS, encodeLinkEntries(entries))

    private fun put(key: String, value: String) = prefs.edit { putString(key, value) }

    /** Written at once, so a restart right after cannot lose it. */
    private fun putNow(key: String, value: String) = prefs.edit(commit = true) { putString(key, value) }

    private companion object {
        const val K_PROFILES = "profiles"
        const val K_ACTIVE = "active_profile"
        const val K_LINK_FORM = "link_form"
        const val K_EVENT_FORM = "event_form"
        const val K_SCREEN_FORM = "screen_form"
        const val K_PURCHASE_FORM = "purchase_form"
        const val K_IDENTITY = "identity"
        const val K_GLOBAL_TAGS = "global_tags"
        const val K_ALIASES = "screen_aliases"
        const val K_EXCLUDED = "excluded_screens"
        const val K_IN_APP_DEBUG = "in_app_debug"
        const val K_DEBUG_LEVEL = "debug_level"
        const val K_CALL_STYLE = "call_style"
        const val K_TRIGGER = "trigger_event"
        const val K_LINKS = "link_history"
    }
}
