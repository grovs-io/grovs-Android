package io.grovs.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.model.CustomEvent
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class GrovsServiceInAppTest : ServiceTestBase() {

    private fun body() = JsonObject().apply {
        add("campaigns_changed_at", com.google.gson.JsonNull.INSTANCE)
        addProperty("user_changed_at", "u1")
        addProperty("format", 1)
        addProperty("sdk_version", "3.1.0")
    }

    @Test
    fun `sync posts the stamps and returns the answer as it is`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"unchanged":true,"server_time":"2026-10-13T09:00:00Z","store_url":null}"""))

        val result = service.inAppSync(body())

        val request = mockWebServer.takeRequest()
        assertEquals("/api/v1/sdk/in_app/sync", request.path)
        assertEquals("POST", request.method)
        assertEquals("test-key", request.getHeader("PROJECT-KEY"))
        val sent = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(1, sent.get("format").asInt)
        assertEquals("u1", sent.get("user_changed_at").asString)
        assertTrue(!sent.has("campaigns_changed_at") || sent.get("campaigns_changed_at").isJsonNull)
        assertTrue(result is LSResult.Success)
        assertTrue((result as LSResult.Success).data.get("unchanged").asBoolean)
    }

    @Test
    fun `a 404 is a failed sync`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("{}"))

        val first = service.inAppSync(body())
        val second = service.inAppSync(body())

        assertTrue(first is LSResult.Error)
        assertEquals(404, ((first as LSResult.Error).exception as HttpStatusException).code)
        assertTrue(second is LSResult.Error)
    }

    @Test
    fun `a 2xx whose body is not an object is a failed sync`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(service.inAppSync(body()) is LSResult.Error)
    }

    @Test
    fun `a custom events batch hands its in_app stamps over`() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        service.onBatchStamps = { campaigns, user -> seen.add(campaigns to user) }
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"accepted":1,"rejected":0,"errors":[],"in_app":{"campaigns_changed_at":"c2","user_changed_at":"u1"}}"""))

        val result = service.addCustomEvents(listOf(CustomEvent(eventName = "x", createdAt = InstantCompat.now())))

        assertTrue(result is LSResult.Success)
        assertEquals(listOf<Pair<String?, String?>>("c2" to "u1"), seen)
    }

    @Test
    fun `a system events batch hands its in_app stamps over`() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        service.onBatchStamps = { campaigns, user -> seen.add(campaigns to user) }
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"accepted":1,"rejected":0,"errors":[],"in_app":{"campaigns_changed_at":"off","user_changed_at":"off"}}"""))

        service.addEvents(listOf(Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now(), sessionId = "s1")))

        assertEquals(listOf<Pair<String?, String?>>("off" to "off"), seen)
    }

    @Test
    fun `a malformed in_app value is ignored and the batch still succeeds`() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        service.onBatchStamps = { campaigns, user -> seen.add(campaigns to user) }
        for (inApp in listOf("[1]", "5", "null", "\"x\"")) {
            mockWebServer.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"accepted":1,"rejected":0,"errors":[],"in_app":$inApp}"""))
            val result = service.addCustomEvents(listOf(CustomEvent(eventName = "x", createdAt = InstantCompat.now())))
            assertTrue(inApp, result is LSResult.Success)
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a batch without in_app tells nobody`() = runTest {
        var told = false
        service.onBatchStamps = { _, _ -> told = true }
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1,"rejected":0,"errors":[]}"""))
        service.addCustomEvents(listOf(CustomEvent(eventName = "x", createdAt = InstantCompat.now())))
        assertTrue(!told)
    }
}
