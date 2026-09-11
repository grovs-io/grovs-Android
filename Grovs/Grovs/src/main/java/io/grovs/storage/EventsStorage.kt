package io.grovs.storage

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.grovs.model.DebugLogger
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.LogLevel
import io.grovs.model.events.PaymentEvent
import io.grovs.utils.DurationCompat
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSJsonInstantCompatTypeAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

class EventsStorage(context: Context) : IEventsStorage {
    private val preferences = context.getSharedPreferences(GROVS_STORAGE, Context.MODE_PRIVATE)
    private val gson = GsonBuilder().setLenient().registerTypeAdapterFactory(
        LSJsonInstantCompatTypeAdapterFactory()
    ).create()

    companion object {
        // Instances share the preference file, so they must also share its mutation dispatcher.
        @OptIn(ExperimentalCoroutinesApi::class)
        private val storageSerialDispatcher = Dispatchers.IO.limitedParallelism(1)

        const val GROVS_STORAGE = "GrovsStorage"
        private const val STORED_EVENTS = "stored_events"
        private const val STORED_PAYMENT_EVENTS = "stored_payment_events"

        // Retention policy shared with CustomEventsStorage.

        /** Hard cap on persisted events. Oldest are evicted first. */
        const val MAX_STORED_EVENTS = 1000

        /** Events older than this are discarded on read rather than sent. */
        const val MAX_EVENT_AGE_DAYS = 7
        internal const val MAX_EVENT_AGE_MS = MAX_EVENT_AGE_DAYS * 24L * 60 * 60 * 1000

        /** Enforces the storage cap by dropping the oldest events first. */
        internal fun <T> capped(events: List<T>, createdAt: (T) -> InstantCompat): List<T> =
            if (events.size <= MAX_STORED_EVENTS) events
            else events.sortedBy(createdAt).takeLast(MAX_STORED_EVENTS)
    }

    private fun capped(events: List<Event>): List<Event> = capped(events) { it.createdAt }

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

        val cappedEvents = capped(currentEvents)

        val type = object : TypeToken<List<Event>>() {}.type
        val editor = preferences.edit()
        val jsonString = gson.toJson(cappedEvents)
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
        val currentEvents = capped(getEvents() + event)

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

    override suspend fun addPaymentEvent(event: PaymentEvent) = withContext(storageSerialDispatcher) {
        writePaymentEvents(readPaymentEvents() + event)
        DebugLogger.instance.log(LogLevel.INFO, "Caching payment events - Add event: ${event}")
    }

