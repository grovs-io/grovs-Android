package io.grovs.inapp.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal object InAppValues {
    private val NUMBER = Regex("-?[0-9]+(\\.[0-9]+)?")
    private val VERSION_HEAD = Regex("^[0-9.]*")
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    /** "42" for 42.0 and every digit, never an exponent: the shortest decimal that reads back the same. */
    private fun plain(value: Double): String {
        if (value == 0.0) return "0"
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
        val exact = BigDecimal(value)
        for (digits in 1..17) {
            val candidate = exact.round(MathContext(digits, RoundingMode.HALF_EVEN))
            if (candidate.toString().toDouble() == value) return candidate.stripTrailingZeros().toPlainString()
        }
        return exact.stripTrailingZeros().toPlainString()
    }

    /** Null for anything with no text form every platform agrees on: null, lists, objects. */
    fun text(value: JsonElement?): String? {
        InAppJson.string(value)?.let { return it }
        InAppJson.bool(value)?.let { return if (it) "true" else "false" }
        return InAppJson.double(value)?.let(::plain)
    }

    fun isNumberText(text: String): Boolean = NUMBER.matches(text)

    fun isNumber(value: JsonElement?): Boolean =
        InAppJson.double(value) != null || InAppJson.string(value)?.let(::isNumberText) == true

    private fun numberOf(value: JsonElement?): Double =
        InAppJson.double(value) ?: InAppJson.string(value)?.toDoubleOrNull() ?: Double.NaN

    private fun same(actual: String, expected: String): Boolean =
        if (isNumberText(actual) && isNumberText(expected)) actual.toDouble() == expected.toDouble()
        else actual.lowercase() == expected.lowercase()

    private fun between(actual: String, range: JsonElement?): Boolean {
        val pair = (range as? JsonArray)?.takeIf { it.size() == 2 } ?: return false
        if (!isNumberText(actual) || !isNumber(pair[0]) || !isNumber(pair[1])) return false
        val value = actual.toDouble()
        return value >= numberOf(pair[0]) && value <= numberOf(pair[1])
    }

    fun compare(actual: JsonElement?, op: String, expected: JsonElement?): Boolean {
        val text = text(actual)
        val present = text != null && text != ""
        if (op == "is_not_set") return !present
        if (text == null || !present) return false
        if (op == "is_set") return true
        if (op == "between") return between(text, expected)
        val wanted = text(expected) ?: return false
        return when (op) {
            "eq" -> same(text, wanted)
            "neq" -> !same(text, wanted)
            "gt" -> isNumberText(text) && isNumberText(wanted) && text.toDouble() > wanted.toDouble()
            "lt" -> isNumberText(text) && isNumberText(wanted) && text.toDouble() < wanted.toDouble()
            "contains" -> text.lowercase().contains(wanted.lowercase())
            "not_contains" -> !text.lowercase().contains(wanted.lowercase())
            else -> false
        }
    }

    private fun versionParts(version: String): List<Long> =
        VERSION_HEAD.find(version)?.value.orEmpty().split(".").map { it.toLongOrNull() ?: 0L }

    private fun versionOrder(left: String, right: String): Int {
        val a = versionParts(left)
        val b = versionParts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val order = (a.getOrNull(index) ?: 0L).compareTo(b.getOrNull(index) ?: 0L)
            if (order != 0) return order
        }
        return 0
    }

    fun compareVersions(actual: String, op: String, expected: JsonElement?): Boolean {
        if (actual.isEmpty()) return false
        if (op == "between") {
            val pair = (expected as? JsonArray)?.takeIf { it.size() == 2 } ?: return false
            return versionOrder(actual, text(pair[0]) ?: "") >= 0 && versionOrder(actual, text(pair[1]) ?: "") <= 0
        }
        val order = versionOrder(actual, text(expected) ?: "")
        return when (op) {
            "eq" -> order == 0
            "neq" -> order != 0
            "gt" -> order > 0
            "lt" -> order < 0
            else -> false
        }
    }

    /** RFC 3986 unreserved characters stay; every other UTF-8 byte becomes %XX. Not URLEncoder, which writes "+". */
    fun percentEncode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (code < 0x80 && UNRESERVED.indexOf(char) >= 0) {
                out.append(char)
            } else {
                out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return out.toString()
    }
}
