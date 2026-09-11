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
     * Copies JSON-compatible properties, converting Date/URL/UUID values at every depth. A value
     * JSON cannot represent is dropped on its own - its list element, its map entry or its
     * top-level property - and everything else is kept. Dropped top-level properties are logged.
     * Oversized input or an exhausted traversal budget drops all properties, never the event.
     */
    fun sanitizeProperties(properties: Map<String, Any>?): Map<String, Any>? =
        PropertySanitizer().sanitize(properties)

    private class PropertiesTooLarge : RuntimeException()

    /** Marks a value JSON cannot represent. Distinct from null, which is a valid JSON value. */
    private object Dropped

    private class PropertySanitizer {
        private var remainingNodes = MAX_PROPERTY_NODES
        private val ancestors = IdentityHashMap<Any, Boolean>()

        fun sanitize(properties: Map<String, Any>?): Map<String, Any>? {
            if (properties == null) return null
            val clean = linkedMapOf<String, Any>()
            val dropped = mutableListOf<String>()
            try {
                for ((key, value) in properties) {
                    checkLength(key)
                    when (val safe = normalize(value, depth = 0)) {
                        null -> Unit
                        Dropped -> dropped += key
                        else -> clean[key] = safe
                    }
                }
                // Count the actual Gson JSON bytes, including escapes, without allocating an
                // unbounded JSON string/byte array before discovering that it exceeds 8KB.
                if (clean.isNotEmpty()) {
                    OutputStreamWriter(LimitedOutputStream(), Charsets.UTF_8).use { gson.toJson(clean, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PropertiesTooLarge) {
                DebugLogger.instance.log(
                    LogLevel.ERROR,
                    "Custom event properties exceed $MAX_PROPERTIES_BYTES bytes; dropping properties.",
                )
                return null
            } catch (e: Exception) {
                // The caller's map failed while it was being read. No partial map escapes.
                DebugLogger.instance.log(LogLevel.ERROR, "Custom event properties could not be read; dropping properties.")
                return null
            }
            if (dropped.isNotEmpty()) {
                DebugLogger.instance.log(
                    LogLevel.ERROR,
                    "Dropped non-serializable custom event property value(s) for key(s): ${dropped.sorted().joinToString(", ")}",
                )
            }
            return clean.ifEmpty { null }
        }

        /** A JSON-safe copy of [value], null for a JSON null, or [Dropped] when JSON cannot represent it. */
        private fun normalize(value: Any?, depth: Int): Any? {
            if (--remainingNodes < 0) throw PropertiesTooLarge()
            return when (value) {
                null -> null
                is String -> value.also(::checkLength)
                is Char -> value.toString()
                is Boolean, is Byte, is Short, is Int, is Long -> value
                is Double -> if (value.isFinite()) value else Dropped
                is Float -> if (value.isFinite()) value else Dropped
                // Stored Map<String, Any> numbers are read back as doubles by Gson, so a big number
                // must stay finite as a double.
                is BigInteger -> when {
                    value.javaClass != BigInteger::class.java -> Dropped
                    value.bitLength() > MAX_PROPERTIES_BYTES * 4 -> throw PropertiesTooLarge()
                    value.toDouble().isFinite() -> value
                    else -> Dropped
                }
                is BigDecimal -> when {
                    value.javaClass != BigDecimal::class.java -> Dropped
                    value.unscaledValue().bitLength() > MAX_PROPERTIES_BYTES * 4 -> throw PropertiesTooLarge()
                    value.toDouble().isFinite() -> value
                    else -> Dropped
                }
                is Date -> InstantCompat.ofEpochMilli(value.time).toIsoString().also(::checkLength)
                is URL -> value.toString().also(::checkLength)
                is UUID -> value.toString()
                is Map<*, *> -> container(value, depth) {
                    val copy = linkedMapOf<String, Any?>()
                    for ((key, child) in value) {
                        // Only string keys make a JSON object; any other key drops the whole map.
                        if (key !is String) return@container Dropped
                        checkLength(key)
                        val safe = normalize(child, depth + 1)
                        if (safe !== Dropped) copy[key] = safe
                    }
                    copy
                }
                is List<*> -> container(value, depth) {
                    val copy = mutableListOf<Any?>()
                    for (child in value) {
                        val safe = normalize(child, depth + 1)
                        if (safe !== Dropped) copy.add(safe)
                    }
                    copy
                }
                else -> if (!value.javaClass.isArray) Dropped else container(value, depth) {
                    val copy = mutableListOf<Any?>()
                    for (index in 0 until JavaArray.getLength(value)) {
                        val safe = normalize(JavaArray.get(value, index), depth + 1)
                        if (safe !== Dropped) copy.add(safe)
                    }
                    copy
                }
            }
        }

        /** Copies one list, map or array, or drops it when it is too deep, cyclic or fails to read. */
        private fun container(value: Any, depth: Int, copy: () -> Any): Any {
            if (depth >= MAX_PROPERTY_DEPTH || ancestors.containsKey(value)) return Dropped
            ancestors[value] = true
            return try {
                copy()
            } catch (e: PropertiesTooLarge) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A caller-owned collection that fails while being read is dropped, not half-copied.
                Dropped
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