    override suspend fun markTimeSpentNode(startingNode: Boolean, endingNode: Boolean, link: String?, sessionId: String?) = withContext(storageSerialDispatcher) {
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
            val event = Event(event = EventType.TIME_SPENT, createdAt = InstantCompat.now(), link = link, sessionId = sessionId)
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

    override suspend fun removeEvents(events: List<Event>) = withContext(storageSerialDispatcher) {
        if (events.isEmpty()) return@withContext
        val doomed = events.toSet()
        val remaining = getEvents().filterNot { it in doomed }
        preferences.edit().putString(STORED_EVENTS, gson.toJson(remaining)).apply()
    }

    /// Removes a payment event from the storage.
    ///
    /// - Parameter event: The event to remove.
    override suspend fun removePaymentEvent(event: PaymentEvent) = withContext(storageSerialDispatcher) {
        val currentEvents = readPaymentEvents().toMutableList()
        currentEvents.remove(event)
        writePaymentEvents(currentEvents)
    }

    override suspend fun replacePaymentEvents(events: List<PaymentEvent>) = withContext(storageSerialDispatcher) {
        writePaymentEvents(events)
    }

    /** No suspension between reading and writing: concurrent inserts/removals cannot be overwritten. */
    internal suspend fun updatePaymentEvents(transform: (PaymentEvent) -> Unit) = withContext(storageSerialDispatcher) {
        val events = readPaymentEvents()
        if (events.isNotEmpty()) {
            events.forEach(transform)
            writePaymentEvents(events)
        }
    }

    /** Launch events and counters share one preference edit, including across process death. */
    internal suspend fun recordLaunch(cache: LocalCache, lastSeen: InstantCompat?, link: String?, sessionId: String) =
        withContext(storageSerialDispatcher) {
            val now = InstantCompat.now()
            val opens = cache.numberOfOpens
            val events = getEvents().filterNot { it.event == EventType.TIME_SPENT && it.engagementTime == null }.toMutableList()
            fun add(type: EventType) { events.add(Event(type, now, link = link, sessionId = sessionId)) }
            if (opens == 0) add(if (lastSeen == null) EventType.INSTALL else EventType.REINSTALL)
            cache.lastStartTimestamp?.let {
                if (DurationCompat.between(it, now).toDays() >= 7) add(EventType.REACTIVATION)
            }
            add(EventType.APP_OPEN)
            add(EventType.TIME_SPENT)
            val editor = preferences.edit().putString(STORED_EVENTS, gson.toJson(capped(events)))
            cache.stageLaunch(editor, opens + 1, now)
            editor.apply()
        }

    /** Close only an existing segment, at the lifecycle/consent boundary rather than dispatch time. */
    internal suspend fun closeEngagementAt(end: InstantCompat) = withContext(storageSerialDispatcher) {
        val events = getEvents()
        if (events.none { it.event == EventType.TIME_SPENT && it.engagementTime == null }) return@withContext
        val updated = events.filter { event ->
            if (event.event != EventType.TIME_SPENT || event.engagementTime != null) return@filter true
            val seconds = DurationCompat.between(event.createdAt, end).seconds
            if (event.createdAt.isAfter(end)) return@filter true
            if (seconds <= 0) return@filter false
            event.engagementTime = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            true
        }
        preferences.edit().putString(STORED_EVENTS, gson.toJson(updated)).apply()
    }

    /// Retrieves all events from the storage, dropping any that are too old to be useful.
    override suspend fun getEvents(): List<Event> = withContext(storageSerialDispatcher) {
        val jsonString = preferences.getString(STORED_EVENTS, null)
        val type = object : TypeToken<List<Event>>() {}.type

        val events: List<Event> = try {
            gson.fromJson<List<Event>>(jsonString, type) ?: emptyList()
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.ERROR, "Caching events - Read failed. ${e.message}")
            emptyList()
        }

        val cutoff = InstantCompat.now().minusMillis(MAX_EVENT_AGE_MS)
        events.filter { it.createdAt.isAfter(cutoff) }
    }

    /// Retrieves all payment events from the storage.
    override suspend fun getPaymentEvents(): List<PaymentEvent> = withContext(storageSerialDispatcher) {
        readPaymentEvents()
    }

    // Only call these from the storage dispatcher. They deliberately contain no suspension points.
    private fun writePaymentEvents(events: List<PaymentEvent>) {
        preferences.edit().putString(STORED_PAYMENT_EVENTS, gson.toJson(events)).apply()
    }

    private fun readPaymentEvents(): List<PaymentEvent> {
        val jsonString = preferences.getString(STORED_PAYMENT_EVENTS, null)
        val type = object : TypeToken<List<PaymentEvent>>() {}.type

        return try {
            gson.fromJson<List<PaymentEvent>>(jsonString, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /// Check if we already have an empty time spent event.
    override suspend fun hasEmptyTimeSpentEvent(): Boolean = withContext(storageSerialDispatcher) {
        val jsonString = preferences.getString(STORED_EVENTS, null)
        val type = object : TypeToken<List<Event>>() {}.type

        val events: List<Event> = try {
            gson.fromJson<List<Event>>(jsonString, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        events.firstOrNull { (it.event == EventType.TIME_SPENT) && (it.engagementTime == null) } != null
    }
}
