package io.grovs.inapp.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

internal object InAppJson {
    private const val SAFE_INTEGER = 9_007_199_254_740_992.0

    fun isNull(value: JsonElement?): Boolean = value == null || value.isJsonNull

    fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.asString

    fun bool(value: JsonElement?): Boolean? = (value as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean

    fun double(value: JsonElement?): Double? {
        val primitive = (value as? JsonPrimitive)?.takeIf { it.isNumber } ?: return null
        val number = try {
            primitive.asDouble
        } catch (e: NumberFormatException) {
            return null
        }
        return number.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    /** A whole number, 1.0 included: JavaScript cannot tell it from 1. */
    fun whole(value: JsonElement?): Int? {
        val number = double(value) ?: return null
        if (number != Math.rint(number) || Math.abs(number) >= SAFE_INTEGER) return null
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
        return number.toInt()
    }

    fun isScalar(value: JsonElement?): Boolean = string(value) != null || bool(value) != null || double(value) != null

    fun parse(text: String): JsonElement? = try {
        JsonParser.parseString(text)
    } catch (e: JsonParseException) {
        null
    }

    fun fromHost(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Number -> if (value.toDouble().let { it.isNaN() || it.isInfinite() }) JsonNull.INSTANCE else JsonPrimitive(value)
        is Map<*, *> -> JsonObject().also { out -> value.forEach { (key, item) -> if (key is String) out.add(key, fromHost(item)) } }
        is Iterable<*> -> JsonArray().also { out -> value.forEach { out.add(fromHost(it)) } }
        is Array<*> -> JsonArray().also { out -> value.forEach { out.add(fromHost(it)) } }
        else -> JsonNull.INSTANCE
    }
}
