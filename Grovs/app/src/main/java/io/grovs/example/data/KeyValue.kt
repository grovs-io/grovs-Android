package io.grovs.example.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ValueType(val badge: String) { TEXT("abc"), NUMBER("123"), BOOL("y/n"), DATE("date") }

fun ValueType.next(): ValueType = ValueType.values()[(ordinal + 1) % ValueType.values().size]

/** A map entry as typed by the user. A null [type] means the type is detected from [raw]. */
data class KeyValue(val key: String, val raw: String, val type: ValueType? = null) {
    val resolvedType: ValueType get() = type ?: detectType(raw)
    fun value(): Serializable = convert(raw, resolvedType)
}

private val NUMBER = Regex("-?\\d+(\\.\\d+)?")
private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

fun detectType(raw: String): ValueType {
    val v = raw.trim()
    return when {
        v.equals("true", ignoreCase = true) || v.equals("false", ignoreCase = true) -> ValueType.BOOL
        NUMBER.matches(v) -> ValueType.NUMBER
        ISO_DATE.matches(v) && parseDate(v) != null -> ValueType.DATE
        else -> ValueType.TEXT
    }
}

fun convert(raw: String, type: ValueType): Serializable {
    val v = raw.trim()
    return when (type) {
        ValueType.TEXT -> raw
        ValueType.NUMBER -> v.toLongOrNull() ?: v.toDoubleOrNull() ?: raw
        ValueType.BOOL -> v.equals("true", ignoreCase = true)
        ValueType.DATE -> parseDate(v) ?: raw
    }
}

private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    isLenient = false
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun parseDate(v: String): Date? = try {
    dateFormat().parse(v)
} catch (e: ParseException) {
    null
}

fun formatDate(date: Date): String = dateFormat().format(date)

fun List<KeyValue>.toValueMap(): Map<String, Serializable> =
    filter { it.key.isNotBlank() }.associate { it.key.trim() to it.value() }

fun List<KeyValue>.summary(): String = filter { it.key.isNotBlank() }.joinToString(", ") { it.key.trim() }

fun List<KeyValue>.toJson(): String {
    val json = JSONObject()
    toValueMap().forEach { (key, value) -> json.put(key, if (value is Date) formatDate(value) else value) }
    return json.toString(2)
}

fun parseKeyValues(json: String): List<KeyValue> {
    val obj = JSONObject(json)
    return obj.keys().asSequence().map { key ->
        val value = obj.get(key)
        val type = when (value) {
            is Boolean -> ValueType.BOOL
            is Number -> ValueType.NUMBER
            else -> null
        }
        KeyValue(key, if (value == JSONObject.NULL) "" else value.toString(), type)
    }.toList()
}

fun List<KeyValue>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { array.put(JSONObject().put("k", it.key).put("v", it.raw).put("t", it.type?.name ?: JSONObject.NULL)) }
}

fun keyValuesFrom(array: JSONArray?): List<KeyValue> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val type = if (o.isNull("t")) null else ValueType.values().firstOrNull { it.name == o.getString("t") }
        KeyValue(o.optString("k"), o.optString("v"), type)
    }
}
