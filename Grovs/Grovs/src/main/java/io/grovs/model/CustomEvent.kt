package io.grovs.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.grovs.utils.InstantCompat
import kotlinx.coroutines.CancellationException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Array as JavaArray
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URL
import java.util.Date
import java.util.IdentityHashMap
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
 * Validation and sanitization rules for custom and screen events.
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
    private const val MAX_PROPERTY_DEPTH = 16
    // Every JSON value needs at least one byte. This also bounds invalid/omitted input work.
    private const val MAX_PROPERTY_NODES = MAX_PROPERTIES_BYTES

    fun isReserved(name: String): Boolean = RESERVED_NAMES.contains(name.trim().lowercase())

    fun isValidName(name: String): Boolean = name.isNotBlank() && !isReserved(name)

    /**
     * Copies JSON-compatible properties, converting Date/URL/UUID values at every depth.
     * An invalid subtree drops its whole top-level property, preserving valid siblings.
     * Oversized input or exhausted traversal budgets drop all properties, never the event.
     */
    fun sanitizeProperties(properties: Map<String, Any>?): Map<String, Any>? =
        PropertySanitizer().sanitize(properties)

    private class InvalidProperty : RuntimeException()
    private class PropertiesTooLarge : RuntimeException()

    private class PropertySanitizer {
        private var remainingNodes = MAX_PROPERTY_NODES
        private val ancestors = IdentityHashMap<Any, Boolean>()

        fun sanitize(properties: Map<String, Any>?): Map<String, Any>? {
            if (properties == null) return null
            return try {
                val clean = linkedMapOf<String, Any>()
                for ((key, value) in properties) {
                    try {
                        checkLength(key)
                        normalize(value, depth = 0)?.let { clean[key] = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PropertiesTooLarge) {
                        throw e
                    } catch (e: Exception) {
                        // Unsupported values or a failing caller-owned collection drop this key.
                    }
                }
                if (clean.isEmpty()) return null

                // Count the actual Gson JSON bytes, including escapes, without allocating an
                // unbounded JSON string/byte array before discovering that it exceeds 8KB.
                OutputStreamWriter(LimitedOutputStream(), Charsets.UTF_8).use {
                    gson.toJson(clean, it)
                }
                clean
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This includes input iteration and serialization failures. No partial map escapes.
                null
            }
        }

        private fun normalize(value: Any?, depth: Int): Any? {
            if (--remainingNodes < 0) throw PropertiesTooLarge()
            return when (value) {
                null -> null
                is String -> value.also(::checkLength)
                is Char -> value.toString()
                is Boolean, is Byte, is Short, is Int, is Long -> value
                is Double -> value.takeIf { it.isFinite() } ?: throw InvalidProperty()
                is Float -> value.takeIf { it.isFinite() } ?: throw InvalidProperty()
                is BigInteger -> {
                    if (value.javaClass != BigInteger::class.java) throw InvalidProperty()
                    if (value.bitLength() > MAX_PROPERTIES_BYTES * 4) throw PropertiesTooLarge()
                    // Stored Map<String, Any> numbers are read back as doubles by Gson.
                    if (!value.toDouble().isFinite()) throw InvalidProperty()
                    value
                }
                is BigDecimal -> {
                    if (value.javaClass != BigDecimal::class.java) throw InvalidProperty()
                    if (value.unscaledValue().bitLength() > MAX_PROPERTIES_BYTES * 4) throw PropertiesTooLarge()
                    if (!value.toDouble().isFinite()) throw InvalidProperty()
                    value
                }
                is Date -> InstantCompat.ofEpochMilli(value.time).toIsoString().also(::checkLength)
                is URL -> value.toString().also(::checkLength)
                is UUID -> value.toString()
                is Map<*, *> -> container(value, depth) {
                    val copy = linkedMapOf<String, Any?>()
                    for ((key, child) in value) {
                        if (key !is String) throw InvalidProperty()
                        checkLength(key)
                        copy[key] = normalize(child, depth + 1)
                    }
                    copy
                }
                is List<*> -> container(value, depth) {
                    val copy = mutableListOf<Any?>()
                    for (child in value) copy.add(normalize(child, depth + 1))
                    copy
                }
                else -> {
                    if (!value.javaClass.isArray) throw InvalidProperty()
                    container(value, depth) {
                        val copy = mutableListOf<Any?>()
                        for (index in 0 until JavaArray.getLength(value)) {
                            copy.add(normalize(JavaArray.get(value, index), depth + 1))
                        }
                        copy
                    }
                }
            }
        }

        private fun container(value: Any, depth: Int, copy: () -> Any): Any {
            if (depth >= MAX_PROPERTY_DEPTH || ancestors.containsKey(value)) throw InvalidProperty()
            ancestors[value] = true
            return try {
                copy()
            } finally {
                ancestors.remove(value)
            }
        }

        private fun checkLength(value: String) {
            if (value.length > MAX_PROPERTIES_BYTES) throw PropertiesTooLarge()
        }
    }

    private class LimitedOutputStream : OutputStream() {
        private var remaining = MAX_PROPERTIES_BYTES

        override fun write(value: Int) = count(1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = count(length)

        private fun count(bytes: Int) {
            if (bytes > remaining) throw PropertiesTooLarge()
            remaining -= bytes
        }
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
