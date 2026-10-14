package io.grovs.e2e.contract

import io.grovs.e2e.contract.BackendContract.json
import io.grovs.service.ConsentBackend
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers every SDK route the way the backend does and records each request that the backend
 * would refuse or that a correct SDK would not send.
 */
internal class ContractBackend(val backend: ConsentBackend) {

    class Rejection(val event: JSONObject, val error: String)

    val violations = CopyOnWriteArrayList<String>()
    val rejected = CopyOnWriteArrayList<Rejection>()

    @Volatile var knownPushCampaigns: Set<String> = setOf(PushPayloads.CAMPAIGN)
    @Volatile var authBody: String = authenticateBody()
    @Volatile var batchInApp: String? = BackendContract.OFF_STAMPS

    private val opened = ConcurrentHashMap.newKeySet<String>()

    fun install() {
        backend.answer("authenticate") { seen ->
            val body = JSONObject(seen.body.ifBlank { "{}" })
            if (!body.has("user_agent") || !body.has("app_version")) {
                violations += "authenticate without user_agent or app_version: ${seen.body}"
                json(400, """{"error":"param is missing"}""")
            } else {
                json(200, authBody)
            }
        }
        backend.respond("device_for_vendor_id", """{"last_seen":null}""")
        backend.respond("data_for_device", """{"link":null,"data":null}""")
        backend.respond("data_for_device_and_url", """{"link":null,"data":null}""")
        backend.respond("clipboard_status", """{"clipboard_active":false}""")
        backend.answer("push_open") { seen -> pushOpen(seen) }
        backend.answer("events/batch") { seen -> batch(seen) }
        backend.answer("event/custom") { seen -> customEvent(seen) }
        backend.answer("visitor_attributes") { seen -> visitorAttributes(seen) }
        backend.respond("in_app/sync", offSync())
        backend.respond("notifications_for_device", """{"notifications":[]}""")
        backend.respond("number_of_unread_notifications", """{"number_of_unread_notifications":0}""")
        backend.respond("mark_notification_as_read", """{"message":"Marked as read"}""")
        backend.respond("notifications_to_display_automatically", """{"notifications":[]}""")
    }

    fun linkResolves(link: String, data: Map<String, String>) {
        val tracking = JSONObject().put("source", JSONObject.NULL).put("campaign", JSONObject.NULL).put("medium", JSONObject.NULL)
        backend.respond(
            "data_for_device_and_url",
            JSONObject().put("data", JSONObject(data)).put("link", link).put("tracking", tracking).toString(),
        )
    }

    fun inboxCalls(): List<String> = backend.seen.map { it.path }.filter { it in BackendContract.INBOX_ROUTES }

    fun bodies(path: String): List<JSONObject> = backend.seen.filter { it.path == path }.map { JSONObject(it.body) }

    fun batchEvents(): List<JSONObject> = bodies("events/batch").flatMap { body ->
        val events = body.optJSONArray("events") ?: JSONArray()
        (0 until events.length()).map { events.getJSONObject(it) }
    }

    /** Called after every contract test. */
    fun assertKept() {
        backend.seen.forEach { seen ->
            if (seen.path !in BackendContract.SDK_ROUTES) violations += "route the backend does not have: ${seen.path}"
            listOf("PROJECT-KEY", "PLATFORM", "IDENTIFIER").forEach { name ->
                if (BackendContract.header(seen.request, name).isNullOrBlank()) violations += "${seen.path} without $name"
            }
            if (seen.path !in BackendContract.DEVICE_CHECK_EXEMPT &&
                BackendContract.header(seen.request, "LINKSQUARED").isNullOrBlank()
            ) violations += "${seen.path} without LINKSQUARED"
        }
        val calls = inboxCalls()
        if (calls.isNotEmpty()) violations += "inbox routes called: $calls"
        rejected.forEach { violations += "backend would reject ${it.event}: ${it.error}" }
        assertTrue("contract broken:\n" + violations.distinct().joinToString("\n"), violations.isEmpty())
    }

    // push_open: the device check comes first (403), then the push outcome
    private fun pushOpen(seen: ConsentBackend.Seen): MockResponse {
        val body = runCatching { JSONObject(seen.body) }.getOrElse { JSONObject() }
        val campaign = body.opt("campaign_id")
        if (campaign !is String || campaign.isBlank()) {
            violations += "push_open without a campaign_id string: ${seen.body}"
            return json(400, """{"error":"campaign_id is required"}""")
        }
        if (body.has("session_id") && body.opt("session_id") !is String) violations += "push_open session_id is not a string"
        val visitor = BackendContract.header(seen.request, "LINKSQUARED")
            ?: return json(403, """{"error":"Invalid linksquared id"}""")
        if (campaign !in knownPushCampaigns) return json(404, """{"error":"Push campaign not found"}""")
        return if (opened.add("$visitor/$campaign")) json(200, BackendContract.PUSH_OPEN_RECORDED)
        else json(200, BackendContract.PUSH_OPEN_ALREADY)
    }

