package io.grovs.model

import com.google.gson.GsonBuilder
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSJsonInstantCompatTypeAdapterFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json.JSONObject is an Android SDK stub outside Robolectric, and this module's unit tests
// run with unitTests.isReturnDefaultValues = true, so a plain JUnit test would silently get nulls
// back from it instead of parsed JSON. Robolectric supplies the real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BatchEventsTest {
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(LSJsonInstantCompatTypeAdapterFactory())
        .create()

    @Test
    fun `request serialises system and custom events by their runtime type`() {
        val system = Event(event = EventType.APP_OPEN, createdAt = InstantCompat.ofEpochMilli(1_000L), sessionId = "s1")
        val custom = CustomEvent(eventName = "checkout", createdAt = InstantCompat.ofEpochMilli(2_000L), sessionId = "s1")

        val json = JSONObject(gson.toJson(BatchEventsRequest(listOf(system, custom))))
        val events = json.getJSONArray("events")

        assertEquals(2, events.length())
        assertEquals("app_open", events.getJSONObject(0).getString("event"))
        assertEquals(system.eventId, events.getJSONObject(0).getString("event_id"))
        assertTrue(events.getJSONObject(0).has("created_at"))
        assertEquals("checkout", events.getJSONObject(1).getString("event_name"))
        assertEquals(custom.eventId, events.getJSONObject(1).getString("event_id"))
    }

    @Test
    fun `response parses accepted, rejected and errors`() {
        val response = gson.fromJson(
            """{"accepted":2,"rejected":1,"errors":[{"index":1,"error":"unknown event type 'x'"}]}""",
            BatchEventsResponse::class.java
        )

        assertEquals(2, response.accepted)
        assertEquals(1, response.rejected)
        assertEquals(listOf(BatchEventError(index = 1, error = "unknown event type 'x'")), response.errors)
    }

    @Test
    fun `response tolerates a missing errors array`() {
        val response = gson.fromJson("""{"accepted":3,"rejected":0}""", BatchEventsResponse::class.java)

        assertEquals(emptyList<BatchEventError>(), response.errors)
    }
}
