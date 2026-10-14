package io.grovs.inapp.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.grovs.inapp.InAppBridge
import io.grovs.inapp.model.InAppLayout
import java.net.URI
import java.net.URISyntaxException
import kotlin.math.max
import kotlin.math.min

/** What an `html` message loads, and the small rules around it. No Android types, so it runs in plain JUnit. */
internal object InAppHtmlDocument {
    /** The name both transports give the page's native object. */
    const val CHANNEL = "grovsAndroid"
    const val MIN_HEIGHT_DP = 44f
    const val MODAL_WIDTH_DP = 420f
    const val WHITE = -0x1

    private const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    private const val URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!\$&'()*+,;=:@/?#%"
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val viewportTag = Regex("<meta[^>]*name\\s*=\\s*[\"']?viewport", RegexOption.IGNORE_CASE)
    private val doctype = Regex("^(?:\\s|<!--[\\s\\S]*?-->)*<!doctype[^>]*>", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("<meta[^>]*>", RegexOption.IGNORE_CASE)
    private val themeName = Regex("name\\s*=\\s*[\"']?theme-color[\"'\\s>/]", RegexOption.IGNORE_CASE)
    private val contentValue = Regex("content\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
    private val mediaValue = Regex("media\\s*=\\s*[\"']?[^\"'>]*prefers-color-scheme\\s*:\\s*(dark|light)", RegexOption.IGNORE_CASE)
    private val rgbValue = Regex("^rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([\\d.]+)\\s*)?", RegexOption.IGNORE_CASE)
    private val hexValue = Regex("^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val gson = GsonBuilder().serializeNulls().create()

    /**
     * The page with a viewport when it has none (else WebView lays it out 980 px wide) and the boot script right after
     * its doctype, which is added when missing (else quirks mode sizes the page to its frame).
     */
    fun document(html: String, script: String): String {
        val tag = if (viewportTag.containsMatchIn(html)) "" else VIEWPORT
        val match = doctype.find(html) ?: return "<!doctype html>" + tag + script + html
        val end = match.range.last + 1
        return html.substring(0, end) + tag + script + html.substring(end)
    }

    /** The handoff the bridge takes and deletes, then the bridge. `post` is bound here, before any author script runs. */
    fun injection(token: String, user: JsonObject, event: JsonObject, pushPermission: String): String {
        val handoff = JsonObject()
        handoff.addProperty("token", token)
        handoff.add("user", plain(user))
        handoff.add("event", plain(event))
        handoff.add("capabilities", JsonObject().apply { addProperty("pushPermission", pushPermission) })
        val boot = "(function(b,s){window.__grovs=Object.assign(" + scriptJson(handoff) +
            ",{post:function(m){b.postMessage(s(m));}});})(window." + CHANNEL + ",JSON.stringify.bind(JSON));"
        return "<script>" + boot + "\n" + InAppBridge.SCRIPT + "</script>"
    }

    /** JSON that is safe inside an inline script and inside a JavaScript string literal. */
    fun scriptJson(value: JsonElement): String = gson.toJson(value)
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    /** Keeps the entries JSON can carry; a NaN or infinite number anywhere inside drops its entry. */
    fun plain(values: JsonObject): JsonObject {
        val kept = JsonObject()
        for ((key, value) in values.entrySet()) if (finite(value)) kept.add(key, value)
        return kept
    }

    private fun finite(value: JsonElement): Boolean = when {
        value is JsonPrimitive && value.isNumber -> value.asDouble.let { !it.isNaN() && !it.isInfinite() }
        value is JsonArray -> value.all { finite(it) }
        value is JsonObject -> value.entrySet().all { finite(it.value) }
        else -> true
    }

    fun hasCover(html: String): Boolean = html.contains("viewport-fit=cover", ignoreCase = true)

    /** The head's `<meta name="theme-color">` for this appearance (by its media), else one without media. Hex or rgb() only. */
    fun themeColor(page: String, dark: Boolean): Int? {
        val headEnd = page.indexOf("</head", ignoreCase = true)
        val head = if (headEnd >= 0) page.substring(0, headEnd) else page
        var plain: String? = null
        for (tag in metaTag.findAll(head).map { it.value }) {
            if (!themeName.containsMatchIn(tag)) continue
            val found = contentValue.find(tag) ?: continue
            val value = (1..3).firstNotNullOfOrNull { found.groups[it]?.value } ?: ""
            val media = mediaValue.find(tag)
            if (media == null) {
                if (plain == null) plain = value
                continue
            }
            if (media.groupValues[1].lowercase() == (if (dark) "dark" else "light")) return parseColor(value.trim())
        }
        return plain?.let { parseColor(it.trim()) }
    }

    /** `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa` or `rgb()`/`rgba()` (alpha ignored) as ARGB. */
    fun parseColor(value: String): Int? {
        if (value.startsWith("#")) {
            if (!hexValue.matches(value)) return null
            var hex = value.substring(1)
            if (hex.length == 3 || hex.length == 4) hex = hex.map { "$it$it" }.joinToString("")
            val rgb = hex.substring(0, 6).toInt(16)
            val alpha = if (hex.length == 8) hex.substring(6, 8).toInt(16) else 0xFF
            return (alpha shl 24) or rgb
        }
        val match = rgbValue.find(value) ?: return null
        val channels = (1..3).map { min(match.groupValues[it].toDouble(), 255.0).toInt() }
        return (0xFF shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    /** The page's computed background as `evaluateJavascript` hands it back (a JSON string). A transparent page shows white. */
    fun pageBackground(evaluated: String?): Int? {
        if (evaluated == null) return null
        val parsed = try {
            JsonParser.parseString(evaluated)
        } catch (e: Exception) {
            return null
        }
        if (!parsed.isJsonPrimitive || !parsed.asJsonPrimitive.isString) return null
        val text = parsed.asString.trim()
        val match = rgbValue.find(text) ?: return null
        if (match.groups[4]?.value?.toDoubleOrNull() == 0.0) return WHITE
        return parseColor(text)
    }

    /** True over a light colour, which needs dark status bar icons. */
    fun isLight(argb: Int): Boolean = !InAppStyle.wantsLightIcons(argb)

    /** The page's height within its layout's limit, never below the close control; null for `full`, which fills the screen. */
    fun clamp(height: Float, layout: InAppLayout, available: Float): Float? = when (layout) {
        InAppLayout.FULL -> null
        InAppLayout.MODAL -> max(min(height, available * 0.9f), MIN_HEIGHT_DP)
        // A phone on its side has under 500 dp; 30 % of that is too short for even a compact banner.
        InAppLayout.BANNER_TOP, InAppLayout.BANNER_BOTTOM ->
            max(min(height, available * (if (available < 500f) 0.45f else 0.3f)), MIN_HEIGHT_DP)
    }

    /** The link with every byte a URL may not hold raw percent-encoded; null when even that is no URL. */
    fun openable(link: String): String? {
        val out = StringBuilder()
        for (byte in link.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && URL_CHARS.indexOf(c.toChar()) >= 0) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        val encoded = out.toString()
        val uri = try {
            URI(encoded)
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.scheme.equals("https", ignoreCase = true) && uri.host == null) return null
        return encoded
    }
}
