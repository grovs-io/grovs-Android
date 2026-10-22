package io.grovs.example.actions

import io.grovs.example.data.formatDate
import java.util.Date

/** Writes an SDK call the way it would look in code, for the log detail view. */
fun callCode(name: String, vararg args: Pair<String, Any?>): String {
    if (args.isEmpty()) return "$name()"
    return args.joinToString(separator = ",\n", prefix = "$name(\n", postfix = "\n)") { (key, value) ->
        "  $key = ${render(value, 1)}"
    }
}

fun render(value: Any?, depth: Int = 0): String = when (value) {
    null -> "null"
    is String -> "\"$value\""
    is Date -> "\"${formatDate(value)}\""
    is Map<*, *> -> if (value.isEmpty()) "{}" else {
        val pad = "  ".repeat(depth + 1)
        value.entries.joinToString(",\n", "{\n", "\n${"  ".repeat(depth)}}") { "$pad${it.key}: ${render(it.value, depth + 1)}" }
    }
    is List<*> -> value.joinToString(", ", "[", "]") { render(it, depth) }
    else -> value.toString()
}
