package io.grovs.inapp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.*

/** Loads src/test/resources/in-app-shared, the contract copied from the backend. */
internal object InAppShared {
    fun bytes(name: String): ByteArray {
        val stream = InAppShared::class.java.classLoader!!.getResourceAsStream("in-app-shared/$name")
            ?: error("missing in-app-shared/$name: copy it from the backend")
        return stream.use { it.readBytes() }
    }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun json(name: String): JsonObject = JsonParser.parseString(text(name)).asJsonObject

    fun obj(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    /** Objects merge key by key; arrays and scalars, null included, replace. */
    fun merge(base: JsonObject, over: JsonObject): JsonObject {
        val out = base.deepCopy()
        for ((key, value) in over.entrySet()) {
            val under = base.get(key)
            if (under != null && under.isJsonObject && value.isJsonObject) {
                out.add(key, merge(under.asJsonObject, value.asJsonObject))
            } else {
                out.add(key, value.deepCopy())
            }
        }
        return out
    }

    fun baseCampaign(): JsonObject = json("cases.json").getAsJsonObject("base_campaign")

    class Case(
        val name: String,
        val device: JsonObject,
        val campaigns: List<JsonObject>,
        val steps: List<JsonObject>,
        val globalCaps: InAppGlobalCaps,
        val syncedAt: String,
        val notReady: Set<String>,
    )

    private fun strings(value: JsonElement?): List<String> =
        value?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { InAppJson.string(it) } ?: emptyList()

    private fun objects(value: JsonElement?): List<JsonObject> =
        value?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()

    fun cases(): List<Case> {
        val file = json("cases.json")
        val baseCampaign = file.getAsJsonObject("base_campaign")
        val baseDevice = file.getAsJsonObject("base_device")
        val baseCase = file.getAsJsonObject("base_case")
        return objects(file.get("cases")).map { raw ->
            val top = merge(baseCase, JsonObject().also { picked ->
                for (key in listOf("global_caps", "synced_at", "not_ready")) raw.get(key)?.let { picked.add(key, it) }
            })
            val caps = top.get("global_caps")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            Case(
                name = InAppJson.string(raw.get("name")) ?: "",
                device = merge(baseDevice, raw.get("device")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()),
                campaigns = objects(raw.get("campaigns")).map { merge(baseCampaign, it) },
                steps = objects(raw.get("steps")),
                globalCaps = InAppGlobalCaps(InAppJson.whole(caps.get("session")), InAppJson.whole(caps.get("day"))),
                syncedAt = InAppJson.string(top.get("synced_at")) ?: "",
                notReady = strings(top.get("not_ready")).toSet(),
            )
        }
    }

    fun device(raw: JsonObject) = InAppDevice(
        platform = InAppJson.string(raw.get("platform")) ?: "",
        appVersion = InAppJson.string(raw.get("app_version")) ?: "",
        osVersion = InAppJson.string(raw.get("os_version")) ?: "",
        language = InAppJson.string(raw.get("language")) ?: "",
        firstSeenAt = InAppDefinition.readTimestamp(raw.get("first_seen_at")),
        utcOffsetMinutes = InAppJson.whole(raw.get("utc_offset_minutes")) ?: 0,
        user = raw.get("user")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject(),
        pushPermission = InAppPushPermission.from(InAppJson.string(raw.get("push_permission"))) ?: InAppPushPermission.NOT_ASKED,
        unsupportedTemplates = strings(raw.get("unsupported_templates")),
        unsupportedActions = strings(raw.get("unsupported_actions")),
        orientation = InAppOrientation.from(InAppJson.string(raw.get("orientation"))) ?: InAppOrientation.PORTRAIT,
    )

    fun engine(case: Case, campaigns: List<JsonElement>? = null): InAppEngine {
        var next = 0
        return InAppEngine(
            device = device(case.device),
            campaigns = campaigns ?: case.campaigns,
            globalCaps = case.globalCaps,
            syncedAt = InAppDefinition.readTimestamp(JsonPrimitive(case.syncedAt)) ?: 0L,
            notReady = { case.notReady },
            newViewId = { "d${++next}" },
        )
    }

    fun event(raw: JsonObject) = InAppEvent(
        type = InAppEventType.from(InAppJson.string(raw.get("type"))) ?: InAppEventType.EVENT,
        name = InAppJson.string(raw.get("name")),
        properties = raw.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject(),
    )

    /** Runs every step; returns the first mismatch as text, or null when the case passes. */
    fun run(case: Case, engine: InAppEngine = engine(case)): String? {
        for ((index, step) in case.steps.withIndex()) {
            if (step.has("session_start")) {
                engine.startSession()
                continue
            }
            val sync = step.get("sync")
            if (sync != null && sync.isJsonObject) {
                engine.markSynced(InAppDefinition.readTimestamp(sync.asJsonObject.get("at")) ?: 0L)
                continue
            }
            val answer = InAppJson.string(step.get("answer"))
            if (answer != null) {
                engine.answer(answer)
                continue
            }
            val raw = step.get("event")?.takeIf { it.isJsonObject }?.asJsonObject
            val at = InAppDefinition.readTimestamp(step.get("at"))
            val expect = step.get("expect")?.takeIf { it.isJsonObject }?.asJsonObject
            if (raw == null || at == null || expect == null) return "unreadable step ${index + 1}"
            val decision = engine.track(event(raw), at)
            for ((key, wanted) in expect.entrySet()) {
                val got = valueOf(key, decision) ?: JsonNull.INSTANCE
                if (wanted != got) return "step ${index + 1}, $key: wanted $wanted, got $got"
            }
        }
        return null
    }

    private fun valueOf(key: String, decision: InAppDecision): JsonElement? = when (key) {
        "show" -> decision.show?.let { JsonPrimitive(it) }
        "view" -> decision.view?.let { JsonPrimitive(it) }
        "reasons" -> JsonObject().also { out -> decision.reasons.forEach { (id, reason) -> out.addProperty(id, reason.wire) } }
        "title" -> decision.rendered?.title?.let { JsonPrimitive(it) }
        "message" -> decision.rendered?.message?.let { JsonPrimitive(it) }
        "buttons" -> decision.rendered?.buttons?.let { list -> JsonArray().also { out -> list.forEach { out.add(it) } } }
        "urls" -> decision.rendered?.urls?.let { list -> JsonArray().also { out -> list.forEach { out.add(it) } } }
        "delay" -> decision.delay?.let { JsonPrimitive(it) }
        else -> null
    }
}
