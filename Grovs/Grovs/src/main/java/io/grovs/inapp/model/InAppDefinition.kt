package io.grovs.inapp.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/** Reads a definition from the network into the engine's model; null means unsupported, never a crash. */
internal object InAppDefinition {
    private val TIMESTAMP = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([01][0-9]|2[0-3]):([0-5][0-9]):([0-5][0-9])(\\.[0-9]{1,3})?Z")
    private val CLOCK = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
    private val VALUE_OPS = setOf("eq", "neq", "gt", "lt", "contains", "not_contains")
    private val ORDER_OPS = setOf("eq", "neq", "gt", "lt")
    private val RESERVED = listOf("variants", "control_group", "conversion")

    /** A field that is either absent (`value == null`) or read; a null `Read` means unreadable. */
    private class Read<T>(val value: T)

    private fun text(value: JsonElement?): String? = InAppJson.string(value)?.takeIf { it.isNotEmpty() }
    private fun obj(value: JsonElement?): JsonObject? = value?.takeIf { it.isJsonObject }?.asJsonObject
    private fun list(value: JsonElement?): JsonArray? = value?.takeIf { it.isJsonArray }?.asJsonArray
    private fun isNull(value: JsonElement?) = InAppJson.isNull(value)
    private fun isText(value: JsonElement?) = isNull(value) || InAppJson.string(value) != null
    private fun isWhole(value: JsonElement?) = isNull(value) || InAppJson.whole(value) != null
    private fun isPair(value: JsonElement?, each: (JsonElement?) -> Boolean) = list(value)?.let { it.size() == 2 && it.all(each) } == true

    /** Epoch milliseconds, or null unless the text is a real UTC calendar time in the contract's form. */
    fun readTimestamp(value: JsonElement?): Long? {
        val written = InAppJson.string(value) ?: return null
        val match = TIMESTAMP.matchEntire(written) ?: return null
        val part = match.groupValues
        val year = part[1].toLong()
        val month = part[2].toInt()
        val day = part[3].toInt()
        if (month !in 1..12 || day < 1 || day > InAppTime.daysInMonth(year, month)) return null
        val fraction = part[7].drop(1).padEnd(3, '0').ifEmpty { "0" }.toLong()
        val seconds = part[4].toLong() * 3600 + part[5].toLong() * 60 + part[6].toLong()
        return InAppTime.daysFromCivil(year, month, day) * 86_400_000L + seconds * 1000 + fraction
    }

    private fun <T> readList(value: JsonElement?, read: (JsonElement) -> T?): List<T>? {
        val items = list(value) ?: return null
        val out = ArrayList<T>(items.size())
        for (item in items) out.add(read(item) ?: return null)
        return out
    }

    private fun readAction(value: JsonElement?): InAppAction? {
        val raw = obj(value) ?: return null
        val type = InAppActionType.from(InAppJson.string(raw.get("type"))) ?: return null
        return when (type) {
            InAppActionType.OPEN_URL -> InAppAction(type, text(raw.get("url")) ?: return null, null)
            InAppActionType.KEY_VALUE -> InAppAction(type, null, obj(raw.get("payload")) ?: return null)
            else -> InAppAction(type, null, null)
        }
    }

    private fun readButton(value: JsonElement): InAppButton? {
        val raw = obj(value) ?: return null
        return InAppButton(
            text(raw.get("id")) ?: return null,
            text(raw.get("text")) ?: return null,
            readAction(raw.get("action")) ?: return null,
        )
    }

    fun readPage(content: JsonObject): InAppPage? {
        val html = InAppJson.string(content.get("html")) ?: return null
        val layout = InAppLayout.from(InAppJson.string(content.get("layout"))) ?: return null
        return InAppPage(html, layout)
    }

    private fun readContent(value: JsonElement?, template: InAppTemplate): InAppContent? {
        val raw = obj(value) ?: return null
        val stated = raw.get("dismissible")
        if (!isNull(stated) && InAppJson.bool(stated) == null) return null
        val dismissible = InAppJson.bool(stated) ?: true
        // Absent means any; an orientation this build does not know makes the definition unreadable.
        val orientation = if (isNull(raw.get("orientation"))) InAppOrientation.ANY
        else InAppOrientation.from(InAppJson.string(raw.get("orientation"))) ?: return null
        if (template == InAppTemplate.HTML) {
            // The page is the whole message: it draws no question, and native fields from another template are never read.
            val page = readPage(raw) ?: return null
            if (!isNull(raw.get("question"))) return null
            return InAppContent(null, null, emptyList(), null, dismissible = dismissible, page = page, orientation = orientation)
        }
        if (!isText(raw.get("title")) || !isText(raw.get("message"))) return null
        val buttons = readList(raw.get("buttons"), ::readButton) ?: return null
        val tap = if (isNull(raw.get("tap_action"))) null else readAction(raw.get("tap_action")) ?: return null
        var question: InAppQuestion? = null
        if (!isNull(raw.get("question"))) {
            // An answer is the one thing to tap, so a question comes with no button and no tap action.
            val asked = readQuestion(raw.get("question")) ?: return null
            if (buttons.isNotEmpty() || tap != null) return null
            question = asked
        }
        // A message nobody can dismiss must offer something to tap, or it would hold the screen for ever.
        if (!dismissible && buttons.isEmpty() && tap == null && question == null) return null
        return InAppContent(
            InAppJson.string(raw.get("title")), InAppJson.string(raw.get("message")), buttons, tap,
            dismissible = dismissible, question = question, orientation = orientation,
        )
    }

