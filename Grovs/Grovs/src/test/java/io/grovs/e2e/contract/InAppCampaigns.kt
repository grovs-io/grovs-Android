package io.grovs.e2e.contract

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Campaign definitions as the backend sends them: cases.json base_campaign (a byte copy of the
 * backend's file) with the defaults the backend always adds.
 * The base is campaign "c1", an interstitial shown by the event "cart_viewed".
 */
internal object InAppCampaigns {

    private val base: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResource("in-app-shared/cases.json")!!.readText()
        JsonParser.parseString(text).asJsonObject.getAsJsonObject("base_campaign")
    }

    fun raw(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    fun campaign(): Builder = Builder(base.deepCopy().apply {
        getAsJsonObject("content").apply { addProperty("dismissible", true); add("question", JsonNull.INSTANCE) }
    })

    class Builder(private val c: JsonObject) {
        private val content get() = c.getAsJsonObject("content")

        fun id(value: String) = apply { c.addProperty("id", value) }
        fun revision(value: Int) = apply { c.addProperty("revision", value) }
        fun priority(value: Int) = apply { c.addProperty("priority", value) }
        fun createdAt(value: String) = apply { c.addProperty("created_at", value) }
        fun template(value: String) = apply {
            c.addProperty("template", value)
            if (value.endsWith("_image")) { content.add("title", JsonNull.INSTANCE); content.add("message", JsonNull.INSTANCE) }
        }
        fun title(value: String?) = apply { content.add("title", value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE) }
        fun message(value: String?) = apply { content.add("message", value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE) }
        fun triggers(vararg value: JsonObject) = apply { c.add("triggers", JsonArray().apply { value.forEach(::add) }) }
        fun condition(value: JsonObject) = apply { c.getAsJsonArray("conditions").add(value) }
        fun caps(session: Int? = null, day: Int? = null, week: Int? = null, lifetime: Int? = null, ignoreGlobal: Boolean = false) = apply {
            c.add("caps", JsonObject().apply {
                addProperty("session", session); addProperty("day", day); addProperty("week", week)
                addProperty("lifetime", lifetime); addProperty("ignore_global", ignoreGlobal)
            })
        }
        fun schedule(startAt: String, endAt: String? = null, days: List<Int>? = null, from: String? = null, to: String? = null) = apply {
            c.add("schedule", JsonObject().apply {
                addProperty("start_at", startAt); addProperty("end_at", endAt)
                add("days", days?.let { d -> JsonArray().apply { d.forEach { add(it) } } } ?: JsonNull.INSTANCE)
                addProperty("from", from); addProperty("to", to)
            })
        }
        fun delay(seconds: Int) = apply { c.addProperty("delay_seconds", seconds) }
        fun buttons(vararg value: JsonObject) = apply { content.add("buttons", JsonArray().apply { value.forEach(::add) }) }
        fun tapAction(value: JsonObject?) = apply { content.add("tap_action", value ?: JsonNull.INSTANCE) }
        fun question(value: JsonObject?) = apply { content.add("question", value ?: JsonNull.INSTANCE) }
        fun html(html: String, layout: String) = apply {
            c.addProperty("template", "html"); content.addProperty("html", html); content.addProperty("layout", layout)
        }
        fun images(portrait: String?, landscape: String?) = apply {
            content.add("image", JsonObject().apply { addProperty("portrait", portrait); addProperty("landscape", landscape) })
        }
        fun dismissible(value: Boolean) = apply { content.addProperty("dismissible", value) }
        fun closeButton(value: Boolean) = apply { content.addProperty("close_button", value) }
        fun backdrop(value: String) = apply { content.addProperty("backdrop", value) }
        fun orientation(value: String) = apply { content.addProperty("orientation", value) }
        /** Escape hatch for off-schema definitions; `path` is dot separated. */
        fun set(path: String, value: JsonElement) = apply {
            val keys = path.split('.')
            keys.dropLast(1).fold(c) { node, key -> node.getAsJsonObject(key) }.add(keys.last(), value)
        }
        fun build(): JsonObject = c.deepCopy()
    }

    fun appOpen() = raw("""{"type":"app_open"}""")
    fun screen(name: String?) = JsonObject().apply { addProperty("type", "screen_view"); name?.let { addProperty("name", it) } }
    fun event(name: String, vararg filters: JsonObject) = JsonObject().apply {
        addProperty("type", "event"); addProperty("name", name)
        if (filters.isNotEmpty()) add("filters", JsonArray().apply { filters.forEach(::add) })
    }
    fun filter(field: String, op: String, value: Any?) = JsonObject().apply {
        addProperty("field", field); addProperty("op", op)
        when (value) { null -> {}; is Number -> addProperty("value", value); is Boolean -> addProperty("value", value); else -> addProperty("value", value.toString()) }
    }
    fun button(id: String, text: String, action: JsonObject = close()) =
        JsonObject().apply { addProperty("id", id); addProperty("text", text); add("action", action) }
    fun close() = raw("""{"type":"close"}""")
    fun openUrl(url: String) = JsonObject().apply { addProperty("type", "open_url"); addProperty("url", url) }
    fun openStore() = raw("""{"type":"open_store"}""")
    fun requestPushPermission() = raw("""{"type":"request_push_permission"}""")
    fun requestAppRating() = raw("""{"type":"request_app_rating"}""")
    fun keyValue(payload: Map<String, String>) = JsonObject().apply {
        addProperty("type", "key_value"); add("payload", JsonObject().apply { payload.forEach { (k, v) -> addProperty(k, v) } })
    }
    fun stars(reviewFrom: Int? = null, comment: Boolean = false, split: Int? = null, low: String? = null, high: String? = null, thanks: String? = null) =
        question("stars", emptyList(), comment, split, low, high, thanks, reviewFrom)
    fun choice(vararg options: Pair<String, String>, comment: Boolean = false, thanks: String? = null) =
        question("choice", options.toList(), comment, null, null, null, thanks, null)

    private fun question(type: String, options: List<Pair<String, String>>, comment: Boolean, split: Int?, low: String?, high: String?, thanks: String?, reviewFrom: Int?) =
        JsonObject().apply {
            addProperty("id", "q"); addProperty("type", type)
            add("options", JsonArray().apply { options.forEach { (id, text) -> add(JsonObject().apply { addProperty("id", id); addProperty("text", text) }) } })
            add("comment", if (comment) JsonObject().apply { addProperty("split", split); addProperty("low", low); addProperty("high", high) } else JsonNull.INSTANCE)
            addProperty("thanks", thanks); addProperty("review_from", reviewFrom)
        }
}
