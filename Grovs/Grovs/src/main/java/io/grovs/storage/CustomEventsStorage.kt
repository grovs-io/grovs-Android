package io.grovs.storage

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.grovs.model.CustomEvent
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSJsonInstantCompatTypeAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

internal class CustomEventsStorage(context: Context) : ICustomEventsStorage {
    private val preferences =
        context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val storageSerialDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val gson = GsonBuilder().setLenient().registerTypeAdapterFactory(
        LSJsonInstantCompatTypeAdapterFactory()
    ).create()

    companion object {
        private const val STORED_CUSTOM_EVENTS = "stored_custom_events"
    }

    override suspend fun addEvent(event: CustomEvent) = withContext(storageSerialDispatcher) {
        // Retention policy (cap and age) is shared with EventsStorage.
        val updated = EventsStorage.capped(readAll() + event) { it.createdAt }
        write(updated)
        DebugLogger.instance.log(LogLevel.INFO, "Caching custom events - Add event: $event")
    }

    override suspend fun removeEvents(events: List<CustomEvent>) = withContext(storageSerialDispatcher) {
        val doomed = events.map { it.eventId }.toSet()
        write(readAll().filterNot { doomed.contains(it.eventId) })
    }

    override suspend fun getEvents(): List<CustomEvent> = withContext(storageSerialDispatcher) {
        val cutoff = InstantCompat.now().minusMillis(EventsStorage.MAX_EVENT_AGE_MS)
        readAll().filter { it.createdAt.isAfter(cutoff) }
    }

    private fun readAll(): List<CustomEvent> {
        val jsonString = preferences.getString(STORED_CUSTOM_EVENTS, null)
        val type = object : TypeToken<List<CustomEvent>>() {}.type

        return try {
            gson.fromJson<List<CustomEvent>>(jsonString, type) ?: emptyList()
        } catch (e: Exception) {
            DebugLogger.instance.log(
                LogLevel.ERROR,
                "Caching custom events - Read failed. ${e.message}"
            )
            emptyList()
        }
    }

    private fun write(events: List<CustomEvent>) {
        preferences.edit()
            .putString(STORED_CUSTOM_EVENTS, gson.toJson(events))
            .apply()
    }
}
