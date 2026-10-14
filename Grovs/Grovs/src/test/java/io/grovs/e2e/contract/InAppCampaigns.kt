package io.grovs.e2e.contract

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Campaign definitions as the backend sends them: cases.json base_campaign (a byte copy of the
 * backend's file) with the defaults the backend always adds.
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
        fun buttons(vararg value: JsonObject) = apply { content.add("buttons", JsonArray().apply { value.forEach(::add) }) }
        fun question(value: JsonObject?) = apply { content.add("question", value ?: JsonNull.INSTANCE) }
        fun build(): JsonObject = c.deepCopy()
    }

    fun button(id: String, text: String, action: JsonObject = close()) =
        JsonObject().apply { addProperty("id", id); addProperty("text", text); add("action", action) }
    fun close() = raw("""{"type":"close"}""")
    fun stars(reviewFrom: Int? = null, comment: Boolean = false, split: Int? = null, low: String? = null, high: String? = null, thanks: String? = null) =
        question("stars", emptyList(), comment, split, low, high, thanks, reviewFrom)

    private fun question(type: String, options: List<Pair<String, String>>, comment: Boolean, split: Int?, low: String?, high: String?, thanks: String?, reviewFrom: Int?) =
        JsonObject().apply {
            addProperty("id", "q"); addProperty("type", type)
            add("options", JsonArray().apply { options.forEach { (id, text) -> add(JsonObject().apply { addProperty("id", id); addProperty("text", text) }) } })
            add("comment", if (comment) JsonObject().apply { addProperty("split", split); addProperty("low", low); addProperty("high", high) } else JsonNull.INSTANCE)
            addProperty("thanks", thanks); addProperty("review_from", reviewFrom)
        }
}
