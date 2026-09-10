package io.grovs.e2e

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import io.grovs.Grovs
import io.grovs.storage.CustomEventsStorage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A backend that rejects system events must not stall the SDK's own work queue.
 *
 * The public call returns immediately either way; what this guards is the serial dispatcher
 * behind it. Retry back-off for a failed event send must not hold that dispatcher, or every
 * later `track()`, screen view and deep-link resolution queues up behind the back-off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FailingBackendQueueE2ETest {

    private lateinit var application: Application
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK threading tests")
        server = E2ETestUtils.createMockWebServer()
        // One events/batch entry answers 503 for every batch, system and custom alike.
        // The test cannot pass vacuously: track() only writes to local storage and never
        // flushes synchronously, so when awaitRequestFor below returns, the batch the server
        // has already received and failed with 503 is the system-event batch.
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to json(200, """{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json(200, """{"last_seen":null}"""),
            "data_for_device" to json(200, """{"link":"https://test.grovs.io/campaign","data":null}"""),
            "events/batch" to json(503, """{"error":"events backend unavailable"}"""),
        ))
        E2ETestUtils.configureAndWaitForAuthOnly(application, baseURL = server.url("/").toString())
        E2ETestUtils.assertAuthenticationCompleted()
    }

    @After
    fun tearDown() {
        E2ETestUtils.cleanupMockWebServer(server)
        E2ETestUtils.resetGrovsSingleton()
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `custom events are still persisted promptly while system event sends are failing`() {
        val controller = Robolectric.buildActivity(TestActivity::class.java).create().start().resume()

        // A resolved direct link releases the launch events (INSTALL, OPEN) for immediate sending;
        // the backend answers 503 to every one of them.
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("testapp://open?link=campaign"))
        controller.newIntent(link)
        assertNotNull(
            "The SDK must have attempted to send a system event before this test means anything",
            E2ETestUtils.awaitRequestFor(server, "/api/v1/sdk/events/batch", timeoutMs = 5_000)
        )

        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val start = System.nanoTime()
        Grovs.track("checkout_completed", mapOf("total" to 10), null)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("track() took ${elapsedMs}ms on the main thread", elapsedMs <= 500)

        val storage = CustomEventsStorage(application)
        E2ETestUtils.waitForCondition(timeoutMs = 1_500, description = "checkout_completed to be persisted") {
            runBlocking { storage.getEvents() }.any { it.eventName == "checkout_completed" }
        }

        controller.pause().stop().destroy()
    }
}
