package io.grovs.model

import io.grovs.utils.InstantCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventIdentityTest {

    @Test
    fun `each event gets a unique event id`() {
        val now = InstantCompat.now()
        val a = Event(event = EventType.APP_OPEN, createdAt = now)
        val b = Event(event = EventType.APP_OPEN, createdAt = now)

        assertNotEquals(a.eventId, b.eventId)
    }

    @Test
    fun `session id defaults to null`() {
        assertNull(Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now()).sessionId)
    }

    @Test
    fun `equality still ignores event id so storage dedup keeps working`() {
        val now = InstantCompat.now()
        val a = Event(event = EventType.APP_OPEN, createdAt = now)
        val b = Event(event = EventType.APP_OPEN, createdAt = now)

        // Different eventIds, but EventsStorage.removeEvent relies on these being equal.
        assertEquals(a, b)
    }
}
