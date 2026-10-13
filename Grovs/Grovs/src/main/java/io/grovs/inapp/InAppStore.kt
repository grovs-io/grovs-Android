package io.grovs.inapp

import androidx.core.util.AtomicFile
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.InAppGlobalCaps
import io.grovs.inapp.model.InAppJson
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What is kept between launches: the campaigns, written at a sync, and the small state, written often. */
internal enum class InAppRecord(val fileName: String) {
    CAMPAIGNS("in_app_campaigns.json"),
    STATE("in_app_state.json"),
}

internal interface InAppStoring {
    fun read(record: InAppRecord): String?
    fun write(text: String, record: InAppRecord): Boolean
}

/**
 * Lives in noBackupFilesDir: Auto Backup must not restore caps, answers and stamps onto another
 * device. One folder per key, so the test and live environments never read each other's counts.
 */
internal class InAppFileStore(baseDir: File, key: String) : InAppStoring {
    private val folder = File(baseDir, "grovs/in_app/${InAppHash.short(key)}")

    private fun file(record: InAppRecord) = AtomicFile(File(folder, record.fileName))

    override fun read(record: InAppRecord): String? = try {
        String(file(record).readFully(), Charsets.UTF_8)
    } catch (e: IOException) {
        null
    }

    override fun write(text: String, record: InAppRecord): Boolean {
        if (!folder.isDirectory && !folder.mkdirs()) return false
        val file = file(record)
        val stream = try {
            file.startWrite()
        } catch (e: IOException) {
            return false
        }
        return try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (e: IOException) {
            file.failWrite(stream)
            false
        }
    }
}

internal object InAppHash {
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The first eight bytes, as iOS names its folders. */
    fun short(text: String): String = sha256Hex(text).take(16)
}

private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

private fun JsonObject.objectAt(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal data class InAppSynced(
    val campaigns: List<JsonElement> = emptyList(),
    val globalCaps: InAppGlobalCaps = InAppGlobalCaps(session = null, day = null),
    val firstSeenAt: String? = null,
    val storeUrl: String? = null,
    val campaignsChangedAt: String? = null,
    val userChangedAt: String? = null,
    /** Server time minus device time at the last sync, in milliseconds. */
    val clockOffset: Long = 0,
) {
    fun save(store: InAppStoring): Boolean {
        val raw = JsonObject()
        raw.add("campaigns", JsonArray().also { array -> campaigns.forEach { array.add(it) } })
        raw.add("global_caps", JsonObject().also {
            it.add("session", globalCaps.session?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
            it.add("day", globalCaps.day?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
        })
        raw.addProperty("clock_offset", clockOffset)
        firstSeenAt?.let { raw.addProperty("first_seen_at", it) }
        storeUrl?.let { raw.addProperty("store_url", it) }
        campaignsChangedAt?.let { raw.addProperty("campaigns_changed_at", it) }
        userChangedAt?.let { raw.addProperty("user_changed_at", it) }
        return store.write(gson.toJson(raw), InAppRecord.CAMPAIGNS)
    }

    companion object {
        /** Null for a missing or unreadable record: the caller starts empty and syncs. */
        fun load(store: InAppStoring): InAppSynced? {
            val raw = store.read(InAppRecord.CAMPAIGNS)?.let(InAppJson::parse)
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val campaigns = raw.get("campaigns")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val caps = raw.objectAt("global_caps") ?: JsonObject()
            return InAppSynced(
                campaigns = campaigns.toList(),
                globalCaps = InAppGlobalCaps(session = InAppJson.whole(caps.get("session")), day = InAppJson.whole(caps.get("day"))),
                firstSeenAt = InAppJson.string(raw.get("first_seen_at")),
                storeUrl = InAppJson.string(raw.get("store_url")),
                campaignsChangedAt = InAppJson.string(raw.get("campaigns_changed_at")),
                userChangedAt = InAppJson.string(raw.get("user_changed_at")),
                clockOffset = InAppJson.double(raw.get("clock_offset"))?.let { Math.round(it) } ?: 0,
            )
        }
    }
}

internal data class InAppKept(
    /** The SDK session the engine's session caps belong to. */
    val sessionId: String? = null,
    val state: InAppEngineState = InAppEngineState(),
) {
    fun save(store: InAppStoring): Boolean {
        val raw = JsonObject()
        sessionId?.let { raw.addProperty("sessionId", it) }
        raw.add("state", state.toJson())
        return store.write(gson.toJson(raw), InAppRecord.STATE)
    }

    companion object {
        fun load(store: InAppStoring): InAppKept? {
            val raw = store.read(InAppRecord.STATE)?.let(InAppJson::parse)
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val state = InAppEngineState.fromJson(raw.get("state")) ?: return null
            return InAppKept(sessionId = InAppJson.string(raw.get("sessionId")), state = state)
        }
    }
}
