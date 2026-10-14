package io.grovs.e2e.contract

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.e2e.contract.BackendContract.json
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Answers in_app/sync and judges in-app events the way the backend does. */
internal class InAppBackend {

    enum class SyncMode { FULL, UNCHANGED_WHEN_SAME, NO_STAMPS, OFF, NOT_FOUND, FAIL }

    val campaigns = CopyOnWriteArrayList<JsonObject>()
    val testMessages = CopyOnWriteArrayList<JsonObject>()
    val syncBodies = CopyOnWriteArrayList<JsonObject>()

    @Volatile var mode = SyncMode.UNCHANGED_WHEN_SAME
    @Volatile var campaignsStamp = "1760000000000"
    @Volatile var userStamp = "1760000000000"
    @Volatile var serverNowMs: () -> Long = { System.currentTimeMillis() }
    @Volatile var storeUrl: String? = "https://play.google.com/store/apps/details?id=io.grovs.test"
    @Volatile var firstSeenAt: String? = "2026-08-01T00:00:00Z"
    @Volatile var globalCaps: Pair<Int, Int> = 10 to 10

    private val answered = ConcurrentHashMap.newKeySet<String>()
    private val stampCounter = AtomicLong(1_760_000_000_001)

    /** Changes both stamps, as InAppStamps.touch does on any campaign or user change. */
    fun touch() {
        val next = stampCounter.incrementAndGet().toString()
        campaignsStamp = next
        userStamp = next
    }

    /** No stamps when in-app is unavailable (404) or none are stored. */
    fun batchStamps(): String? = when (mode) {
        SyncMode.OFF -> BackendContract.OFF_STAMPS
        SyncMode.NOT_FOUND, SyncMode.NO_STAMPS -> null
        else -> """{"campaigns_changed_at":"$campaignsStamp","user_changed_at":"$userStamp"}"""
    }

    // in_app/sync
    fun syncAnswer(body: JsonObject): MockResponse {
        syncBodies += body
        val now = stamp(serverNowMs())
        return when (mode) {
            SyncMode.NOT_FOUND -> json(404, """{"error":"Not Found"}""")
            SyncMode.FAIL -> json(500, """{"error":"Internal Server Error"}""")
            SyncMode.OFF -> json(200, offAnswer(now))
            SyncMode.NO_STAMPS -> json(200, fullAnswer(now, withStamps = false))
            SyncMode.FULL -> json(200, fullAnswer(now, withStamps = true))
            SyncMode.UNCHANGED_WHEN_SAME -> {
                val held = body.stringOrNull("campaigns_changed_at") to body.stringOrNull("user_changed_at")
                if (held == campaignsStamp to userStamp) {
                    json(200, JsonObject().apply {
                        addProperty("unchanged", true); addProperty("server_time", now); addProperty("store_url", storeUrl)
                    }.toString())
                } else json(200, fullAnswer(now, withStamps = true))
            }
        }
    }

    private fun fullAnswer(now: String, withStamps: Boolean): String = JsonObject().apply {
        if (withStamps) { addProperty("campaigns_changed_at", campaignsStamp); addProperty("user_changed_at", userStamp) }
        addProperty("server_time", now)
        addProperty("store_url", storeUrl)
        addProperty("first_seen_at", firstSeenAt)
        add("global_caps", JsonObject().apply { addProperty("session", globalCaps.first); addProperty("day", globalCaps.second) })
        add("campaigns", JsonArray().apply { campaigns.forEach { add(it.deepCopy()) } })
        add("test_messages", takeTestMessages())
    }.toString()

    // A test message is handed over once.
    private fun takeTestMessages(): JsonArray = synchronized(testMessages) {
        JsonArray().apply { testMessages.forEach { add(it.deepCopy()) } }.also { testMessages.clear() }
    }

    private fun offAnswer(now: String) =
        """{"campaigns_changed_at":"off","user_changed_at":"off","server_time":"$now","store_url":null,""" +
            """"first_seen_at":null,"global_caps":{"session":10,"day":10},"campaigns":[],"test_messages":[]}"""

    /** Returns the refusal reason, or null when accepted. */
    fun eventError(name: String, event: JSONObject, visitor: String): String? {
        if (mode == SyncMode.NOT_FOUND || mode == SyncMode.OFF) return "switched_off"
        if (name !in BackendContract.DEVICE_IN_APP_EVENTS) return "server_only"
        val properties = event.optJSONObject("properties") ?: JSONObject()
        val campaign = campaigns.firstOrNull { it.get("id").asString == properties.opt("campaign_id")?.toString() }
            ?: return "unknown_campaign"
        val revision = integer(properties.opt("revision"))
        val oldRevision = revision != null && revision < campaign.get("revision").asLong
        val content = campaign.getAsJsonObject("content")
        return when (name) {
            "in_app_clicked" -> {
                val button = if (properties.isNull("button_id")) "" else properties.opt("button_id").toString()
                val known = content.getAsJsonArray("buttons").any { it.asJsonObject.get("id").asString == button }
                if (button.isBlank() || known || (oldRevision && BUTTON_ID.matches(button))) null else "unknown_button"
            }
            "in_app_answered", "in_app_commented" -> {
                val question = content.get("question")?.takeIf { it.isJsonObject }?.asJsonObject ?: return "bad_answer"
                val value = properties.opt("value")
                val goodValue = when (question.get("type").asString) {
                    "stars" -> integer(value)?.let { it in 1..5 } == true
                    else -> question.getAsJsonArray("options").any { it.asJsonObject.get("id").asString == value } ||
                        (oldRevision && value != null && value != JSONObject.NULL && BUTTON_ID.matches(value.toString()))
                }
                if (!goodValue) return "bad_answer"
                val key = "$visitor/${campaign.get("id").asString}"
                if (name == "in_app_answered") {
                    if (!answered.add(key)) "already_answered" else null
                } else {
                    val comment = properties.opt("comment")
                    when {
                        question.get("comment")?.isJsonObject != true -> "bad_answer"
                        comment !is String || comment.isBlank() || comment.codePointCount(0, comment.length) > COMMENT_MAX -> "bad_answer"
                        key !in answered -> "no_answer"
                        else -> null
                    }
                }
            }
            else -> null
        }
    }

    /** Whole JSON numbers only, never 4.0. */
    private fun integer(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString

    companion object {
        const val COMMENT_MAX = 1_000
        private val BUTTON_ID = Regex("""\A[A-Za-z0-9_-]{1,64}\z""")

        fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

        fun parse(text: String): JsonObject = JsonParser.parseString(text).asJsonObject
    }
}
