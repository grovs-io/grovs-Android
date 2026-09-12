package io.grovs.service

import io.grovs.e2e.E2ETestUtils
import io.grovs.handlers.ConsentController
import io.grovs.handlers.ConsentRevokedException
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.RevocationReason
import io.grovs.handlers.runOperation
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.utils.GVRetryResult
import io.grovs.utils.LSResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

private const val MANY_RETRY_INTERVALS = 60_000L
private val FLOW_CASES = setOf("getDeviceFor", "authenticate")

/** A real service over its own backend and context. Closing retires the configuration and checks routing. */
private class ServiceFixture : Closeable {
    val backend = ConsentBackend().apply { phase = "enabled" }
    val context = GrovsContext().also { it.settings.baseURL = backend.baseUrl }
    private var gate: GatedExecutor? = null
    private val scopes = mutableListOf<CoroutineScope>()

    init {
        // The retry tests detect "in the retry wait" from the SDK's INFO failure line.
        DebugLogger.instance.logLevel = LogLevel.INFO
    }

    /** Revocation cancellations wait until teardown: only token validation stands in the way. Call before [service]. */
    fun holdCleanup(): GatedExecutor =
        GatedExecutor().also { gate = it; context.useConsentController(ConsentController(cleanupExecutor = it)) }

    fun service() = GrovsService(context = RuntimeEnvironment.getApplication(), apiKey = "test-key", grovsContext = context)

    fun scope(dispatcher: CoroutineContext) = CoroutineScope(dispatcher + SupervisorJob()).also { scopes += it }

    override fun close() {
        DebugLogger.instance.logLevel = LogLevel.ERROR
        gate?.open()
        scopes.forEach { it.cancel() }
        val cleanup = E2ETestUtils.retireConsentConfiguration(context)
        backend.close()
        assertNull(cleanup)
        assertEquals("requests to unrouted paths", emptyList<String>(), backend.unexpected.toList())
    }
}

