package io.grovs.e2e

import android.app.Application
import io.grovs.Grovs
import io.grovs.handlers.CustomEventsManager
import io.grovs.handlers.GrovsContext
import io.grovs.service.GrovsService
import io.grovs.storage.CustomEventsStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Uses the actual HTTP service and persistent queue; no fake backend deduplication logic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventDeliveryE2ETest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var context: GrovsContext
    private lateinit var storage: CustomEventsStorage
    private lateinit var manager: CustomEventsManager

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(app)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK flow tests")
        server = MockWebServer().apply { start() }
        context = GrovsContext().also {
            it.markAuthenticated("flow-test-device", it.consent.currentConfiguration)
            it.settings.baseURL = server.url("/").toString()
        }
        // Bind the real public enable/disable API to the context used by this delivery fixture.
        E2ETestUtils.installGrovsContext(context)
        storage = CustomEventsStorage(app)
        manager = CustomEventsManager(app, context, GrovsService(app, "test-key", context), storage, startFlushTimer = false)
    }

    @After
    fun tearDown() {
        manager.close()
        server.shutdown()
        E2ETestUtils.resetGrovsSingleton()
    }

    @Test
    fun `lost acknowledgement retains the event and retries the identical payload`() = runBlocking {
        // The entire POST reaches the server, which then drops the connection without a response.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        manager.track("checkout_completed", mapOf("order" to "order-123"), listOf("android"))
        val original = storage.getEvents().single()

        manager.flush()
        val first = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("The server must actually receive the first POST", first)
        assertEquals("/api/v1/sdk/events/batch", first!!.path)
        val firstBody = first.body.readUtf8()
        assertEquals(original.eventId, JSONObject(firstBody).getJSONArray("events").getJSONObject(0).getString("event_id"))
        assertEquals("Without acknowledgement the event must remain queued", original.eventId, storage.getEvents().single().eventId)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        manager.flush()
        val retry = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(retry)
        assertEquals("Retries must preserve ID, timestamp, session, properties and attribution", firstBody, retry!!.body.readUtf8())
        assertTrue("Only the acknowledged send removes the event", storage.getEvents().isEmpty())

        manager.flush()
        assertEquals("No third application-level send after acknowledgement", 2, server.requestCount)
    }

    @Test
    fun `disabled SDK retains a failed event until re-enabled`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"temporarily unavailable"}"""))
        manager.track("checkout_completed", null, null)
        manager.flush()
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val originalId = JSONObject(first.body.readUtf8()).getJSONArray("events").getJSONObject(0).getString("event_id")
        assertEquals(1, storage.getEvents().size)

        Grovs.setSDK(false)
        // A response is available: lack of traffic cannot be explained by an unreachable server.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        manager.flush()
        assertEquals("No new retry may begin while the SDK is disabled", 1, server.requestCount)
        assertEquals(originalId, storage.getEvents().single().eventId)

        Grovs.setSDK(true)
        manager.flush()
        val retry = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Re-enabling must permit delivery of the retained event", retry)
        assertEquals(originalId, JSONObject(retry!!.body.readUtf8()).getJSONArray("events").getJSONObject(0).getString("event_id"))
        assertTrue(storage.getEvents().isEmpty())
    }

    @Test
    fun `periodic flush starts no request after disabling the SDK`() = runBlocking {
        manager.close()
        val clock = TestScope()
        manager = CustomEventsManager(app, context, GrovsService(app, "test-key", context), storage,
            timerDispatcher = StandardTestDispatcher(clock.testScheduler),
            flushIntervalMs = 1_000, startFlushTimer = true)
        fun awaitTimerRequest(timeoutMs: Long): okhttp3.mockwebserver.RecordedRequest? {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            do {
                clock.runCurrent() // Resume storage/HTTP continuations arriving from real IO threads.
                server.takeRequest(25, TimeUnit.MILLISECONDS)?.let { return it }
            } while (System.nanoTime() < end)
            return null
        }
        // Positive control: the same real timer can deliver while enabled.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        manager.track("enabled_control", null, null)
        clock.runCurrent()
        clock.advanceTimeBy(1_001)
        assertNotNull("The timer fixture must send while enabled", awaitTimerRequest(2_000))
        val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (storage.getEvents().isNotEmpty() && System.nanoTime() < drainDeadline) {
            clock.runCurrent()
            Thread.sleep(10)
        }
        assertTrue("The control event must be acknowledged before the disable phase", storage.getEvents().isEmpty())

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        manager.track("queued_before_disable", null, null)
        Grovs.setSDK(false)
        clock.advanceTimeBy(1_001)
        assertNull("The periodic worker must honor disable, not only track()", awaitTimerRequest(1_000))
        assertEquals(1, storage.getEvents().size)
    }
    @Test
    fun `periodic HTTP delivery recovers when consent cancels a send in flight`() = runBlocking {
        manager.close()
        val clock = TestScope()
        manager = CustomEventsManager(app, context, GrovsService(app, "test-key", context), storage,
            timerDispatcher = StandardTestDispatcher(clock.testScheduler), flushIntervalMs = 1_000)
        fun awaitRequest(): okhttp3.mockwebserver.RecordedRequest {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                clock.runCurrent()
                server.takeRequest(10, TimeUnit.MILLISECONDS)?.let { return it }
            }
            throw AssertionError("Timer did not send an HTTP request")
        }
        fun awaitIdle(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) {
                clock.runCurrent()
                Thread.sleep(5)
            }
            assertTrue("Delivery did not finish", condition())
        }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            manager.track("before_revocation", null, null)
            clock.runCurrent()
            clock.advanceTimeBy(1_001)
            val first = awaitRequest()
            val originalId = JSONObject(first.body.readUtf8()).getJSONArray("events").getJSONObject(0).getString("event_id")
            Grovs.setSDK(false)
            val cleanup = context.consent.pendingWork()
            awaitIdle { cleanup.isCompleted }
            assertEquals(1, storage.getEvents().size)
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            Grovs.setSDK(true)
            clock.advanceTimeBy(1_001)
            val retry = awaitRequest()
            assertEquals(originalId, JSONObject(retry.body.readUtf8()).getJSONArray("events").getJSONObject(0).getString("event_id"))
            awaitIdle { runBlocking { storage.getEvents().isEmpty() } }

            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            manager.track("after_recovery", null, null)
            clock.advanceTimeBy(1_001)
            val next = awaitRequest()
            assertEquals("after_recovery", JSONObject(next.body.readUtf8()).getJSONArray("events").getJSONObject(0).getString("event_name"))
            awaitIdle { runBlocking { storage.getEvents().isEmpty() } }
            assertEquals("One cancelled send, its retry, then the new event", 3, server.requestCount)
        } finally {
            manager.close()
            awaitIdle { context.consent.registrationCount() == 0 }
        }
    }

}
