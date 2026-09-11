package io.grovs

import android.app.Application
import android.os.Looper
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.TestActivity
import io.grovs.model.DeeplinkDetails
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Listener-style public APIs do their work on the SDK's IO dispatcher and must hand the result
 * back on the main looper, never on the worker that produced it.
 *
 * Runs against the real service with a MockWebServer and the real `Dispatchers.Main`; the main
 * looper is pumped while waiting so the hop back to main is actually exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicApiCallbackThreadTest {

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
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to json("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "create_link" to json("""{"link":"https://test.grovs.io/generated"}"""),
            "link_details" to json("""{"title":"T","subtitle":"S","image_url":null,"data":{"k":"v"}}"""),
            "number_of_unread_notifications" to json("""{"number_of_unread_notifications":3}"""),
            "data_for_device" to json("""{"link":"https://test.grovs.io/deferred","data":{"ref":"x"}}"""),
        ))
        E2ETestUtils.configureAndWaitForAuthOnly(application, baseURL = server.url("/").toString())
        E2ETestUtils.assertAuthenticationCompleted()
    }

    @After
    fun tearDown() {
        E2ETestUtils.cleanupMockWebServer(server)
        E2ETestUtils.resetGrovsSingleton()
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class Delivery {
        @Volatile var looper: Looper? = null
        @Volatile var delivered = false
        fun record() { looper = Looper.myLooper(); delivered = true }
    }

    private fun awaitDelivery(delivery: Delivery, what: String) {
        assertNull("$what must not be invoked inline from the caller", delivery.looper)
        E2ETestUtils.waitForCondition(description = what) { delivery.delivered }
        assertSame("$what was delivered off the main looper", Looper.getMainLooper(), delivery.looper)
    }

    @Test
    fun `generateLink listener is invoked on the main looper`() {
        val delivery = Delivery()
        var link: String? = null

        Grovs.generateLink(title = "Title") { generated, _ ->
            link = generated
            delivery.record()
        }

        awaitDelivery(delivery, "onLinkGenerated")
        assertEquals("https://test.grovs.io/generated", link)
    }

    @Test
    fun `linkDetails listener is invoked on the main looper`() {
        val delivery = Delivery()
        var details: Map<String, Any>? = null

        Grovs.linkDetails(path = "abc123") { result, _ ->
            details = result
            delivery.record()
        }

        awaitDelivery(delivery, "onLinkDetails")
        assertEquals("T", details?.get("title"))
    }

    @Test
    fun `numberOfUnreadMessages result is delivered on the main looper`() {
        val delivery = Delivery()
        var count: Int? = null

        Grovs.numberOfUnreadMessages { result ->
            count = result
            delivery.record()
        }

        awaitDelivery(delivery, "unread count callback")
        assertEquals(3, count)
    }

    @Test
    fun `deeplink listener is invoked on the main looper`() {
        val delivery = Delivery()
        var received: DeeplinkDetails? = null
        val controller = Robolectric.buildActivity(TestActivity::class.java).create()
        Grovs.setOnDeeplinkReceivedListener(controller.get()) { details ->
            received = details
            delivery.record()
        }

        controller.start().resume()

        awaitDelivery(delivery, "onDeeplinkReceived")
        assertEquals("https://test.grovs.io/deferred", received?.link)
        controller.pause().stop().destroy()
    }
    @Test
    fun `disabled link errors called from a worker are delivered once on main`() {
        Grovs.setSDK(false)
        val results = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Looper?>>()
        val worker = Thread {
            Grovs.generateLink(title = "disabled") { _, _ -> results += "generate" to Looper.myLooper() }
            Grovs.linkDetails("disabled") { _, _ -> results += "details" to Looper.myLooper() }
        }
        worker.start()
        worker.join(1_000)
        org.junit.Assert.assertFalse("Public calls must return without waiting for main", worker.isAlive)
        assertEquals("No callback may run inline on the worker", 0, results.size)
        E2ETestUtils.waitForCondition(description = "both consent errors") { results.size == 2 }
        results.forEach { (name, looper) -> assertSame(name, Looper.getMainLooper(), looper) }
        assertEquals(setOf("generate", "details"), results.map { it.first }.toSet())
    }

}