/**
 * N01–N05 over every row of the endpoint inventory (both batch body types included), against the
 * REAL GrovsService, its real consent request executor and real Retrofit/OkHttp over MockWebServer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28])
class GrovsServiceConsentTest(private val case: EndpointCase) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = EndpointInventory.all.map { arrayOf<Any>(it) }
    }

    private lateinit var fixture: ServiceFixture
    private val backend get() = fixture.backend
    private val context get() = fixture.context

    @Before
    fun setUp() {
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent executor tests")
        fixture = ServiceFixture()
        backend.respond(case.path, case.successBody)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `N01 initially disabled - no transport and a terminal rejection, not a retry`() = runTest {
        context.settings.sdkEnabled = false
        val service = fixture.service()
        val emissions = CopyOnWriteArrayList<Any?>()

        val outcome = runCatching { case.invoke(service, emissions) }

        assertRevoked(RevocationReason.NOT_ADMITTED, outcome)
        assertEquals("no request may leave while disabled", 0, backend.seen.size)
        assertEquals("rejected at once, not after a retry delay", 0L, testScheduler.currentTime)
        assertEquals("a rejected flow emits nothing, not Retrying", emptyList<Any?>(), emissions.toList())
        assertFalse("no device details are built for a rejected call", service.builtDeviceDetails())

        // Positive control on the same fixture and service: once consent is granted the call goes out.
        context.settings.sdkEnabled = true
        case.checkSuccess(case.invoke(service))
        assertEquals(1, backend.count(case.path))
    }

    @Test
    fun `N02 enabled - sends the expected request and returns the parsed result`() = runTest {
        val service = fixture.service()

        val result = case.invoke(service)

        case.checkSuccess(result)
        val request = backend.seen.single()
        assertEquals(case.method, request.method)
        assertEquals(case.path, request.path)
        case.checkRequest(request.request, request.body)
        assertEquals("headers are built for an admitted request", "test-key", request.request.getHeader("PROJECT-KEY"))
        assertEquals("the attempt unregistered when it finished", 0, context.consent.registrationCount())
    }

    @Test
    fun `N03 revoked between admission and transport - the transport never starts under the old token`() {
        val service = fixture.service()
        val stepper = SteppingDispatcher()
        val outcome = AtomicReference<Result<Any?>?>()
        fixture.scope(stepper).launch { outcome.set(runCatching { case.invoke(service) }) }

        stepper.stepUntil("the call to be admitted and registered") { context.consent.registrationCount() >= 1 }
        assertEquals("admitted but not yet sent", 0, backend.seen.size)
        context.settings.sdkEnabled = false
        stepper.stepUntil("the revoked call to end") { outcome.get() != null }

        assertRevoked(RevocationReason.REVOKED, outcome.get())
        backend.assertQuiet(case.path, expected = 0)
        assertFalse("no headers were built for the revoked attempt", service.builtDeviceDetails())

        // Positive control on the same fixture: after a new grant the same call goes out.
        context.settings.sdkEnabled = true
        val again = AtomicReference<Result<Any?>?>()
        fixture.scope(stepper).launch { again.set(runCatching { case.invoke(service) }) }
        stepper.stepUntil("the positive control") { again.get() != null }
        case.checkSuccess(again.get()!!.getOrThrow())
        assertEquals(1, backend.count(case.path))
    }

    @Test
    fun `N04 revoked during a suspended response - a late success after re-enable is never published`() = runBlocking {
        val cleanup = fixture.holdCleanup()
        val release = backend.hold(case.path, case.successBody)
        val service = fixture.service()
        val call = fixture.scope(Dispatchers.IO).async { runCatching { case.invoke(service) } }
        backend.awaitCount(case.path, 1)
        val admitted = context.consent.generation

        context.settings.sdkEnabled = false
        context.settings.sdkEnabled = true
        backend.phase = "re-enabled"
        release.countDown()
        val outcome = withTimeout(5_000) { call.await() }

        val revoked = assertRevoked(RevocationReason.REVOKED, outcome)
        assertEquals("rejected under the generation that admitted it", admitted, revoked.token?.generation)
        assertTrue("cancellation was held, so only the generation check stood in the way", cleanup.heldCount > 0)
        backend.assertQuiet(case.path, expected = 1)
        assertEquals("no request under a fresh token", 0, backend.count(case.path, "re-enabled"))
    }

    @Test
    fun `N05 a transport failure then revocation during the retry delay - the attempt count stays fixed`() {
        backend.fail(case.path)
        val driver = DrivenDispatcher()
        val service = fixture.service()
        val emissions = CopyOnWriteArrayList<Any?>()
        val outcome = AtomicReference<Result<Any?>?>()
        fixture.scope(driver).launch { outcome.set(runCatching { case.invoke(service, emissions) }) }
        driver.runCurrent()
        backend.awaitCount(case.path, 1)

        if (!case.retrying) {
            // Single attempt per call: the transport failure is the answer. The callers' delivery
            // triggers own retrying, so no retry wait may be scheduled here.
            driver.runUntil("the single attempt to fail") { outcome.get() != null }
            val result = outcome.get()!!.getOrThrow()
            assertTrue("a transport failure is an Error, got $result", result is LSResult.Error)
            val attempts = backend.count(case.path)
            driver.advanceTimeBy(MANY_RETRY_INTERVALS)
            backend.assertQuiet(case.path, attempts)
            assertEquals(0, context.consent.registrationCount())
            return
        }

        driver.runUntil("the failure to put the call in its retry wait") { loggedCount(case.failureLog) >= 1 }
        assertNull("still retrying", outcome.get())
        if (case.name in FLOW_CASES) assertTrue("Retrying emitted: $emissions", emissions.last() is GVRetryResult.Retrying)
        assertEquals("the retry wait is a registered operation", 1, context.consent.registrationCount())
        val attempts = backend.count(case.path)

        context.settings.sdkEnabled = false
        driver.runUntil("revocation to end the retry wait") { outcome.get() != null }
        assertEquals("revocation cancelled the wait itself; no virtual time passed", 0L, driver.currentTime)
        assertRevoked(RevocationReason.REVOKED, outcome.get())

        driver.advanceTimeBy(MANY_RETRY_INTERVALS)
        backend.assertQuiet(case.path, attempts)
    }
}

/** N05 (held cancellation), N06–N09 and the non-consent cancellation ruling, against the REAL GrovsService. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GrovsServiceConsentLifecycleTest {

    private val fixtures = mutableListOf<ServiceFixture>()

    private fun fixture() = ServiceFixture().also { fixtures += it }

    private fun named(name: String) = EndpointInventory.named(name)

    @Before
    fun setUp() {
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent executor tests")
    }

    @After
    fun tearDown() {
        fixtures.forEach { it.close() }
    }

    @Test
    fun `N05 a retry wait whose cancellation is held cannot resume under the next grant`() {
        for (case in EndpointInventory.retrying) {
            val f = fixture()
            val cleanup = f.holdCleanup()
            f.backend.fail(case.path)
            val driver = DrivenDispatcher()
            val service = f.service()
            val outcome = AtomicReference<Result<Any?>?>()
            val logged = loggedCount(case.failureLog)
            f.scope(driver).launch { outcome.set(runCatching { case.invoke(service) }) }
            driver.runCurrent()
            f.backend.awaitCount(case.path, 1)
            driver.runUntil("${case.name}: the failure to start its retry wait") { loggedCount(case.failureLog) > logged }
            val attempts = f.backend.count(case.path)

            f.context.settings.sdkEnabled = false
            f.context.settings.sdkEnabled = true
            f.backend.phase = "re-enabled"
            f.backend.respond(case.path, case.successBody)
            assertTrue("${case.name}: the wait's cancellation is held", cleanup.heldCount > 0)
            assertNull("${case.name}: the held wait is still pending", outcome.get())

            driver.advanceTimeBy(MANY_RETRY_INTERVALS)
            driver.runUntil("${case.name}: the stale wait to end") { outcome.get() != null }

            assertRevoked(RevocationReason.REVOKED, outcome.get())
            f.backend.assertQuiet(case.path, attempts)
            assertEquals("${case.name}: no retry under the new grant", 0, f.backend.count(case.path, "re-enabled"))
        }
    }

    @Test
    fun `N06 caller cancellation propagates as ordinary cancellation and ends the retries`() {
        for (case in listOf(named("payloadFor"), named("authenticate"))) {
            val f = fixture()
            val release = f.backend.hold(case.path, case.successBody)
            val driver = DrivenDispatcher()
            val service = f.service()
            val outcome = AtomicReference<Result<Any?>?>()
            val caller = f.scope(driver).launch { outcome.set(runCatching { case.invoke(service) }) }
            driver.runCurrent()
            f.backend.awaitCount(case.path, 1)

            caller.cancel()
            driver.runUntil("${case.name}: the caller's cancellation to finish") { caller.isCompleted }

            val thrown = outcome.get()?.exceptionOrNull()
            assertTrue("${case.name}: ordinary cancellation, got $thrown", thrown is kotlinx.coroutines.CancellationException)
            assertFalse("${case.name}: never reported as a consent rejection", thrown is ConsentRevokedException)
            assertTrue("consent is untouched", f.context.settings.sdkEnabled)
            assertEquals("the attempt unregistered", 0, f.context.consent.registrationCount())
            driver.advanceTimeBy(MANY_RETRY_INTERVALS)
            release.countDown()
            f.backend.assertQuiet(case.path, 1)
        }
    }

    @Test
    fun `N06 consent cancellation is terminal even after consent is granted again`() = runBlocking {
        for (case in listOf(named("payloadFor"), named("getDeviceFor"))) {
            val f = fixture()
            val release = f.backend.hold(case.path, case.successBody)
            val service = f.service()
            val call = f.scope(Dispatchers.IO).async { runCatching { case.invoke(service) } }
            f.backend.awaitCount(case.path, 1)

            f.context.settings.sdkEnabled = false
            // Real cancellation: the call ends before its response is released.
            assertRevoked(RevocationReason.REVOKED, withTimeout(5_000) { call.await() })
            f.context.settings.sdkEnabled = true
            f.backend.phase = "re-enabled"
            release.countDown()
            f.backend.assertQuiet(case.path, 1)
        }
    }

    @Test
    fun `N06 enabled transport failures retry on the bounded schedule`() {
        for (case in listOf(named("payloadFor"), named("getDeviceFor"))) {
            val f = fixture()
            // Three failures then a success: the last attempt inside the budget succeeds.
            val failures = GrovsService.MAX_ATTEMPTS.toInt() - 1
            f.backend.failThenRespond(case.path, failures, case.successBody)
            val driver = DrivenDispatcher()
            ConsentRequestExecutor.retryJitterMs = { 0 }
            val service = f.service()
            val emissions = CopyOnWriteArrayList<Any?>()
            val outcome = AtomicReference<Result<Any?>?>()
            val logged = loggedCount(case.failureLog)
            f.scope(driver).launch { outcome.set(runCatching { case.invoke(service, emissions) }) }
            driver.runCurrent()

            val sentAt = mutableListOf<Long>()
            for (attempt in 0..failures) {
                f.backend.awaitCount(case.path, attempt + 1)
                sentAt += driver.currentTime
                if (attempt == failures) break
                driver.runUntil("${case.name}: failure of attempt $attempt") {
                    loggedCount(case.failureLog) > logged + attempt
                }
                val wait = GrovsService.RETRY_BASE_DELAY_MS shl attempt
                driver.advanceTimeBy(wait - 1)
                f.backend.assertQuiet(case.path, attempt + 1, windowMs = 20)
                driver.advanceTimeBy(1)
            }
            driver.runUntil("${case.name}: the success after the failures") { outcome.get() != null }

            case.checkSuccess(outcome.get()!!.getOrThrow())
            val expected = (0 until failures).runningFold(0L) { at, attempt ->
                at + (GrovsService.RETRY_BASE_DELAY_MS shl attempt)
            }
            assertEquals("${case.name}: attempts sent on the bounded schedule", expected, sentAt)
            if (case.name in FLOW_CASES) {
                assertEquals((0 until failures).toList(), emissions.filterIsInstance<GVRetryResult.Retrying>().map { it.count })
            }
            ConsentRequestExecutor.retryJitterMs = ConsentRequestExecutor.defaultJitterMs
        }
    }

    @Test
    fun `N06b a failure past the budget is a terminal result, not a thrown exception`() {
        for (case in listOf(named("payloadFor"), named("getDeviceFor"))) {
            val f = fixture()
            f.backend.fail(case.path)
            val driver = DrivenDispatcher()
            ConsentRequestExecutor.retryJitterMs = { 0 }
            val service = f.service()
            val emissions = CopyOnWriteArrayList<Any?>()
            val outcome = AtomicReference<Result<Any?>?>()
            f.scope(driver).launch { outcome.set(runCatching { case.invoke(service, emissions) }) }

            driver.runUntil("${case.name}: the call to give up") {
                driver.advanceTimeBy(1_000)
                outcome.get() != null
            }

            assertEquals(
                "${case.name}: stopped at the budget",
                GrovsService.MAX_ATTEMPTS.toInt(),
                f.backend.count(case.path),
            )
            assertTrue(
                "${case.name}: gave up with a result rather than an exception",
                outcome.get()!!.isSuccess,
            )
            ConsentRequestExecutor.retryJitterMs = ConsentRequestExecutor.defaultJitterMs
        }
    }

    @Test
    fun `N06 a non-consent cancellation ending an operation block propagates unchanged`() = runBlocking {
        val f = fixture()
        val consent = f.context.consent
        val token = consent.tryAcquire(consent.currentConfiguration)!!

        val thrown = runCatching { consent.runOperation(token) { withTimeout(20) { awaitCancellation() } } }.exceptionOrNull()

        assertTrue("the timeout propagates as itself, got $thrown", thrown is TimeoutCancellationException)
        assertFalse(thrown is ConsentRevokedException)
        assertTrue("the token is untouched", consent.isCurrent(token))
        assertTrue("the caller keeps running", coroutineContext.isActive)

        // The same through the executor: the caller's own timeout around a held request.
        val case = named("payloadFor")
        f.backend.hold(case.path, case.successBody)
        val service = f.service()
        val viaService = runCatching { withTimeout(300) { case.invoke(service) } }.exceptionOrNull()
        assertTrue("got $viaService", viaService is TimeoutCancellationException)
        assertFalse(viaService is ConsentRevokedException)
        assertTrue(f.context.settings.sdkEnabled)
        assertEquals(1, f.backend.count(case.path))
    }

    @Test
    fun `N07 both flows acquire consent at collection, not at construction`() = runBlocking {
        for (case in listOf(named("getDeviceFor"), named("authenticate"))) {
            val f = fixture()
            f.backend.respond(case.path, case.successBody)
            val service = f.service()
            val build: () -> Flow<GVRetryResult<Any>> = {
                if (case.name == "getDeviceFor") service.getDeviceFor("vendor-1") else service.authenticate(EndpointInventory.appDetails)
            }
            val builtWhileEnabled = build()

            f.context.settings.sdkEnabled = false
            assertRevoked(RevocationReason.NOT_ADMITTED, runCatching { builtWhileEnabled.first() })
            assertEquals("${case.name}: collecting while disabled makes no request", 0, f.backend.count(case.path))

            val builtWhileDisabled = build()
            f.context.settings.sdkEnabled = true
            case.checkSuccess(builtWhileDisabled.first())
            case.checkSuccess(builtWhileEnabled.first())
            assertEquals(2, f.backend.count(case.path))

            val consent = f.context.consent
            val parent = consent.tryAcquire(consent.currentConfiguration)!!
            f.context.settings.sdkEnabled = false
            f.context.settings.sdkEnabled = true
            assertRevoked(RevocationReason.REVOKED, runCatching { withContext(parent) { build().first() } })
            assertEquals("${case.name}: a revoked parent's flow cannot revive", 2, f.backend.count(case.path))
        }
    }

    @Test
    fun `N08 services under one configuration all stop on disable, a bystander survives, a retired one stays retired`() = runBlocking {
        val f = fixture()
        val cases = listOf(named("payloadFor"), named("numberOfUnreadNotifications"), named("getDeviceFor"))
        val releases = cases.map { f.backend.hold(it.path, it.successBody) }
        val services = cases.map { f.service() }
        val callers = f.scope(Dispatchers.IO)
        val calls = cases.zip(services).map { (case, service) -> callers.async { runCatching { case.invoke(service) } } }
        cases.forEach { f.backend.awaitCount(it.path, 1) }
        val bystanderGate = CompletableDeferred<String>()
        val bystander = callers.async { bystanderGate.await() }

        f.context.settings.sdkEnabled = false

        calls.forEach { assertRevoked(RevocationReason.REVOKED, withTimeout(5_000) { it.await() }) }
        assertTrue("an unrelated coroutine in the callers' scope survives", bystander.isActive)
        assertTrue("the callers' scope is not cancelled", callers.coroutineContext.job.isActive)
        bystanderGate.complete("survived")
        assertEquals("survived", withTimeout(1_000) { bystander.await() })

        f.context.consent.retireConfiguration(enabled = true)
        releases.forEach { it.countDown() }
        f.backend.phase = "new configuration"
        cases.forEach { f.backend.respond(it.path, it.successBody) }
        cases.zip(services).forEach { (case, service) ->
            assertRevoked(RevocationReason.CONFIGURATION_RETIRED, runCatching { case.invoke(service) })
        }
        cases.forEach { assertEquals("${it.name}: a retired service sends nothing", 0, f.backend.count(it.path, "new configuration")) }

        // Positive control: a service built for the new configuration works.
        val fresh = f.service()
        cases.forEach { it.checkSuccess(it.invoke(fresh)) }
        cases.forEach { assertEquals(1, f.backend.count(it.path, "new configuration")) }
    }

    @Test
    fun `N09 a revoked operation builds no fresh headers or device details`() = runBlocking {
        val f = fixture()
        val case = named("payloadFor")
        f.backend.respond(case.path, case.successBody)
        val service = f.service()
        val consent = f.context.consent
        val parent = consent.tryAcquire(consent.currentConfiguration)!!
        f.context.settings.sdkEnabled = false
        f.context.settings.sdkEnabled = true

        assertRevoked(RevocationReason.REVOKED, runCatching { withContext(parent) { case.invoke(service) } })
        assertFalse("no user agent or app details were built", service.builtDeviceDetails())
        assertEquals(0, f.backend.seen.size)

        // Positive control: under current consent the same service builds them and sends.
        case.checkSuccess(case.invoke(service))
        assertTrue(service.builtDeviceDetails())
        assertEquals("test-key", f.backend.seen.single().request.getHeader("PROJECT-KEY"))
    }

    @Test
    fun `N09 a request queued inside OkHttp when consent is revoked is refused before it is sent`() = runBlocking {
        val f = fixture()
        f.holdCleanup()
        val busy = named("notifications")
        val queued = named("numberOfUnreadNotifications")
        val release = f.backend.hold(busy.path, busy.successBody)
        f.backend.respond(queued.path, queued.successBody)
        val service = f.service()
        // OkHttp runs at most five calls per host; these five occupy all of them.
        val busyCalls = List(5) { f.scope(Dispatchers.IO).async { runCatching { busy.invoke(service) } } }
        f.backend.awaitCount(busy.path, 5)
        assertTrue("positive control: admitted requests carry headers",
            f.backend.seen.all { it.request.getHeader("PROJECT-KEY") == "test-key" })

        val stepper = SteppingDispatcher()
        val outcome = AtomicReference<Result<Any?>?>()
        f.scope(stepper).launch { outcome.set(runCatching { queued.invoke(service) }) }
        stepper.drainNow()
        assertEquals("five busy attempts and the queued one are registered", 6, f.context.consent.registrationCount())

        f.context.settings.sdkEnabled = false
        release.countDown()
        stepper.stepUntil("the queued call to end") { outcome.get() != null }

        assertRevoked(RevocationReason.REVOKED, outcome.get())
        f.backend.assertQuiet(queued.path, 0)
        busyCalls.forEach { assertRevoked(RevocationReason.REVOKED, withTimeout(5_000) { it.await() }) }
    }

    @Test
    fun `N09 a network callback queued behind revocation cannot publish its result`() {
        val f = fixture()
        f.holdCleanup()
        val case = named("notificationsToDisplayAutomatically")
        f.backend.respond(case.path, case.successBody)
        val service = f.service()
        val stepper = SteppingDispatcher()

        // Positive control on the same fixture.
        val first = AtomicReference<Result<Any?>?>()
        f.scope(stepper).launch { first.set(runCatching { case.invoke(service) }) }
        stepper.stepUntil("the positive control") { first.get() != null }
        case.checkSuccess(first.get()!!.getOrThrow())

        val outcome = AtomicReference<Result<Any?>?>()
        f.scope(stepper).launch { outcome.set(runCatching { case.invoke(service) }) }
        stepper.drainNow()
        f.backend.awaitCount(case.path, 2)
        stepper.awaitQueued("the response callback to be queued on the SDK dispatcher")
        f.context.settings.sdkEnabled = false
        stepper.stepUntil("the queued callback to run") { outcome.get() != null }

        assertRevoked(RevocationReason.REVOKED, outcome.get())
        f.backend.assertQuiet(case.path, 2)
    }
}
