package io.grovs.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.model.CustomEvent
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises the real [GrovsService] batch send against a MockWebServer. One attempt per call. */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class GrovsServiceBatchTest : ServiceTestBase() {

    private fun systemEvent() = Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now(), sessionId = "s1")
    private fun customEvent() = CustomEvent(eventName = "checkout", createdAt = InstantCompat.now(), sessionId = "s1")

    @Test
    fun `system events are posted to events batch as an events array`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":2,"rejected":0,"errors":[]}"""))
        val events = listOf(systemEvent(), systemEvent())

        val result = service.addEvents(events)

        val request = mockWebServer.takeRequest()
        assertEquals("/api/v1/sdk/events/batch", request.path)
        val body = JSONObject(request.body.readUtf8()).getJSONArray("events")
        assertEquals(2, body.length())
        assertEquals("app_open", body.getJSONObject(0).getString("event"))
        assertEquals(events[0].eventId, body.getJSONObject(0).getString("event_id"))
        assertTrue(result is LSResult.Success)
        assertEquals(2, (result as LSResult.Success).data.accepted)
    }

    @Test
    fun `custom events are posted to the same endpoint with event_name`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1,"rejected":0,"errors":[]}"""))

        val result = service.addCustomEvents(listOf(customEvent()))

        val body = JSONObject(mockWebServer.takeRequest().body.readUtf8()).getJSONArray("events")
        assertEquals("checkout", body.getJSONObject(0).getString("event_name"))
        assertTrue(result is LSResult.Success)
    }

    @Test
    fun `a 2xx with rejected items is still Success and reports them`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"accepted":1,"rejected":1,"errors":[{"index":1,"error":"unknown event type 'x'"}]}"""))

        val result = service.addEvents(listOf(systemEvent(), systemEvent()))

        assertTrue(result is LSResult.Success)
        val response = (result as LSResult.Success).data
        assertEquals(1, response.rejected)
        assertEquals(1, response.errors.single().index)
    }

    @Test
    fun `a 2xx with an empty body is Success`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = service.addEvents(listOf(systemEvent()))

        assertTrue(result is LSResult.Success)
        assertEquals(1, (result as LSResult.Success).data.accepted)
    }

    @Test
    fun `a 5xx is Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))

        assertTrue(service.addEvents(listOf(systemEvent())) is LSResult.Error)
    }

    @Test
    fun `a non-2xx is Error carrying its status`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"events must be an array"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Invalid credentials"}"""))

        val refused = (service.addEvents(listOf(systemEvent())) as LSResult.Error).exception as HttpStatusException
        val forbidden = (service.addEvents(listOf(systemEvent())) as LSResult.Error).exception as HttpStatusException

        assertEquals(400, refused.code)
        assertEquals(403, forbidden.code)
    }

    @Test
    fun `a dropped connection is Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue(service.addCustomEvents(listOf(customEvent())) is LSResult.Error)
    }

    @Test
    fun `an empty list is Success without a request`() = runTest {
        val result = service.addEvents(emptyList())

        assertTrue(result is LSResult.Success)
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `more than MAX_BATCH_SIZE events is rejected locally`() = runTest {
        val events = List(GrovsService.MAX_BATCH_SIZE + 1) { systemEvent() }

        val result = service.addEvents(events)

        assertTrue(result is LSResult.Error)
        assertEquals(0, mockWebServer.requestCount)
    }
}
