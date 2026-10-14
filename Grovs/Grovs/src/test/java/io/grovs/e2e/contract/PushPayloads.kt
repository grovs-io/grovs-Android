package io.grovs.e2e.contract

import android.content.Intent
import android.os.Bundle
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject

/** Push data as the backend builds it, and as FCM hands it to the app. */
internal object PushPayloads {
    const val CAMPAIGN = "k9Qx2"
    const val LINK = "https://demo.sqd.link/promo"

    /** Keys a campaign's custom data may not use. */
    private val RESERVED = Regex("""\A(aps|from|notification|message_type|__rpush_.*|google.*|gcm.*)\z""", RegexOption.IGNORE_CASE)

    fun data(
        campaign: String = CAMPAIGN,
        link: String? = LINK,
        image: String? = null,
        custom: Map<String, Any?> = mapOf("promo" to "spring26", "screen" to "push"),
    ): Map<String, String> {
        val base = linkedMapOf("grovs_campaign_id" to campaign)
        link?.let { base["grovs_link"] = it }
        image?.let { base["grovs_image"] = it }
        val customData = custom.filterKeys { !RESERVED.matches(it) }.mapValues { (_, v) -> dataValueToText(v) }
        return customData + base
    }

    /** A string stays as it is; any other value becomes its JSON text. */
    fun dataValueToText(value: Any?): String = when (value) {
        is String -> value
        null -> "null"
        is Map<*, *> -> JSONObject(value).toString()
        is List<*> -> JSONArray(value).toString()
        else -> value.toString()
    }

    /** The launcher intent Android builds when a tray notification is tapped. */
    fun tapIntent(data: Map<String, String> = data(), messageId: String = "0:m1"): Intent = Intent().apply {
        data.forEach { (k, v) -> putExtra(k, v) }
        putExtra("google.message_id", messageId)
        putExtra("google.sent_time", 1_760_000_000_000L)
        putExtra("google.ttl", 2_419_200)
        putExtra("google.original_priority", "high")
        putExtra("google.delivered_priority", "high")
        putExtra("google.c.sender.id", "1234567890")
        putExtra("from", "1234567890")
        putExtra("collapse_key", "io.grovs.test")
    }

    /** The message FirebaseMessagingService.onMessageReceived gets in the foreground. */
    fun remoteMessage(
        data: Map<String, String> = data(),
        title: String? = "Spring sale",
        body: String? = "20% off today",
        image: String? = null,
        sound: String? = null,
        messageId: String = "0:fg",
    ): RemoteMessage = RemoteMessage(Bundle().apply {
        data.forEach { (k, v) -> putString(k, v) }
        putString("google.message_id", messageId)
        putString("from", "1234567890")
        if (title != null || body != null) {
            putString("gcm.n.e", "1")
            title?.let { putString("gcm.notification.title", it) }
            body?.let { putString("gcm.notification.body", it) }
            image?.let { putString("gcm.notification.image", it) }
            sound?.let { putString("gcm.notification.sound2", it); putString("gcm.notification.sound", it) }
        }
    })
}
