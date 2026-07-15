package io.grovs.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.utils.InstantCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class EventsStorageCapsTest {

    private lateinit var context: Context
    private lateinit var storage: EventsStorage

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        storage = EventsStorage(context)
    }

    @Test
    fun `getEvents returns empty list when nothing is stored`() = runTest {
        assertEquals(emptyList<Event>(), storage.getEvents())
    }

    @Test
    fun `storage caps at MAX_STORED_EVENTS and drops the oldest`() = runTest {
        val base = InstantCompat.now()
        repeat(EventsStorage.MAX_STORED_EVENTS + 10) { i ->
            storage.addEvent(
                Event(
                    event = EventType.APP_OPEN,
                    createdAt = base.plusMillis(i.toLong()),
                )
            )
        }

        val stored = storage.getEvents()
        assertEquals(EventsStorage.MAX_STORED_EVENTS, stored.size)
        // The 10 oldest were evicted, so the earliest survivor is index 10.
        assertEquals(base.plusMillis(10), stored.first().createdAt)
    }

    @Test
    fun `events older than seven days are discarded on read`() = runTest {
        val eightDaysMs = 8L * 24 * 60 * 60 * 1000
        val stale = Event(
            event = EventType.APP_OPEN,
            createdAt = InstantCompat.now().minusMillis(eightDaysMs),
        )
        val fresh = Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now())

        storage.addEvent(stale)
        storage.addEvent(fresh)

        val stored = storage.getEvents()
        assertEquals(1, stored.size)
        assertTrue(stored.first().createdAt.isAfter(InstantCompat.now().minusMillis(eightDaysMs)))
    }
}
