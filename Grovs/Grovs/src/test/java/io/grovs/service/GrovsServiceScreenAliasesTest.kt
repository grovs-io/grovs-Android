package io.grovs.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.e2e.E2ETestUtils
import io.grovs.handlers.GrovsContext
import io.grovs.utils.LSResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the real [GrovsService.syncScreenAliases] against a MockWebServer. The empty-map case
 * matters on its own: it is how a caller clears the aliases the backend already holds, so it has to
 * reach the wire rather than being answered locally.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class GrovsServiceScreenAliasesTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var grovsContext: GrovsContext
    private lateinit var service: GrovsService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Pre-seed the cached user agent so the header interceptor doesn't spin up a WebView off the main thread.
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )

        mockWebServer = MockWebServer()
        mockWebServer.start()

        grovsContext = GrovsContext()
        grovsContext.settings.baseURL = mockWebServer.url("/").toString()

        service = GrovsService(context = context, apiKey = "test-key", grovsContext = grovsContext)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `aliases are sent as identifier and alias pairs`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = service.syncScreenAliases(mapOf("MainActivity" to "Home"))

        assertTrue("Expected Success, got $result", result is LSResult.Success)
        val recorded = mockWebServer.takeRequest()
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue("Expected the identifier in $body", body.contains("MainActivity"))
        assertTrue("Expected the alias in $body", body.contains("Home"))
    }

    @Test
    fun `an empty alias map is still sent so the backend clears what it holds`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = service.syncScreenAliases(emptyMap())

        assertTrue("Expected Success, got $result", result is LSResult.Success)
        assertEquals(
            "the clear has to reach the backend, not be answered locally",
            1,
            mockWebServer.requestCount
        )
        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue("Expected an empty alias list in $body", body.contains("[]"))
    }

    @Test
    fun `a non-2xx is reported as an error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = service.syncScreenAliases(mapOf("MainActivity" to "Home"))

        assertTrue("Expected Error, got $result", result is LSResult.Error)
    }
}
