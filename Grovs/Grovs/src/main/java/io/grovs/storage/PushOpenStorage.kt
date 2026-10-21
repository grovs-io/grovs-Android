package io.grovs.storage

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSJsonInstantCompatTypeAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A push tap not yet accepted by the backend. [sessionId] is the session of the tap. */
internal data class PushOpen(
    val campaignId: String,
    val sessionId: String,
    val createdAt: InstantCompat,
)

/** Keeps push opens on disk until they are reported, with the same cap and age limit as events. */
internal class PushOpenStorage(context: Context) {
    private val preferences =
        context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)

    private val gson = GsonBuilder().setLenient()
        .registerTypeAdapterFactory(LSJsonInstantCompatTypeAdapterFactory())
        .create()

    companion object {
        // A lock rather than a serial dispatcher, so clear() can run synchronously on any thread.
        private val lock = Any()

        private const val STORED_PUSH_OPENS = "stored_push_opens"
    }

    /** Saves [open] unless its campaign already has one, so a newer tap never replaces an older one. */
    suspend fun addIfAbsent(open: PushOpen): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val stored = readAll()
            if (stored.any { it.campaignId == open.campaignId }) return@synchronized false
            write(EventsStorage.capped(stored + open) { it.createdAt })
            true
        }
    }

    suspend fun remove(open: PushOpen) = withContext(Dispatchers.IO) {
        synchronized(lock) { write(readAll() - open) }
    }

    /** Oldest first, without opens past the event age limit. */
    suspend fun getAll(): List<PushOpen> = withContext(Dispatchers.IO) {
        val cutoff = InstantCompat.now().minusMillis(EventsStorage.MAX_EVENT_AGE_MS)
        synchronized(lock) { readAll() }.filter { it.createdAt.isAfter(cutoff) }
    }

    fun clear() {
        synchronized(lock) { preferences.edit().remove(STORED_PUSH_OPENS).apply() }
    }

    private fun readAll(): List<PushOpen> {
        val jsonString = preferences.getString(STORED_PUSH_OPENS, null)
        val type = object : TypeToken<List<PushOpen>>() {}.type
        return try {
            gson.fromJson<List<PushOpen>>(jsonString, type) ?: emptyList()
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.ERROR, "Caching push opens - Read failed. ${e.message}")
            emptyList()
        }
    }

    private fun write(opens: List<PushOpen>) {
        preferences.edit().putString(STORED_PUSH_OPENS, gson.toJson(opens)).apply()
    }
}
