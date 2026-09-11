package io.grovs.service

import io.grovs.handlers.ConsentController
import io.grovs.handlers.ConsentRevokedException
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.RevocationReason
import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.CustomEvent
import io.grovs.model.DeeplinkDetails
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.events.PaymentEvent
import io.grovs.model.events.PaymentEventType
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.model.notifications.NumberOfUnreadNotificationsResponse
import io.grovs.settings.GrovsSettings
import io.grovs.utils.GVRetryResult
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.robolectric.shadows.ShadowLog
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/*
 * Fixture for the consent request executor tests: the REAL GrovsService over a MockWebServer that
 * routes exact paths, records phase-labelled requests, and can hold, fail or answer each route.
 */

const val SDK_PATH = "/api/v1/sdk/"

/** One row of the endpoint inventory. [invoke] returns the terminal result of one call. */
class EndpointCase(
    val name: String,
    val method: String,
    val path: String,
    val successBody: String,
    val retrying: Boolean,
    private val call: suspend (GrovsService, MutableList<Any?>) -> Any?,
    val checkRequest: (RecordedRequest, String) -> Unit,
    val checkSuccess: (Any?) -> Unit,
) {
    /** Flow cases record every emission (Retrying included) into [emissions]. */
    suspend fun invoke(service: GrovsService, emissions: MutableList<Any?> = CopyOnWriteArrayList()): Any? =
        call(service, emissions)

    /**
     * For a retrying method: the SDK's INFO line for a failed attempt. It is written synchronously
     * right before the retry wait starts, so once it appears the call is in its wait.
     */
    val failureLog: String get() = EndpointInventory.retryFailureLog.getValue(name)

    override fun toString() = name
}

/** How many times the SDK has logged a line containing [text]. INFO logging must be on. */
fun loggedCount(text: String): Int = ShadowLog.getLogs().count { text in it.msg }

object EndpointInventory {
    const val LINK_A = "https://demo.sqd.link/consent-a"
    const val LINK_B = "https://demo.sqd.link/consent-b"
    private const val NOTIFICATIONS = """{"notifications":[{"id":7,"title":"Hello","updated_at":"2024-01-01T10:00:00.000Z",
        "subtitle":null,"auto_display":true,"access_url":null,"read":false}]}"""

    val appDetails = AppDetails(
        version = "1.0", build = "1", bundle = "io.grovs.consent", device = "Robolectric",
        deviceID = "vendor-1", userAgent = "Consent UA",
    )

    private fun json(body: String) = JSONObject(body)

    private suspend fun <T : Any> terminal(flow: Flow<GVRetryResult<T>>, emissions: MutableList<Any?>): Any? =
        flow.transformWhile { emit(it); it is GVRetryResult.Retrying }.onEach { emissions += it }.lastOrNull()

    private fun <T> success(result: Any?): T {
        return when (result) {
            is LSResult.Success<*> -> @Suppress("UNCHECKED_CAST") (result.data as T)
            is GVRetryResult.Success<*> -> @Suppress("UNCHECKED_CAST") (result.data as T)
            else -> throw AssertionError("expected a success, got $result")
        }
    }

