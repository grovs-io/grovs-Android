package io.grovs.handlers

import android.graphics.Bitmap
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun `a large image is scaled down to the notification size, keeping its shape`() {
        val bitmap = PushImageLoader.decodeScaled(png(4_000, 3_000))!!
        assertEquals(1_000, bitmap.width)
        assertEquals(750, bitmap.height)
    }

    @Test
    fun `an image just over the limit is halved`() {
        val bitmap = PushImageLoader.decodeScaled(png(1_200, 600))!!
        assertEquals(600, bitmap.width)
        assertEquals(300, bitmap.height)
    }

    @Test
    fun `a small image is kept at its size`() {
        val bitmap = PushImageLoader.decodeScaled(png(300, 200))!!
        assertEquals(300, bitmap.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun `bytes that are not an image give nothing`() {
        assertNull(PushImageLoader.decodeScaled(ByteArray(10) { 7 }))
    }

    @Test
    fun `a downloaded image is scaled down`() {
        server.enqueue(MockResponse().setBody(Buffer().write(png(2_048, 512))))
        val bitmap = PushImageLoader.download(server.url("/wide.png").toString(), client)!!
        assertTrue(maxOf(bitmap.width, bitmap.height) <= PushImageLoader.MAX_SIDE_PX)
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()
}
