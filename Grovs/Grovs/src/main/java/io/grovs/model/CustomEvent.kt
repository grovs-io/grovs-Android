package io.grovs.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.grovs.utils.InstantCompat
import java.net.URL
import java.util.Date
import java.util.UUID

/**
 * A consumer-tracked analytics event. Distinct from [Event], which covers the SDK's own
 * lifecycle telemetry (install, app_open, time_spent, ...).
 */
data class CustomEvent(
    @SerializedName("event_id")
    val eventId: String = UUID.randomUUID().toString(),
    @SerializedName("event_name")
    val eventName: String,
    @SerializedName("session_id")
    val sessionId: String? = null,
    @SerializedName("link")
    var link: String? = null,
    @SerializedName("created_at")
    val createdAt: InstantCompat,
    @SerializedName("properties")
    val properties: Map<String, Any>? = null,
    @SerializedName("tags")
    val tags: List<String>? = null,
) {
    // Identity is the event id alone: an app may legitimately fire the same event twice within a
    // millisecond, so name + timestamp cannot identify one.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomEvent) return false
        return eventId == other.eventId
    }

    override fun hashCode(): Int = eventId.hashCode()
}

/**
 * Validation and sanitization rules for custom events. Mirrors the iOS implementation so the
 * two SDKs reject and coerce identically.
 */
object CustomEventRules {

    /** Names owned by the SDK's own telemetry. Consumers may not use them. */
    val RESERVED_NAMES: Set<String> = setOf(
        "view", "open", "install", "reinstall", "app_open",
        "time_spent", "reactivation", "user_referred", "custom", "screen_view",
    )

    const val MAX_PROPERTIES_BYTES = 8192
    const val MAX_TAGS = 20
    const val MAX_TAG_LENGTH = 255

    private val gson = Gson()

    fun isReserved(name: String): Boolean = RESERVED_NAMES.contains(name.trim().lowercase())

    fun isValidName(name: String): Boolean = name.isNotBlank() && !isReserved(name)

    /**
     * Coerces unsupported types, drops non-finite numbers, and enforces the size cap.
     * Returns null if the properties are absent, empty, or exceed the cap.
     */
    fun sanitizeProperties(properties: Map<String, Any>?): Map<String, Any>? {
        if (properties.isNullOrEmpty()) return null

        val coerced = mutableMapOf<String, Any>()
        for ((key, value) in properties) {
            val clean: Any? = when (value) {
                is Double -> if (value.isFinite()) value else null
                is Float -> if (value.isFinite()) value else null
                is Date -> InstantCompat.ofEpochMilli(value.time).toIsoString()
                is URL -> value.toString()
                is UUID -> value.toString()
                else -> value
            }
            if (clean != null) coerced[key] = clean
        }

        if (coerced.isEmpty()) return null

        val serializedBytes = gson.toJson(coerced).toByteArray(Charsets.UTF_8).size
        if (serializedBytes > MAX_PROPERTIES_BYTES) return null

        return coerced
    }

    /** Drops blanks, truncates over-long tags, and caps the count. */
    fun sanitizeTags(tags: List<String>?): List<String>? {
        if (tags.isNullOrEmpty()) return null

        val clean = tags
            .filter { it.isNotBlank() }
            .map { if (it.length > MAX_TAG_LENGTH) it.take(MAX_TAG_LENGTH) else it }
            .distinct()
            .take(MAX_TAGS)

        return clean.ifEmpty { null }
    }

    /** Merges global tags into an event's own tags, deduping and respecting the combined cap. */
    fun mergeTags(eventTags: List<String>?, globalTags: List<String>?): List<String>? {
        val combined = (eventTags.orEmpty() + globalTags.orEmpty())
        return sanitizeTags(combined)
    }
}