    val all: List<EndpointCase> = listOf(
        EndpointCase(
            "getDeviceFor", "GET", "device_for_vendor_id", """{"last_seen":null}""", retrying = true,
            call = { s, e -> terminal(s.getDeviceFor("vendor-1"), e) },
            checkRequest = { r, _ -> assertEquals("vendor-1", r.requestUrl!!.queryParameter("vendor_id")) },
            checkSuccess = { assertTrue("device lookup succeeded: $it", it is GVRetryResult.Success<*>) },
        ),
        EndpointCase(
            "authenticate", "POST", "authenticate", """{"linksquared":"gid-1","uri_scheme":"testapp"}""", retrying = true,
            call = { s, e -> terminal(s.authenticate(appDetails), e) },
            checkRequest = { _, b -> assertEquals("vendor-1", json(b).getString("vendor_id")) },
            checkSuccess = { assertEquals("gid-1", success<AuthenticationResponse>(it).grovsId) },
        ),
        EndpointCase(
            "payloadFor", "POST", "data_for_device", """{"link":"$LINK_A","data":{"k":"v"}}""", retrying = true,
            call = { s, _ -> s.payloadFor(appDetails) },
            checkRequest = { _, b -> assertEquals("vendor-1", json(b).getString("vendor_id")) },
            checkSuccess = { assertEquals(LINK_A, success<DeeplinkDetails>(it).link) },
        ),
        EndpointCase(
            "payloadWithLinkFor", "POST", "data_for_device_and_url", """{"link":"$LINK_B","data":null}""", retrying = true,
            call = { s, _ -> s.payloadWithLinkFor(appDetails.copy(url = LINK_B)) },
            checkRequest = { _, b -> assertEquals(LINK_B, json(b).getString("url")) },
            checkSuccess = { assertEquals(LINK_B, success<DeeplinkDetails>(it).link) },
        ),
        EndpointCase(
            "clipboardStatus", "POST", "clipboard_status", """{"clipboard_active":true}""", retrying = false,
            call = { s, _ -> s.clipboardStatus() },
            checkRequest = { _, _ -> },
            checkSuccess = { assertEquals(true, success<Boolean>(it)) },
        ),
        EndpointCase(
            "generateLink", "POST", "create_link", """{"link":"https://demo.sqd.link/new"}""", retrying = false,
            call = { s, _ ->
                s.generateLink(
                    title = "Consent title", subtitle = null, imageURL = null, data = null, tags = null,
                    customRedirects = null, showPreviewIos = null, showPreviewAndroid = null,
                    copyToClipboardIos = null, copyToClipboardAndroid = null, tracking = null,
                )
            },
            checkRequest = { _, b -> assertEquals("Consent title", json(b).getString("title")) },
            checkSuccess = { assertEquals("https://demo.sqd.link/new", success<GenerateLinkResponse>(it).link) },
        ),
        EndpointCase(
            "linkDetails", "POST", "link_details", """{"title":"Link title"}""", retrying = false,
            call = { s, _ -> s.linkDetails("/consent-path") },
            checkRequest = { _, b -> assertEquals("/consent-path", json(b).getString("path")) },
            checkSuccess = { assertEquals("Link title", success<LinkDetailsResponse>(it).link["title"]) },
        ),
        EndpointCase(
            "updateAttributes", "POST", "visitor_attributes", "{}", retrying = true,
            call = { s, _ -> s.updateAttributes("user-1", mapOf("plan" to "pro"), "push-1") },
            checkRequest = { _, b ->
                assertEquals("user-1", json(b).getString("sdk_identifier"))
                assertEquals("push-1", json(b).getString("push_token"))
            },
            checkSuccess = { assertEquals(true, success<Boolean>(it)) },
        ),
        EndpointCase(
            "addEvents", "POST", "events/batch", """{"accepted":1,"rejected":0,"errors":[]}""", retrying = false,
            call = { s, _ -> s.addEvents(listOf(Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now(), sessionId = "s1"))) },
            checkRequest = { _, b -> assertEquals("app_open", json(b).getJSONArray("events").getJSONObject(0).getString("event")) },
            checkSuccess = { assertEquals(1, success<io.grovs.model.BatchEventsResponse>(it).accepted) },
        ),
        EndpointCase(
            "addCustomEvents", "POST", "events/batch", """{"accepted":1,"rejected":0,"errors":[]}""", retrying = false,
            call = { s, _ -> s.addCustomEvents(listOf(CustomEvent(eventName = "checkout", createdAt = InstantCompat.now(), sessionId = "s1"))) },
            checkRequest = { _, b -> assertEquals("checkout", json(b).getJSONArray("events").getJSONObject(0).getString("event_name")) },
            checkSuccess = { assertEquals(1, success<io.grovs.model.BatchEventsResponse>(it).accepted) },
        ),
        EndpointCase(
            "addPaymentEvent", "POST", "add_payment_event", "{}", retrying = false,
            call = { s, _ ->
                s.addPaymentEvent(PaymentEvent(eventType = PaymentEventType.BUY, priceCents = 1999, currency = "USD", productId = "sku-1"))
            },
            checkRequest = { _, b -> assertEquals("sku-1", json(b).getString("product_id")) },
            checkSuccess = { assertEquals(true, success<Boolean>(it)) },
        ),
        EndpointCase(
            "notifications", "POST", "notifications_for_device", NOTIFICATIONS, retrying = true,
            call = { s, _ -> s.notifications(page = 2) },
            checkRequest = { _, b -> assertEquals(2, json(b).getInt("page")) },
            checkSuccess = { assertEquals(7, success<NotificationsResponse>(it).notifications!!.single().id) },
        ),
        EndpointCase(
            "numberOfUnreadNotifications", "GET", "number_of_unread_notifications",
            """{"number_of_unread_notifications":3}""", retrying = true,
            call = { s, _ -> s.numberOfUnreadNotifications() },
            checkRequest = { _, _ -> },
            checkSuccess = { assertEquals(3, success<NumberOfUnreadNotificationsResponse>(it).numberOfUnreadNotifications) },
        ),
        EndpointCase(
            "markNotificationAsRead", "POST", "mark_notification_as_read", "{}", retrying = true,
            call = { s, _ -> s.markNotificationAsRead(notificationId = 7) },
            checkRequest = { _, b -> assertEquals(7, json(b).getInt("id")) },
            checkSuccess = { assertEquals(true, success<Boolean>(it)) },
        ),
        EndpointCase(
            "notificationsToDisplayAutomatically", "GET", "notifications_to_display_automatically", NOTIFICATIONS, retrying = true,
            call = { s, _ -> s.notificationsToDisplayAutomatically() },
            checkRequest = { _, _ -> },
            checkSuccess = { assertEquals(7, success<NotificationsResponse>(it).notifications!!.single().id) },
        ),
        EndpointCase(
            "syncScreenAliases", "POST", "screen_aliases", "{}", retrying = false,
            call = { s, _ -> s.syncScreenAliases(mapOf("io.grovs.Home" to "Home")) },
            checkRequest = { _, b ->
                assertEquals("Home", json(b).getJSONArray("screen_aliases").getJSONObject(0).getString("alias"))
            },
            checkSuccess = { assertEquals(true, success<Boolean>(it)) },
        ),
    )

    val retryFailureLog = mapOf(
        "getDeviceFor" to "Getting device last seen - Failed",
        "authenticate" to "Authenticate - Failed",
        "payloadFor" to "Fetching payload - Failed",
        "payloadWithLinkFor" to "Fetching payload - Failed",
        "updateAttributes" to "Set attributes - Failed",
        "notifications" to "Getting all the notifications - Failed",
        "numberOfUnreadNotifications" to "Get unread messages - Failed",
        "markNotificationAsRead" to "Mark notification as read - Failed",
        "notificationsToDisplayAutomatically" to "Notifications to display automatically - Failed",
    )

    val retrying: List<EndpointCase> get() = all.filter { it.retrying }

    fun named(name: String): EndpointCase = all.single { it.name == name }
}

