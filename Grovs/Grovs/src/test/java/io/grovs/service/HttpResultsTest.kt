package io.grovs.service

import com.google.gson.GsonBuilder
import io.grovs.utils.LSResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class HttpResultsTest {

    private val gson = GsonBuilder().setLenient().create()
    private val json = "application/json".toMediaType()

    private fun error(code: Int, body: String) = Response.error<String>(code, body.toResponseBody(json))
    private fun unitError(code: Int, body: String) = Response.error<Unit>(code, body.toResponseBody(json))

    private fun retryableFrom(code: Int, body: String): RetryableHttpException {
        val thrown = runCatching { error(code, body).toResult("Authenticate", gson) }.exceptionOrNull()
        assertTrue("expected RetryableHttpException, got $thrown", thrown is RetryableHttpException)
        return thrown as RetryableHttpException
    }

    @Test
    fun `a 500 is retryable whether or not its body parses`() {
        assertEquals(500, retryableFrom(500, """{"error":"boom"}""").code)
        // The empty body is the case that used to throw an NPE and retry by accident.
        assertEquals(500, retryableFrom(500, "").code)
    }

    @Test
    fun `a 429 is retryable`() {
        assertEquals(429, retryableFrom(429, "").code)
    }

    @Test
    fun `a 401 is terminal and keeps its status and reason`() {
        val result = error(401, """{"error":"bad key"}""").toResult("Authenticate", gson) as LSResult.Error

        assertEquals(401, (result.exception as HttpStatusException).code)
        assertTrue(result.exception.message!!.contains("bad key"))
    }

    @Test
    fun `a 400 with an unparseable body is terminal, not a crash`() {
        val result = error(400, "not json at all").toResult("Authenticate", gson)

        assertTrue(result is LSResult.Error)
    }

    @Test
    fun `a 200 with a body succeeds`() {
        assertEquals("payload", (Response.success("payload").toResult("Authenticate", gson) as LSResult.Success).data)
    }

    @Test
    fun `a 200 with no body is terminal when a payload was expected`() {
        assertTrue(Response.success<String>(null).toResult("Authenticate", gson) is LSResult.Error)
    }

    @Test
    fun `a bodiless 200 succeeds when the status is the whole answer`() {
        // updateAttributes and markNotificationAsRead are Response<Unit>; a backend answering
        // head :ok must not look like a failure.
        assertTrue(Response.success<Unit>(null).toStatusResult("Set attributes", gson) is LSResult.Success)
    }

    @Test
    fun `a status-only endpoint still retries 5xx and returns 4xx`() {
        val thrown = runCatching { unitError(503, "").toStatusResult("Set attributes", gson) }.exceptionOrNull()
        assertTrue(thrown is RetryableHttpException)

        val result = unitError(422, """{"error":"nope"}""").toStatusResult("Set attributes", gson) as LSResult.Error
        assertEquals(422, (result.exception as HttpStatusException).code)
    }
}
