package io.grovs.handlers

import android.content.Intent
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.LogLevel

/** A Grovs campaign push, read from FCM data or from the intent a tap opens. */
internal data class GrovsPushPayload(
    val campaignId: String,
    val link: String?,
    val image: String?,
    val messageId: String?,
    /** Everything the campaign sent, grovs_* keys included, without FCM transport keys. */
    val data: Map<String, String>,
) {

    /** What the deep-link listener gets: push data merged over the resolved link, push keys winning. */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun details(resolved: DeeplinkDetails?): DeeplinkDetails {
        val merged = LinkedHashMap<String, Object>()
        resolved?.data?.let { merged.putAll(it) }
        data.forEach { (key, value) -> merged[key] = value as Object }
        return DeeplinkDetails(link = resolved?.link, data = merged, tracking = resolved?.tracking)
    }

    companion object {
        const val CAMPAIGN_ID = "grovs_campaign_id"
        const val LINK = "grovs_link"
        const val IMAGE = "grovs_image"
        /** Set on the tap intent of a notification the SDK posts itself. */
        const val MESSAGE_ID_EXTRA = "io.grovs.message_id"
        private const val FCM_MESSAGE_ID = "google.message_id"
        private val FCM_KEYS = setOf("from", "collapse_key", "message_type")

        fun from(data: Map<String, String>, messageId: String?): GrovsPushPayload? {
            val campaignId = data[CAMPAIGN_ID]?.takeIf { it.isNotBlank() } ?: return null
            val campaignData = data.filterKeys { !isTransportKey(it) }
            return GrovsPushPayload(
                campaignId = campaignId,
                link = campaignData[LINK]?.takeIf { it.isNotBlank() },
                image = campaignData[IMAGE]?.takeIf { it.isNotBlank() },
                messageId = messageId,
                data = campaignData,
            )
        }

        fun from(intent: Intent?): GrovsPushPayload? {
            val strings = try {
                val extras = intent?.extras ?: return null
                extras.keySet().mapNotNull { key ->
                    @Suppress("DEPRECATION")
                    (extras.get(key) as? String)?.let { key to it }
                }.toMap()
            } catch (e: RuntimeException) {
                // A host extra that cannot be unparcelled must not crash the app's launch.
                DebugLogger.instance.log(LogLevel.ERROR, "Could not read the intent extras: ${e.message}")
                return null
            }
            return from(strings, strings[MESSAGE_ID_EXTRA] ?: strings[FCM_MESSAGE_ID])
        }

        private fun isTransportKey(key: String): Boolean =
            key.startsWith("google.") || key.startsWith("gcm.") || key.startsWith("io.grovs.") || key in FCM_KEYS
    }
}