    // events/batch
    private fun batch(seen: ConsentBackend.Seen): MockResponse {
        val events = runCatching { JSONObject(seen.body).get("events") }.getOrNull()
        if (events !is JSONArray) { violations += "batch without an events array"; return json(400, """{"error":"events must be an array"}""") }
        if (events.length() == 0) { violations += "empty batch"; return json(400, """{"error":"events must not be empty"}""") }
        if (events.length() > BackendContract.MAX_BATCH) { violations += "batch of ${events.length()}"; return json(400, """{"error":"max 50 events per batch"}""") }
        val ids = mutableSetOf<String>()
        val errors = JSONArray()
        var accepted = 0
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val error = eventError(event, ids)
            if (error == null) accepted++ else {
                rejected += Rejection(event, error)
                errors.put(JSONObject().put("index", i).put("error", error))
            }
        }
        val answer = JSONObject().put("accepted", accepted).put("rejected", events.length() - accepted).put("errors", errors)
        batchInApp?.let { answer.put("in_app", JSONObject(it)) }
        return json(200, answer.toString())
    }

    private fun eventError(event: JSONObject, ids: MutableSet<String>): String? {
        val id = event.opt("event_id")
        if ((id is String || id is Number) && !ids.add(id.toString().trim())) return "duplicate event_id in batch"
        val system = event.optString("event", "")
        val name = event.optString("event_name", "")
        return when {
            system.isNotEmpty() -> if (system in BackendContract.SYSTEM_EVENTS) null else "unknown event type '$system'"
            name.isEmpty() -> "missing event or event_name"
            name in BackendContract.SERVER_RESERVED -> "event_name '$name' is reserved"
            name in BackendContract.MESSAGE_EVENTS -> inAppError(name, event)
            else -> null
        }
    }

    /** In-app is switched off here, so every message event is refused. */
    private fun inAppError(name: String, event: JSONObject): String? = "event_name '$name' is reserved"

    // event/custom
    private fun customEvent(seen: ConsentBackend.Seen): MockResponse {
        val name = runCatching { JSONObject(seen.body).optString("event_name") }.getOrDefault("")
        return when {
            name.isBlank() -> { violations += "custom event without a name"; json(400, """{"error":"event_name is required"}""") }
            name in BackendContract.SERVER_RESERVED || name in BackendContract.MESSAGE_EVENTS -> {
                violations += "custom event with reserved name $name"; json(400, """{"error":"event_name is reserved"}""")
            }
            else -> json(200, """{"message":"ok"}""")
        }
    }

    // visitor_attributes
    private fun visitorAttributes(seen: ConsentBackend.Seen): MockResponse {
        if (seen.method == "GET") return json(200, """{"visitor":{"sdk_identifier":null,"sdk_attributes":null}}""")
        val body = runCatching { JSONObject(seen.body) }.getOrElse { violations += "visitor_attributes body is not JSON"; JSONObject() }
        val visitor = JSONObject()
            .put("sdk_identifier", body.opt("sdk_identifier") ?: JSONObject.NULL)
            .put("sdk_attributes", body.opt("sdk_attributes") ?: JSONObject.NULL)
        return json(200, JSONObject().put("visitor", visitor).toString())
    }

    private fun offSync() =
        """{"campaigns_changed_at":"off","user_changed_at":"off","server_time":"2026-10-14T10:00:00Z","store_url":null,""" +
            """"first_seen_at":null,"global_caps":{"session":10,"day":10},"campaigns":[],"test_messages":[]}"""

    companion object {
        /** The body of an authenticate request. */
        fun authenticateBody(identifier: String? = null, attributes: Map<String, Any>? = null, pushToken: String? = null): String =
            JSONObject()
                .put("linksquared", "test-grovs-id")
                .put("uri_scheme", "testapp")
                .put("sdk_identifier", identifier ?: JSONObject.NULL)
                .put("sdk_attributes", attributes?.let { JSONObject(it) } ?: JSONObject.NULL)
                .put("push_token", pushToken ?: JSONObject.NULL)
                .put("has_smart_banners", false)
                .toString()
    }
}
