package io.grovs.inapp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.abs

/** Gson trees back to the plain values the host and the custom event queue use. */
internal object InAppHostValues {
    private const val MAX_SAFE = 9_007_199_254_740_992.0

    fun toHost(value: JsonElement?): Any? = when {
        value == null || value.isJsonNull -> null
        value.isJsonObject -> toMap(value.asJsonObject)
        value.isJsonArray -> value.asJsonArray.map { toHost(it) }
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isNumber -> {
            val number = value.asDouble
            if (number % 1.0 == 0.0 && abs(number) < MAX_SAFE) number.toLong() else number
        }
        else -> value.asString
    }

    fun toMap(json: JsonObject): Map<String, Any?> = json.entrySet().associate { it.key to toHost(it.value) }

    /** Top-level nulls dropped: the custom event queue holds Map<String, Any>. */
    fun toProperties(json: JsonObject): Map<String, Any> =
        json.entrySet().mapNotNull { entry -> toHost(entry.value)?.let { entry.key to it } }.toMap()
}