/**
 * MockWebServer with exact path routing. An unknown route answers 404 and is recorded in
 * [unexpected]; every request is recorded with the [phase] current when it arrived.
 */
class ConsentBackend : Closeable {
    class Seen(val phase: String, val path: String, val method: String, val body: String, val request: RecordedRequest)

    private sealed class Mode {
        class Respond(val body: String) : Mode()
        object Disconnect : Mode()
        class FailTimes(val remaining: AtomicInteger, val body: String) : Mode()
        class Hold(val gate: CountDownLatch, val body: String) : Mode()
    }

    val server = MockWebServer()
    val seen = CopyOnWriteArrayList<Seen>()
    val unexpected = CopyOnWriteArrayList<String>()

    @Volatile
    var phase: String = "setup"

    private val routes = ConcurrentHashMap<String, Mode>()
    private val gates = CopyOnWriteArrayList<CountDownLatch>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.removePrefix(SDK_PATH)
                seen += Seen(phase, path, request.method!!, request.body.clone().readUtf8(), request)
                return when (val mode = routes[path]) {
                    null -> {
                        unexpected += path
                        MockResponse().setResponseCode(404).setBody("""{"error":"unexpected route $path"}""")
                    }
                    is Mode.Respond -> ok(mode.body)
                    Mode.Disconnect -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    is Mode.FailTimes -> if (mode.remaining.getAndDecrement() > 0) {
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    } else {
                        ok(mode.body)
                    }
                    is Mode.Hold -> {
                        mode.gate.await(30, TimeUnit.SECONDS)
                        ok(mode.body)
                    }
                }
            }
        }
        server.start()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    val baseUrl: String get() = server.url("/").toString()

    fun respond(path: String, body: String) { routes[path] = Mode.Respond(body) }

    /** A genuine transport failure: the whole request is read, then the connection is dropped. */
    fun fail(path: String) { routes[path] = Mode.Disconnect }

    fun failThenRespond(path: String, failures: Int, body: String) {
        routes[path] = Mode.FailTimes(AtomicInteger(failures), body)
    }

    /** Holds every request on [path] until the returned gate opens (also opened by [close]). */
    fun hold(path: String, body: String): CountDownLatch =
        CountDownLatch(1).also { gates += it; routes[path] = Mode.Hold(it, body) }

    fun count(path: String): Int = seen.count { it.path == path }

    fun count(path: String, phase: String): Int = seen.count { it.path == path && it.phase == phase }

    fun awaitCount(path: String, count: Int, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (count(path) < count) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for request #$count to $path; saw ${seen.map { "${it.phase}:${it.path}" }}")
            }
            Thread.sleep(2)
        }
    }

    /** A bounded observation window: fails as soon as a request to [path] beyond [expected] arrives. */
    fun assertQuiet(path: String, expected: Int, windowMs: Long = 300) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs)
        while (System.nanoTime() < until) {
            assertEquals("unexpected extra request to $path", expected, count(path))
            Thread.sleep(5)
        }
        assertEquals("unexpected extra request to $path", expected, count(path))
    }

    override fun close() {
        gates.forEach { it.countDown() }
        server.shutdown()
    }
}

