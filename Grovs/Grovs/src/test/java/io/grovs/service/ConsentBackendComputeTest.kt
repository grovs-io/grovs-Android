package io.grovs.service

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsentBackendComputeTest {
    private val backend = ConsentBackend()

    @After fun close() = backend.close()

    @Test
    fun `a computed route answers from the request it saw`() {
        backend.answer("echo") { seen -> MockResponse().setResponseCode(201).setBody("got ${seen.body}") }

        val response = OkHttpClient().newCall(
            Request.Builder().url(backend.baseUrl + "api/v1/sdk/echo").post("ping".toRequestBody()).build()
        ).execute()

        assertEquals(201, response.code)
        assertEquals("got ping", response.body!!.string())
        assertEquals(1, backend.count("echo"))
    }
}
