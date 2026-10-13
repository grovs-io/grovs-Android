package io.grovs.inapp.model

/** The shared link rule for html pages (cases.json `link_rule`). */
internal object InAppLinkRule {
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val FORBIDDEN = setOf("javascript", "vbscript", "data", "blob", "file", "about", "intent", "http")
    private const val ASCII_SPACES = " \t\n\u000B\u000C\r"

    /** The normalised link when allowed, else null. A bare "#..." fragment is allowed. */
    fun allowed(input: String): String? {
        var url = input.filter { it != '\t' && it != '\n' && it != '\r' }.trim { it <= ' ' }
        if (url.startsWith("#")) return url
        val match = SCHEME.find(url) ?: return null
        val name = match.value.dropLast(1).lowercase()
        url = name + ":" + url.substring(match.value.length)
        if (name in FORBIDDEN) return null
        if (name == "https") {
            val rest = url.substring("https:".length)
            if (!rest.startsWith("//") || rest.length < 3) return null
            val host = rest[2]
            if (host in "/?#" || host in ASCII_SPACES) return null
        }
        return url
    }
}
