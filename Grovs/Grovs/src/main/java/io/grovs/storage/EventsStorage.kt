package io.grovs.storage

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.grovs.model.DebugLogger
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.LogLevel
import io.grovs.utils.DurationCompat
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSJsonInstantCompatTypeAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

class EventsStorage(context: Context) : IEventsStorage {
    private val preferences = context.getSharedPreferences(GROVS_STORAGE, Context.MODE_PRIVATE)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val storageSerialDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val gson = GsonBuilder().setLenient().registerTypeAdapterFactory(
        LSJsonInstantCompatTypeAdapterFactory()
    ).create()

    companion object {
        const val GROVS_STORAGE = "GrovsStorage"
        private const val STORED_EVENTS = "stored_events"
    }

    /// Adds or replaces events in the storage.
    ///
    /// - Parameter events: The events to add or replace.
    override suspend fun addOrReplaceEvents(events: List<Event>) = withContext(storageSerialDispatcher) {
        DebugLogger.instance.log(LogLevel.INFO, "Caching events - Events update: ${events}")

        val currentEvents = getEvents().toMutableList()
        events.forEach { event ->
            if (currentEvents.contains(event)) {
                val index = currentEvents.indexOf(event)
                currentEvents[index] = event
            } else {
                currentEvents.add(event)
            }
        }

        val type = object : TypeToken<List<Event>>() {}.type
        val editor = preferences.edit()
        val jsonString = gson.toJson(currentEvents)
        editor.putString(STORED_EVENTS, jsonString)
        editor.apply()

        try {
            gson.fromJson(jsonString, type)
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "Caching events - Failed. ${e.stackTrace}")
        }
    }

    /// Adds an event to the storage.
    ///
    /// - Parameter event: The event to add.
    override suspend fun addEvent(event: Event) = withContext(storageSerialDispatcher) {
        var currentEvents = getEvents().toMutableList()
        currentEvents.add(event)

        val type = object : TypeToken<List<Event>>() {}.type
        val editor = preferences.edit()
        val jsonString = gson.toJson(currentEvents)
        editor.putString(STORED_EVENTS, jsonString)
        editor.apply()

        DebugLogger.instance.log(LogLevel.INFO, "Caching events - Add event: ${event}")

        try {
            gson.fromJson(jsonString, type)
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "Caching events - Failed. ${e.stackTrace}")
        }
    }

    override suspend fun markTimeSpentNode(startingNode: Boolean, endingNode: Boolean, link: String?) = withContext(storageSerialDispatcher) {
        val events = getEvents()
        if (startingNode) {
            for (event in events) {
                if ((event.event == EventType.TIME_SPENT) && (event.engagementTime == null)) {
                    removeEvent(event)
                }
            }
        } else {
            val oldEvent = events.firstOrNull { (it.event == EventType.TIME_SPENT) && (it.engagementTime == null) }
            oldEvent?.let { oldEvent ->
                val timestamp = InstantCompat.now()
                val duration = DurationCompat.between(oldEvent.createdAt, timestamp)
                DebugLogger.instance.log(LogLevel.INFO, "Calculating time: ${oldEvent.createdAt} $timestamp")
                val secondsPassed =  duration.seconds
                if (secondsPassed > 0) {
                    oldEvent.engagementTime = secondsPassed.toInt()
                }
                addOrReplaceEvents(listOf(oldEvent))
            }
        }

        // Remove invalid TIME_SPENT events
        for (event in events) {
            if ((event.event == EventType.TIME_SPENT) && (event.engagementTime == null)) {
                removeEvent(event)
            }
        }

        if (!endingNode) {
            val event = Event(event = EventType.TIME_SPENT, createdAt = InstantCompat.now(), link = link)
            addEvent(event)
        }
    }

    /// Removes an event from the storage.
    ///
    /// - Parameter event: The event to remove.
    override suspend fun removeEvent(event: Event) = withContext(storageSerialDispatcher) {
        val currentEvents = getEvents().toMutableList()
        currentEvents.remove(event)

        val type = object : TypeToken<List<Event>>() {}.type
        val editor = preferences.edit()
        val jsonString = gson.toJson(currentEvents)
        editor.putString(STORED_EVENTS, jsonString)
        editor.apply()

        try {
            gson.fromJson(jsonString, type)
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "Caching events - Failed. ${e.stackTrace}")
        }
    }

    /// Retrieves all events from the storage.
    override suspend fun getEvents(): List<Event> = withContext(storageSerialDispatcher) {
            val jsonString = preferences.getString(STORED_EVENTS, null)
            val type = object : TypeToken<List<Event>>() {}.type

            try {
                gson.fromJson(jsonString, type)
            } catch (e: Exception) {
                emptyList()
            }
    }

    /// Check if we already have an empty time spent event.
    override suspend fun hasEmptyTimeSpentEvent(): Boolean = withContext(storageSerialDispatcher) {
        val jsonString = preferences.getString(STORED_EVENTS, null)
        val type = object : TypeToken<List<Event>>() {}.type

        val events: List<Event> = try {
            gson.fromJson(jsonString, type)
        } catch (e: Exception) {
            emptyList()
        }

        events.firstOrNull { (it.event == EventType.TIME_SPENT) && (it.engagementTime == null) } != null
    }
}