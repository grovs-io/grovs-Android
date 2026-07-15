package io.grovs.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.Grovs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class CustomEventsE2ETest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupTestApplication(RuntimeEnvironment.getApplication())
        // Must pre-seed the user-agent cache: this test never builds an Activity to pump the main
        // looper, and WebViewUtils.getUserAgent() otherwise blocks forever waiting for it.
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        mockWebServer = MockWebServer()
        mockWebServer.start()
        E2ETestUtils.enqueueAuthenticationSuccess(mockWebServer)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        E2ETestUtils.resetGrovsSingleton()
    }

    @Test
    fun `track sends a custom event with name properties and tags`() = runTest {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
        )
        E2ETestUtils.getAuthenticationJob()?.join()

        // Confirm the SDK genuinely authenticated before relying on that below.
        assertEquals("grovs_123", E2ETestUtils.getGrovsId())

        Grovs.track("checkout_completed", mapOf("sku" to "abc"), listOf("shop"))
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, path = "/api/v1/sdk/event/custom")
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("checkout_completed", body.getString("event_name"))
        assertEquals("abc", body.getJSONObject("properties").getString("sku"))
        assertEquals("shop", body.getJSONArray("tags").getString(0))
        assertTrue(body.getString("event_id").isNotEmpty())
        assertTrue(body.getString("session_id").isNotEmpty())
    }

    @Test
    fun `setGlobalTags called from a background thread is still applied to subsequent events`() = runTest {
        // Regression guard: setGlobalTags() must be confined to grovsContext.serialDispatcher (the
        // same dispatcher persist() reads globalTags on), so a background-thread call isn't dropped
        // or reordered relative to a subsequent track() call from a different thread.
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
        )
        E2ETestUtils.getAuthenticationJob()?.join()
        assertEquals("grovs_123", E2ETestUtils.getGrovsId())

        Thread { Grovs.setGlobalTags(listOf("android", "prod")) }.apply {
            start()
            join()
        }
        Grovs.track("checkout_completed", null, tags = listOf("shop"))
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, path = "/api/v1/sdk/event/custom")
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        val tags = body.getJSONArray("tags")
        val tagValues = (0 until tags.length()).map { tags.getString(it) }
        assertTrue("Expected global tags to be merged, got $tagValues", tagValues.containsAll(listOf("shop", "android", "prod")))
    }

    @Test
    fun `reserved event names never reach the network`() = runTest {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
        )
        E2ETestUtils.getAuthenticationJob()?.join()

        Grovs.track("app_open", null, null)
        E2ETestUtils.flushCustomEvents()

        assertEquals(null, E2ETestUtils.awaitRequestFor(mockWebServer, path = "/api/v1/sdk/event/custom"))
    }
}
