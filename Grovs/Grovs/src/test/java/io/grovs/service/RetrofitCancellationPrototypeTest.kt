package io.grovs.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.IOException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Pins the Retrofit 2.9 / OkHttp 4.9 cancellation behaviour the consent request executor relies on:
 * cancelling the coroutine of one suspend call cancels exactly that OkHttp call, whether it is still
 * queued in OkHttp's dispatcher, connecting, or waiting for the response, and the coroutine ends
 * with the cancellation cause it was given rather than an I/O failure. Other calls on the same
 * client are unaffected (no shared cancelAll).
 */
class RetrofitCancellationPrototypeTest {

    private interface Api {
        @GET("slow")
        suspend fun slow(): Response<ResponseBody>

        @GET("fast")
        suspend fun fast(): Response<ResponseBody>
    }

    private class Revoked : CancellationException("revoked for the prototype")

    private lateinit var server: MockWebServer
    private val release = CountDownLatch(1)
    private val serverSawSlow = CountDownLatch(1)
    private val interceptorPaths = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/slow" -> {
                    serverSawSlow.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("slow")
                }
                "/fast" -> MockResponse().setBody("fast")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        release.countDown()
        server.shutdown()
    }

    private fun client(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                interceptorPaths += chain.request().url.encodedPath
                chain.proceed(chain.request())
            }
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    events += "canceled ${call.request().url.encodedPath}"
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    events += "failed ${call.request().url.encodedPath}"
                }
            })
            .apply(configure)
            .build()

    private fun api(callFactory: Call.Factory): Api = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .callFactory(callFactory)
        .build()
        .create(Api::class.java)

    private fun awaitEvent(event: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (event !in events) {
            check(System.nanoTime() < deadline) { "timed out waiting for '$event', saw $events" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `cancelling a call waiting for its response cancels that OkHttp call only`() = runBlocking {
        val client = client()
        val api = api(client)
        val call = async(Dispatchers.IO) { api.slow() }
        assertTrue("the request reached the server", serverSawSlow.await(5, TimeUnit.SECONDS))

        val cause = Revoked()
        call.cancel(cause)
        withTimeout(1_000) { call.join() }

        assertSame("the coroutine ends with the given cause, not an IOException", cause, call.getCompletionExceptionOrNull())
        awaitEvent("canceled /slow")
        // A sibling request on the same client is unaffected.
        assertEquals("fast", api.fast().body()!!.string())
        release.countDown()
    }

    @Test
    fun `cancelling a call still queued in the OkHttp dispatcher never sends it`() = runBlocking {
        val client = client { dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1 }) }
        val api = api(client)
        val first = async(Dispatchers.IO) { api.slow() }
        assertTrue(serverSawSlow.await(5, TimeUnit.SECONDS))
        val queued = async(Dispatchers.IO) { api.fast() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (client.dispatcher.queuedCallsCount() != 1) {
            check(System.nanoTime() < deadline) { "second call never queued" }
            Thread.sleep(5)
        }

        val cause = Revoked()
        queued.cancel(cause)
        withTimeout(1_000) { queued.join() }
        assertSame(cause, queued.getCompletionExceptionOrNull())
        awaitEvent("canceled /fast")

        release.countDown()
        first.await()
        awaitEvent("failed /fast")
        assertEquals("the cancelled queued call never reached the server", 1, server.requestCount)
        // Recorded, not asserted as desirable: OkHttp still runs application interceptors for a
        // cancelled queued call when it is promoted. The executor's gate interceptor therefore
        // checks call.isCanceled() before any header is built.
        println("prototype: application interceptor paths = $interceptorPaths")
    }

    @Test
    fun `cancelling a call while its socket is connecting closes that socket`() = runBlocking {
        val connecting = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val factory = object : SocketFactory() {
            private fun blocking(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    connecting.countDown()
                    if (closed.await(10, TimeUnit.SECONDS)) throw SocketException("closed while connecting")
                    super.connect(endpoint, timeout)
                }

                override fun close() {
                    closed.countDown()
                    super.close()
                }
            }

            override fun createSocket(): Socket = blocking()
            override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
                throw UnsupportedOperationException()
            override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
                throw UnsupportedOperationException()
        }
        val api = api(client { socketFactory(factory) })
        val call = async(Dispatchers.IO) { api.fast() }
        assertTrue("the socket started connecting", connecting.await(5, TimeUnit.SECONDS))

        val cause = Revoked()
        call.cancel(cause)
        withTimeout(1_000) { call.join() }

        assertSame(cause, call.getCompletionExceptionOrNull())
        assertTrue("OkHttp closed the connecting socket", closed.await(5, TimeUnit.SECONDS))
        awaitEvent("failed /fast")
        assertEquals("nothing reached the server", 0, server.requestCount)
    }

    @Test
    fun `the call factory runs on the calling coroutine so a thread context element reaches it`() = runBlocking {
        val tag = ThreadLocal<String?>()
        val seen = AtomicReference<String?>("unset")
        val client = client()
        val api = api(Call.Factory { request -> seen.set(tag.get()); client.newCall(request) })

        withContext(Dispatchers.IO + tag.asContextElement("token-1")) { api.fast() }
        assertEquals("token-1", seen.get())

        withContext(Dispatchers.IO) { api.fast() }
        assertNull(seen.get())
    }
}
