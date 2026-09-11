package io.grovs.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.utils.LSResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Exercises the real [GrovsService] clipboard additions against a MockWebServer:
 * the `clipboard_status` endpoint and the copy-to-clipboard link flags.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class GrovsServiceClipboardTest : ServiceTestBase() {

    // ==================== clipboardStatus ====================

    @Test
    fun `clipboardStatus parses the active flag`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"clipboard_active":true}"""))

        val result = service.clipboardStatus()

        assertTrue("Expected Success, got $result", result is LSResult.Success)
        assertEquals(true, (result as LSResult.Success).data)
        val recorded = mockWebServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/clipboard_status"))
    }

    @Test
    fun `clipboardStatus parses an inactive project`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"clipboard_active":false}"""))

        val result = service.clipboardStatus()

        assertTrue("Expected Success, got $result", result is LSResult.Success)
        assertEquals(false, (result as LSResult.Success).data)
    }

    @Test
    fun `clipboardStatus reports a non-2xx as Error after a single attempt`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"down"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"clipboard_active":true}"""))

        val result = service.clipboardStatus()

        assertTrue("Expected Error, got $result", result is LSResult.Error)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `clipboardStatus reports a body without the flag as Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))

        val result = service.clipboardStatus()

        assertTrue("Expected Error, got $result", result is LSResult.Error)
    }

    // ==================== generateLink copy-to-clipboard flags ====================

    @Test
    fun `generateLink sends both copy-to-clipboard flags`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"link":"https://x.sqd.link/abc"}"""))

        service.generateLink(
            title = "t", subtitle = null, imageURL = null, data = null, tags = null,
            customRedirects = null, showPreviewIos = null, showPreviewAndroid = null,
            copyToClipboardIos = true, copyToClipboardAndroid = false, tracking = null,
        )

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"copy_to_clipboard_ios\":true"))
        assertTrue(body, body.contains("\"copy_to_clipboard_android\":false"))
    }

    @Test
    fun `generateLink omits the copy-to-clipboard fields when null`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"link":"https://x.sqd.link/abc"}"""))

        service.generateLink(
            title = "t", subtitle = null, imageURL = null, data = null, tags = null,
            customRedirects = null, showPreviewIos = null, showPreviewAndroid = null,
            copyToClipboardIos = null, copyToClipboardAndroid = null, tracking = null,
        )

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(body, !body.contains("copy_to_clipboard_ios"))
        assertTrue(body, !body.contains("copy_to_clipboard_android"))
    }
}
