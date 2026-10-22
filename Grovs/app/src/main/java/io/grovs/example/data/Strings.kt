package io.grovs.example.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun middleEllipsis(text: String, keep: Int = 5): String =
    if (text.length <= keep * 2 + 1) text else text.take(keep) + "…" + text.takeLast(keep)

fun formatClock(millis: Long, seconds: Boolean = false): String =
    SimpleDateFormat(if (seconds) "HH:mm:ss" else "HH:mm", Locale.US).format(Date(millis))

fun relativeTime(elapsedMillis: Long): String {
    val minutes = elapsedMillis / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        hours < 1 -> "$minutes min ago"
        days < 1 -> "$hours h ago"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}