/** A cleanup executor that holds revocation cancellations until [open]; they then run inline. */
class GatedExecutor : Executor {
    private val held = mutableListOf<Runnable>()
    private var open = false

    override fun execute(command: Runnable) {
        synchronized(this) {
            if (!open) {
                held += command
                return
            }
        }
        command.run()
    }

    val heldCount: Int get() = synchronized(this) { held.size }

    fun open() {
        val tasks = synchronized(this) {
            open = true
            held.toList().also { held.clear() }
        }
        tasks.forEach { it.run() }
    }
}

/** Replaces the context's consent controller (tests only; the real one is created by GrovsSettings). */
internal fun GrovsContext.useConsentController(controller: ConsentController) {
    GrovsSettings::class.java.getDeclaredField("consent").apply { isAccessible = true }.set(settings, controller)
}

/** Whether this service has built its request headers' device details (user agent, app details). */
fun GrovsService.builtDeviceDetails(): Boolean = listOf("userAgent\$delegate", "appDetails\$delegate").any {
    (GrovsService::class.java.getDeclaredField(it).apply { isAccessible = true }.get(this) as Lazy<*>).isInitialized()
}

internal fun assertRevoked(reason: RevocationReason, outcome: Result<Any?>?): ConsentRevokedException {
    if (outcome == null) throw AssertionError("the call has not finished")
    val thrown = outcome.exceptionOrNull()
        ?: throw AssertionError("expected a consent rejection ($reason), got the result ${outcome.getOrNull()}")
    if (thrown !is ConsentRevokedException) throw AssertionError("expected ConsentRevokedException($reason), got $thrown", thrown)
    assertEquals("revocation reason", reason, thrown.reason)
    return thrown
}

/**
 * Dispatcher for driving SDK coroutines by hand: a [StandardTestDispatcher] on [scheduler] (so
 * `delay` is virtual) that also counts dispatches, so the test can wait for work arriving from
 * another thread (an OkHttp callback, a revocation's cancellation) before running it.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class DrivenDispatcher(val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()) : CoroutineDispatcher(), Delay {
    private val delegate = StandardTestDispatcher(scheduler)
    private val dispatches = AtomicLong()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches.incrementAndGet()
        delegate.dispatch(context, block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
        (delegate as Delay).scheduleResumeAfterDelay(timeMillis, continuation)

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
        (delegate as Delay).invokeOnTimeout(timeMillis, block, context)

    val currentTime: Long get() = scheduler.currentTime

    /** Runs everything due now, including work dispatched from other threads while it runs. */
    fun runCurrent() {
        do {
            val seen = dispatches.get()
            scheduler.runCurrent()
        } while (dispatches.get() != seen)
    }

    fun advanceTimeBy(ms: Long) {
        scheduler.advanceTimeBy(ms)
        runCurrent()
    }

    /** Runs due work, waiting for work from other threads, until [condition] holds. */
    fun runUntil(description: String, timeoutMs: Long = 5_000, onEachPass: () -> Unit = {}, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            runCurrent()
            onEachPass()
            if (condition()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for $description")
            Thread.sleep(1)
        }
    }
}

/** A dispatcher that only queues: the test runs each dispatched task explicitly. No virtual time. */
class SteppingDispatcher : CoroutineDispatcher() {
    private val queue = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.put(block)
    }

    val pending: Int get() = queue.size

    /** Runs the tasks queued right now, and whatever they queue, without waiting for other threads. */
    fun drainNow() {
        while (true) (queue.poll() ?: return).run()
    }

    /** Runs one task at a time, waiting for tasks from other threads, until [condition] holds. */
    fun stepUntil(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("timed out waiting for $description")
            queue.poll(remaining, TimeUnit.NANOSECONDS)?.run()
        }
    }

    /** Waits, without running anything, until a task arrives from another thread. */
    fun awaitQueued(description: String, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (queue.isEmpty()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $description")
            Thread.sleep(1)
        }
    }
}
