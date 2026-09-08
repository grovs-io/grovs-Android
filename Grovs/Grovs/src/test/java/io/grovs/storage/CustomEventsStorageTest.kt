package io.grovs.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.model.CustomEvent
import io.grovs.utils.InstantCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class CustomEventsStorageTest {

    private lateinit var context: Context
    private lateinit var storage: CustomEventsStorage

    private fun event(name: String, offsetMs: Long = 0) = CustomEvent(
        eventName = name,
        createdAt = InstantCompat.now().plusMillis(offsetMs),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        storage = CustomEventsStorage(context)
    }

    @Test
    fun `getEvents returns empty list when nothing is stored`() = runTest {
        assertEquals(emptyList<CustomEvent>(), storage.getEvents())
    }

    @Test
    fun `added events round-trip through storage`() = runTest {
        val e = event("checkout_completed")
        storage.addEvent(e)

        val stored = storage.getEvents()
        assertEquals(1, stored.size)
        assertEquals("checkout_completed", stored.first().eventName)
        assertEquals(e.eventId, stored.first().eventId)
    }

    @Test
    fun `properties and tags survive serialization`() = runTest {
        storage.addEvent(
            CustomEvent(
                eventName = "purchase",
                createdAt = InstantCompat.now(),
                properties = mapOf("sku" to "abc", "qty" to 2.0),
                tags = listOf("checkout"),
            )
        )

        val stored = storage.getEvents().first()
        assertEquals("abc", stored.properties!!["sku"])
        assertEquals(listOf("checkout"), stored.tags)
    }

    @Test
    fun `removeEvents removes exactly the given events by id`() = runTest {
        val a = event("a", 0)
        val b = event("b", 1)
        val c = event("c", 2)
        storage.addEvent(a)
        storage.addEvent(b)
        storage.addEvent(c)

        storage.removeEvents(listOf(a, c))

        val remaining = storage.getEvents()
        assertEquals(1, remaining.size)
        assertEquals(b.eventId, remaining.first().eventId)
    }

    @Test
    fun `storage caps at 1000 events and drops the oldest`() = runTest {
        repeat(EventsStorage.MAX_STORED_EVENTS + 5) { i ->
            storage.addEvent(event("e$i", offsetMs = i.toLong()))
        }

        val stored = storage.getEvents()
        assertEquals(EventsStorage.MAX_STORED_EVENTS, stored.size)
        assertEquals("e5", stored.first().eventName)
    }

    @Test
    fun `events older than seven days are discarded on read`() = runTest {
        val eightDaysMs = 8L * 24 * 60 * 60 * 1000
        storage.addEvent(
            CustomEvent(
                eventName = "stale",
                createdAt = InstantCompat.now().minusMillis(eightDaysMs),
            )
        )
        storage.addEvent(event("fresh"))

        val stored = storage.getEvents()
        assertEquals(1, stored.size)
        assertEquals("fresh", stored.first().eventName)
    }

    @Test
    fun `updateEvents rewrites stored events through the transform`() = runTest {
        storage.addEvent(CustomEvent(eventName = "a", createdAt = InstantCompat.now()))
        storage.addEvent(CustomEvent(eventName = "b", createdAt = InstantCompat.now(), link = "kept"))

        storage.updateEvents { if (it.link == null) it.copy(link = "backfilled") else it }

        val byName = storage.getEvents().associateBy { it.eventName }
        assertEquals("backfilled", byName.getValue("a").link)
        assertEquals("kept", byName.getValue("b").link)
    }

    @Test
    fun `updateEvents on empty storage is a no-op`() = runTest {
        storage.updateEvents { it.copy(link = "x") }

        assertEquals(emptyList<CustomEvent>(), storage.getEvents())
    }

    @Test
    fun `instants are serialized as ISO-8601 strings not epochMillis objects`() = runTest {
        storage.addEvent(event("test_event"))

        val preferences = context.getSharedPreferences(
            EventsStorage.GROVS_STORAGE,
            Context.MODE_PRIVATE
        )
        val rawJson = preferences.getString("stored_custom_events", null)

        assert(rawJson != null, { "Stored events JSON should not be null" })
        rawJson!!

        assert(
            rawJson.contains(Regex(""""created_at":"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"""")),
            { "created_at should be serialized as ISO-8601 string, but got: $rawJson" }
        )

        // epochMillis would appear if Gson fell back to its default reflective adapter for Instant.
        assert(
            !rawJson.contains("epochMillis"),
            { "created_at should not contain epochMillis field: $rawJson" }
        )
    }
}
