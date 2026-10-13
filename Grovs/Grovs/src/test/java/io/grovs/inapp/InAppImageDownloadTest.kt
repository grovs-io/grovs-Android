package io.grovs.inapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class InAppImageDownloadTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetch(path: String): ByteArray? {
        var result: ByteArray? = null
        val latch = CountDownLatch(1)
        InAppImages.download(server.url(path).toString()) { result = it; latch.countDown() }
        assertTrue("download did not finish", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test
    fun `downloads the body and sends no sdk headers`() {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(7, 8, 9))))
        assertArrayEquals(byteArrayOf(7, 8, 9), fetch("/a.png"))
        val request = server.takeRequest()
        assertNull(request.getHeader("PROJECT-KEY"))
        assertNull(request.getHeader("IDENTIFIER"))
        assertNull(request.getHeader("LINKSQUARED"))
    }

    @Test
    fun `a failing status gives nothing`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        assertNull(fetch("/a.png"))
    }

    @Test
    fun `a body over five megabytes gives nothing`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(InAppImages.MAX_BYTES + 1))))
        assertNull(fetch("/big.png"))
    }

    @Test
    fun `an address OkHttp cannot read gives nothing`() {
        var result: ByteArray? = byteArrayOf(1)
        var called = false
        InAppImages.download("https://") { result = it; called = true }
        assertTrue(called)
        assertNull(result)
    }

    @Test
    fun `a body of unknown length over five megabytes gives nothing`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(InAppImages.MAX_BYTES + 1)), 8192))
        assertNull(fetch("/chunked.png"))
    }

    @Test
    fun `a body of exactly five megabytes is accepted`() {
        val body = ByteArray(InAppImages.MAX_BYTES)
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        assertArrayEquals(body, fetch("/exact.png"))
    }

    @Test
    fun `a redirect that changes the scheme is not followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${server.port}/other.png"))
        assertNull(fetch("/a.png"))
        assertEquals(1, server.requestCount)
    }
}
