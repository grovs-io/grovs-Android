package io.grovs.example.data

/** Accepts a full link, host/path or only the path, and returns the last path segment. */
fun extractLinkPath(input: String): String? {
    val clean = input.trim().substringBefore('#').substringBefore('?')
    if (clean.isEmpty()) return null
    val afterScheme = clean.substringAfter("://", clean)
    val hasHost = clean.contains("://") || afterScheme.contains('/')
    val path = if (hasHost) afterScheme.substringAfter('/', "") else afterScheme
    return path.split('/').lastOrNull { it.isNotBlank() }
}

fun isWebUrl(input: String): Boolean {
    val v = input.trim()
    val rest = when {
        v.startsWith("https://") -> v.removePrefix("https://")
        v.startsWith("http://") -> v.removePrefix("http://")
        else -> return false
    }
    return rest.substringBefore('/').isNotBlank()
}

fun adbCommand(link: String, packageName: String): String =
    "adb shell am start -a android.intent.action.VIEW -d \"$link\" $packageName"

fun flatten(map: Map<String, Any?>, prefix: String = ""): List<Pair<String, String>> =
    map.flatMap { (key, value) ->
        val name = if (prefix.isEmpty()) key else "$prefix.$key"
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Map<*, *> -> flatten(value as Map<String, Any?>, name)
            is List<*> -> listOf(name to value.joinToString(", "))
            else -> listOf(name to value.toString())
        }
    }
