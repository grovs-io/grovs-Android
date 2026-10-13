package io.grovs.handlers

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushImageLoaderTest {

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).build()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    @Test
    fun `only https urls are loaded`() {
        assertNull(PushImageLoader.load("http://cdn.grovs.io/i.png"))
        assertNull(PushImageLoader.load("/relative/blob/path.png"))
        assertNull(PushImageLoader.load(null))
    }

    @Test
    fun `an image larger than the limit is skipped`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2_000))))
        assertNull(PushImageLoader.download(server.url("/big.png").toString(), client, maxBytes = 1_000))
    }

    @Test
    fun `a failed download is skipped`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(PushImageLoader.download(server.url("/missing.png").toString(), client))
    }

    @Test
    fun `a slow download is skipped within the timeout`() {
        server.enqueue(MockResponse().setBody("x").setHeadersDelay(2, TimeUnit.SECONDS))
        assertNull(PushImageLoader.download(server.url("/slow.png").toString(), client))
    }
}
