package io.grovs.example.actions

fun exportLog(entries: List<LogEntry>, header: String, formatTime: (Long) -> String): String =
    entries.joinToString(separator = "\n\n", prefix = "$header\n\n") { e ->
        listOf("${formatTime(e.time)} ${e.type.label} ${e.state} ${e.title}", e.detail, e.code)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
