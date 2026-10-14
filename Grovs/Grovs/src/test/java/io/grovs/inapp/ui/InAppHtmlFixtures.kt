package io.grovs.inapp.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppRendered

/** `html` campaigns on the shared cases' base_campaign. */
internal object InAppHtmlFixtures {
    val RENDERED = InAppRendered(title = null, message = null, buttons = emptyList(), urls = emptyList())

    fun raw(layout: String = "modal", html: String = "<p>Hi</p>", content: JsonObject = JsonObject()): JsonObject {
        val page = content.deepCopy()
        page.addProperty("html", html)
        page.addProperty("layout", layout)
        val changes = JsonObject()
        changes.addProperty("template", "html")
        changes.add("content", page)
        return InAppUiFixtures.json(changes.toString())
    }

    fun campaign(layout: String = "modal", html: String = "<p>Hi</p>", content: JsonObject = JsonObject()): InAppCampaign =
        InAppDefinition.readCampaign(raw(layout, html, content)) ?: error("the html fixture must read as a campaign")
}

internal fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply { pairs.forEach { (key, value) -> add(key, json(value)) } }

internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is List<*> -> JsonArray().apply { value.forEach { add(json(it)) } }
    is Map<*, *> -> JsonObject().apply { value.forEach { (key, item) -> add(key as String, json(item)) } }
    else -> error("no JSON form for $value")
}
