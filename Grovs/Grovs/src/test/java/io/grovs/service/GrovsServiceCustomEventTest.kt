package io.grovs.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.e2e.E2ETestUtils
import io.grovs.handlers.GrovsContext
import io.grovs.model.CustomEvent
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
import io.grovs.utils.InstantCompat
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
 * Exercises the real [GrovsService.addCustomEvent] against a MockWebServer, unlike
 * [CustomEventServiceTest], which uses the [TestableGrovsService] double. The service makes a
 * single attempt per call; retrying is owned by the flush cycle.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class GrovsServiceCustomEventTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var grovsContext: GrovsContext
    private lateinit var service: GrovsService

    private val event = CustomEvent(eventName = "checkout", createdAt = InstantCompat.now())

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
    fun `a 200 returns Success`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = service.addCustomEvent(event)

        assertTrue("Expected Success, got $result", result is LSResult.Success)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `a 404 is terminal so the caller drops the event`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"nope"}"""))

        val result = service.addCustomEvent(event)

        assertTrue("Expected Error, got $result", result is LSResult.Error)
        val exception = (result as LSResult.Error).exception
        assertTrue(exception is GrovsException)
        assertEquals(GrovsErrorCode.EVENT_DISPATCH_ERROR, (exception as GrovsException).errorCode)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `a 500 returns a plain error after a single attempt so the caller keeps the event`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"down"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = service.addCustomEvent(event)

        assertTrue("Expected Error, got $result", result is LSResult.Error)
        val exception = (result as LSResult.Error).exception
        assertTrue(
            "Expected a plain (kept) error, got a terminal EVENT_DISPATCH_ERROR",
            exception !is GrovsException || exception.errorCode != GrovsErrorCode.EVENT_DISPATCH_ERROR
        )
        // No in-call retry: the flush cycle owns retrying.
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `a 429 returns a plain error so the caller keeps the event`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("""{"error":"slow down"}""")
        )

        val result = service.addCustomEvent(event)

        assertTrue("Expected Error, got $result", result is LSResult.Error)
        val exception = (result as LSResult.Error).exception
        assertTrue(
            "Expected a plain (kept) error, got a terminal EVENT_DISPATCH_ERROR",
            exception !is GrovsException || exception.errorCode != GrovsErrorCode.EVENT_DISPATCH_ERROR
        )
        assertEquals(1, mockWebServer.requestCount)
    }
}