    private fun readOption(value: JsonElement): InAppOption? {
        val raw = obj(value) ?: return null
        return InAppOption(text(raw.get("id")) ?: return null, text(raw.get("text")) ?: return null)
    }

    private fun readComment(value: JsonElement?): Read<InAppComment?>? {
        if (isNull(value)) return Read(null)
        val raw = obj(value) ?: return null
        if (!isWhole(raw.get("split")) || !isText(raw.get("low")) || !isText(raw.get("high"))) return null
        return Read(InAppComment(InAppJson.whole(raw.get("split")), InAppJson.string(raw.get("low")), InAppJson.string(raw.get("high"))))
    }

    private fun readQuestion(value: JsonElement?): InAppQuestion? {
        val raw = obj(value) ?: return null
        val id = text(raw.get("id")) ?: return null
        val type = InAppQuestionType.from(InAppJson.string(raw.get("type"))) ?: return null
        val options = readList(raw.get("options"), ::readOption) ?: return null
        val comment = readComment(raw.get("comment")) ?: return null
        if (!isText(raw.get("thanks")) || !isWhole(raw.get("review_from"))) return null
        val fits = if (type == InAppQuestionType.CHOICE) options.size in 2..5 else options.isEmpty()
        if (!fits) return null
        return InAppQuestion(id, type, options, comment.value, InAppJson.string(raw.get("thanks")), InAppJson.whole(raw.get("review_from")))
    }

    /** The operator and the value it needs; null when they do not fit together. */
    private fun readComparison(raw: JsonObject): Pair<String, JsonElement>? {
        val op = InAppJson.string(raw.get("op")) ?: return null
        if (op == "is_set" || op == "is_not_set") return op to JsonNull.INSTANCE
        val value = raw.get("value")
        if (op == "between") return if (isPair(value, InAppValues::isNumber)) op to value else null
        return if (op in VALUE_OPS && InAppJson.isScalar(value)) op to value else null
    }

    private fun readFilter(value: JsonElement): InAppFilter? {
        val raw = obj(value) ?: return null
        val field = text(raw.get("field")) ?: return null
        val (op, expected) = readComparison(raw) ?: return null
        return InAppFilter(field, op, expected)
    }

    private fun readTrigger(value: JsonElement): InAppTrigger? {
        val raw = obj(value) ?: return null
        val type = InAppJson.string(raw.get("type")) ?: return null
        // Present counts, null included, as in the Swift dictionary.
        val named = raw.has("name")
        val filtered = raw.has("filters")
        return when (type) {
            "app_open" -> if (named || filtered) null else InAppTrigger.AppOpen
            "screen_view" -> when {
                filtered -> null
                !named -> InAppTrigger.ScreenView(null)
                else -> text(raw.get("name"))?.let { InAppTrigger.ScreenView(it) }
            }
            "event" -> {
                val name = text(raw.get("name")) ?: return null
                val filters = if (filtered) readList(raw.get("filters"), ::readFilter) ?: return null else emptyList()
                InAppTrigger.Event(name, filters)
            }
            else -> null
        }
    }

    private fun readOrdered(raw: JsonObject, each: (JsonElement?) -> Boolean): Pair<String, JsonElement>? {
        val op = InAppJson.string(raw.get("op")) ?: return null
        val value = raw.get("value") ?: return null
        if (op == "between") return if (isPair(value, each)) op to value else null
        return if (op in ORDER_OPS && each(value)) op to value else null
    }

    private fun readFirstSeen(raw: JsonObject): InAppCondition? = when (InAppJson.string(raw.get("op"))) {
        "before" -> readTimestamp(raw.get("value"))?.let { InAppCondition.FirstSeenBefore(it) }
        "after" -> readTimestamp(raw.get("value"))?.let { InAppCondition.FirstSeenAfter(it) }
        "within_days" -> InAppJson.whole(raw.get("value"))?.takeIf { it > 0 }?.let { InAppCondition.FirstSeenWithinDays(it.toLong()) }
        else -> null
    }

