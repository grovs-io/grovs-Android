package io.grovs.inapp.model

import com.google.gson.JsonObject

/** Fills {{ user.key }} and {{ event.key }} fields and judges links; raw braces stop the campaign. */
internal object InAppPersonalize {
    sealed class Outcome {
        data class Rendered(val rendered: InAppRendered) : Outcome()
        data class Skipped(val reason: InAppReason) : Outcome()
    }

    // \s is ASCII here, as in the backend's check that guards what can be saved.
    private val FIELD = Regex("""\{\{\s*(user|event)\.([A-Za-z0-9_]+)\s*(?:\|\s*default:\s*"([^"]*)"\s*)?\}\}""")
    private val AUTHORITY = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val FORBIDDEN = setOf("javascript", "data", "file", "blob")

    fun fill(template: String, user: JsonObject, event: JsonObject, encode: Boolean = false): String? {
        if (FIELD.replace(template, "").contains("{{")) return null
        val out = StringBuilder()
        var cursor = 0
        for (match in FIELD.findAll(template)) {
            out.append(template, cursor, match.range.first)
            cursor = match.range.last + 1
            val source = if (match.groupValues[1] == "user") user else event
            var value = InAppValues.text(source.get(match.groupValues[2]))
            if (value.isNullOrEmpty()) value = match.groups[3]?.value
            val filled = value ?: return null
            out.append(if (encode) InAppValues.percentEncode(filled) else filled)
        }
        return out.append(template, cursor, template.length).toString()
    }

    fun links(campaign: InAppCampaign): List<String> =
        campaign.content.actions.mapNotNull { if (it.type == InAppActionType.OPEN_URL) it.url else null }

    /** The scheme and host are judged on the template, so a filled value can never supply either. */
    fun unsafe(template: String, filled: String): Boolean {
        val found = AUTHORITY.find(template) ?: SCHEME.find(template) ?: return true
        if (found.value.contains("{{")) return true
        return filled.substringBefore(':').lowercase() in FORBIDDEN
    }

    fun render(campaign: InAppCampaign, event: InAppEvent, user: JsonObject): Outcome {
        val content = campaign.content
        val skipped = Outcome.Skipped(InAppReason.PERSONALIZATION)
        fun filled(template: String, encode: Boolean = false) = fill(template, user, event.properties, encode)
        val title = content.title?.let { filled(it) ?: return skipped }
        val message = content.message?.let { filled(it) ?: return skipped }
        val buttons = content.buttons.map { filled(it.text) ?: return skipped }
        val templates = links(campaign)
        val urls = templates.map { filled(it, encode = true) ?: return skipped }
        if (templates.zip(urls).any { (template, url) -> unsafe(template, url) }) return Outcome.Skipped(InAppReason.UNSAFE_LINK)
        return Outcome.Rendered(InAppRendered(title, message, buttons, urls))
    }
}
