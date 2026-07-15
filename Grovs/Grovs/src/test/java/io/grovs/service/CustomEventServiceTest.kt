package io.grovs.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.MockGrovsApi
import io.grovs.handlers.GrovsContext
import io.grovs.model.CustomEvent
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class CustomEventServiceTest {

    private lateinit var context: Context
    private lateinit var mockApi: MockGrovsApi
    private lateinit var service: TestableGrovsService

    private val event = CustomEvent(eventName = "checkout", createdAt = InstantCompat.now())

    private fun errorBody(code: Int): Response<Unit> = Response.error(
        code,
        """{"error":"nope"}""".toResponseBody("application/json".toMediaTypeOrNull())
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockApi = MockGrovsApi()
        service = TestableGrovsService(
            context = context,
            apiKey = "test-key",
            grovsContext = GrovsContext(),
            testApi = mockApi,
        )
    }

    @Test
    fun `successful custom event returns success`() = runTest {
        mockApi.addCustomEventResponse = Response.success(Unit)

        val result = service.addCustomEvent(event)

        assertTrue(result is LSResult.Success)
    }

    @Test
    fun `404 returns a terminal EVENT_DISPATCH_ERROR so the caller drops the event`() = runTest {
        mockApi.addCustomEventResponse = errorBody(404)

        val result = service.addCustomEvent(event)

        assertTrue(result is LSResult.Error)
        val exception = (result as LSResult.Error).exception
        assertTrue(exception is GrovsException)
        assertEquals(GrovsErrorCode.EVENT_DISPATCH_ERROR, (exception as GrovsException).errorCode)
    }

    @Test
    fun `422 is also terminal`() = runTest {
        mockApi.addCustomEventResponse = errorBody(422)

        val result = service.addCustomEvent(event)

        val exception = (result as LSResult.Error).exception
        assertEquals(GrovsErrorCode.EVENT_DISPATCH_ERROR, (exception as GrovsException).errorCode)
    }

    @Test
    fun `500 is a plain error so the caller keeps the event for retry`() = runTest {
        mockApi.addCustomEventResponse = errorBody(500)

        val result = service.addCustomEvent(event)

        assertTrue(result is LSResult.Error)
        val exception = (result as LSResult.Error).exception
        // Not a terminal dispatch error — the event stays in storage.
        assertTrue(exception !is GrovsException || exception.errorCode != GrovsErrorCode.EVENT_DISPATCH_ERROR)
    }

    @Test
    fun `the service sends the session the event was created with, and does not overwrite it`() = runTest {
        mockApi.addCustomEventResponse = Response.success(Unit)
        val createdInSession = CustomEvent(
            eventName = "checkout",
            sessionId = "session-A",
            createdAt = InstantCompat.now(),
        )

        service.addCustomEvent(createdInSession)

        // The service must transmit the session the event was BORN in, never the current one.
        assertEquals("session-A", mockApi.lastCustomEvent?.sessionId)
    }
}