    private fun readCondition(value: JsonElement): InAppCondition? {
        val raw = obj(value) ?: return null
        val type = InAppJson.string(raw.get("type")) ?: return null
        val op = InAppJson.string(raw.get("op"))
        return when (type) {
            "platform", "language" -> {
                if (op != "eq" && op != "neq") return null
                val expected = text(raw.get("value")) ?: return null
                if (type == "platform") InAppCondition.Platform(op, expected) else InAppCondition.Language(op, expected)
            }
            "app_version", "os_version" -> {
                val (order, expected) = readOrdered(raw) { text(it) != null } ?: return null
                if (type == "app_version") InAppCondition.AppVersion(order, expected) else InAppCondition.OsVersion(order, expected)
            }
            "event_count" -> {
                val name = text(raw.get("name")) ?: return null
                val (order, expected) = readOrdered(raw, InAppValues::isNumber) ?: return null
                InAppCondition.EventCount(name, order, expected)
            }
            "user_attribute" -> {
                val key = text(raw.get("key")) ?: return null
                val (order, expected) = readComparison(raw) ?: return null
                InAppCondition.UserAttribute(key, order, expected)
            }
            "first_seen" -> readFirstSeen(raw)
            else -> null
        }
    }

    private fun readCap(value: JsonElement?): Read<Int?>? {
        if (isNull(value)) return Read(null)
        val cap = InAppJson.whole(value)?.takeIf { it > 0 } ?: return null
        return Read(cap)
    }

    private fun readCaps(value: JsonElement?): InAppCaps? {
        val raw = obj(value) ?: return null
        val ignore = InAppJson.bool(raw.get("ignore_global")) ?: return null
        return InAppCaps(
            (readCap(raw.get("session")) ?: return null).value,
            (readCap(raw.get("day")) ?: return null).value,
            (readCap(raw.get("week")) ?: return null).value,
            (readCap(raw.get("lifetime")) ?: return null).value,
            ignore,
        )
    }

    private fun readSchedule(value: JsonElement?): InAppSchedule? {
        val raw = obj(value) ?: return null
        val startAt = readTimestamp(raw.get("start_at")) ?: return null
        val endAt = if (isNull(raw.get("end_at"))) null else readTimestamp(raw.get("end_at")) ?: return null
        var days: List<Int>? = null
        if (!isNull(raw.get("days"))) {
            val written = list(raw.get("days"))?.takeIf { it.size() > 0 } ?: return null
            val read = written.mapNotNull { InAppJson.whole(it) }.filter { it in 1..7 }
            if (read.size != written.size()) return null
            days = read
        }
        if (isNull(raw.get("from")) && isNull(raw.get("to"))) return InAppSchedule(startAt, endAt, days, null, null)
        val from = InAppJson.string(raw.get("from")) ?: return null
        val to = InAppJson.string(raw.get("to")) ?: return null
        if (!CLOCK.matches(from) || !CLOCK.matches(to)) return null
        return InAppSchedule(startAt, endAt, days, from, to)
    }

    fun readCampaign(value: JsonElement?): InAppCampaign? {
        val raw = obj(value) ?: return null
        val format = InAppJson.whole(raw.get("format")) ?: return null
        val id = text(raw.get("id")) ?: return null
        val priority = InAppJson.whole(raw.get("priority")) ?: return null
        val template = InAppTemplate.from(InAppJson.string(raw.get("template"))) ?: return null
        val delay = InAppJson.whole(raw.get("delay_seconds"))?.takeIf { it >= 0 } ?: return null
        val mode = text(raw.get("delivery_mode")) ?: return null
        val createdAt = readTimestamp(raw.get("created_at")) ?: return null
        val content = readContent(raw.get("content"), template) ?: return null
        // The system alert and a picture alone draw no question: nobody may be shut in behind one.
        if (content.question != null && (template == InAppTemplate.ALERT || template.isImageOnly)) return null
        val triggers = readList(raw.get("triggers"), ::readTrigger)?.takeIf { it.isNotEmpty() } ?: return null
        val conditions = readList(raw.get("conditions"), ::readCondition) ?: return null
        val caps = readCaps(raw.get("caps")) ?: return null
        val schedule = readSchedule(raw.get("schedule")) ?: return null
        return InAppCampaign(
            format, id, createdAt, priority, template, content, triggers, conditions, caps, schedule, delay, mode,
            reserved = RESERVED.any { !isNull(raw.get(it)) }, raw = raw,
        )
    }
}
