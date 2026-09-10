package io.grovs

import android.app.Application
import android.os.Looper
import io.grovs.e2e.E2ETestUtils
import io.grovs.handlers.ConsentController
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.model.DebugLogger
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.LogLevel
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
import io.grovs.service.GatedExecutor
import io.grovs.service.useConsentController
import io.grovs.utils.LSResult
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * P — the public API under consent (plan §5, pruned matrix §10).
 *
 * Explicit requests must answer exactly once with their existing error shape; collection calls must
 * be rejected at public-call time; desired-configuration setters must keep working while disabled.
 * Assertions inspect callbacks, thrown errors and manager calls, never the enabled flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PublicApiConsentTest {

    private lateinit var application: Application
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var mainScheduler: TestCoroutineScheduler
    private lateinit var grovsContext: GrovsContext
    private lateinit var manager: GrovsManager
    private var gate: GatedExecutor? = null

    private val link = "https://demo.sqd.link/generated"

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        DebugLogger.instance.logLevel = LogLevel.INFO

        scheduler = TestCoroutineScheduler()
        // A separate scheduler for the main thread, so a test can let the SDK finish its work while
        // main-thread delivery stays queued - which is the whole point of P03.
        mainScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(mainScheduler))
        grovsContext = GrovsContext(StandardTestDispatcher(scheduler))
        E2ETestUtils.installGrovsContext(grovsContext)

        manager = mockk(relaxed = true)
        every { manager.authenticationState } returns GrovsManager.AuthenticationState.AUTHENTICATED
        installManager()
    }

    @After
    fun tearDown() {
        gate?.open()
        Dispatchers.resetMain()
        clearAllMocks()
        E2ETestUtils.resetGrovsSingleton()
    }

    /**
     * Publishes [manager] as the singleton's manager and points it at the controller's active
     * configuration, exactly as a real manager captures it at construction.
     */
    private fun installManager() {
        every { manager.configuration } returns grovsContext.consent.currentConfiguration
        grovsContext.settings.sdkEnabled = true
        E2ETestUtils.injectGrovsManager(manager)
    }

    /**
     * Replaces the controller with one whose revocation cleanup is held, so a request parked on a
     * barrier is never cancelled. A test can then prove the token check itself rejects the late
     * result rather than relying on cancellation to have got there first.
     */
    private fun holdCleanup() {
        gate = GatedExecutor().also {
            grovsContext.useConsentController(ConsentController(cleanupExecutor = it))
        }
        installManager()
    }

    /** Runs everything the SDK and the main looper have due now. */
    private fun pump() {
        repeat(6) {
            scheduler.runCurrent()
            mainScheduler.runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Pumps everything except main-thread delivery, so a queued result stays undelivered. */
    private fun pumpSdkOnly() {
        repeat(6) { scheduler.runCurrent() }
    }

    /**
     * Pumps both clocks until [condition] holds, or fails. The listener overloads launch in
     * GlobalScope when no lifecycle owner is supplied, so their work runs on real threads and
     * cannot be driven by virtual time alone.
     */
    private fun waitFor(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            pump()
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $description")
            Thread.sleep(5)
        }
        pump()
    }

    /** Pumps for a bounded window and fails if [condition] ever becomes true. */
    private fun assertNever(description: String, windowMs: Long = 300, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < until) {
            pump()
            if (condition()) throw AssertionError("unexpected: $description")
            Thread.sleep(5)
        }
    }

    private fun disable() {
        grovsContext.settings.sdkEnabled = false
    }

    private fun stubGenerateLink(result: LSResult<GenerateLinkResponse>) {
        coEvery {
            manager.generateLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns result
    }

    // ==================== P01 ====================

    @Test
    fun `P01 a disabled SDK rejects every explicit request once, with that method's own error`() {
        disable()

        val generated = CopyOnWriteArrayList<Pair<String?, Exception?>>()
        Grovs.generateLink(title = "t", tracking = null, listener = { l, e -> generated += l to e })
        val details = CopyOnWriteArrayList<Pair<Map<String, Any>?, Exception?>>()
        Grovs.linkDetails(path = "/p", listener = { d, e -> details += d to e })
        val counts = CopyOnWriteArrayList<Int?>()
        Grovs.numberOfUnreadMessages(onResult = { counts += it })
        waitFor("all three rejections") { generated.isNotEmpty() && details.isNotEmpty() && counts.isNotEmpty() }
        // A second completion would show up in the same collections during this window.
        assertNever("a duplicate completion") { generated.size > 1 || details.size > 1 || counts.size > 1 }

        assertEquals("exactly one link-generation completion", 1, generated.size)
        assertNull(generated.single().first)
        assertEquals(
            GrovsErrorCode.LINK_GENERATION_ERROR,
            (generated.single().second as GrovsException).errorCode,
        )

        assertEquals("exactly one link-details completion", 1, details.size)
        assertNull(details.single().first)
        assertEquals(
            GrovsErrorCode.LINK_DETAILS_ERROR,
            (details.single().second as GrovsException).errorCode,
        )

        assertEquals("exactly one unread-count completion", 1, counts.size)
        assertNull("a rejected unread count is null, not a number", counts.single())

        // Nothing reached the manager: the rejection happened at the public entry point.
        coVerify(exactly = 0) {
            manager.generateLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { manager.linkDetails(any()) }
    }

    @Test
    fun `P01 a disabled suspend generateLink throws its own error instead of returning a link`() {
        disable()
        val thrown = runCatching { runBlocking { Grovs.generateLink(title = "t", tracking = null) } }
            .exceptionOrNull()
        assertEquals(
            GrovsErrorCode.LINK_GENERATION_ERROR,
            (thrown as GrovsException).errorCode,
        )
    }

    @Test
    fun `P01 a disabled suspend linkDetails throws its own error`() {
        disable()
        val thrown = runCatching { runBlocking { Grovs.linkDetails(path = "/p") } }.exceptionOrNull()
        assertEquals(GrovsErrorCode.LINK_DETAILS_ERROR, (thrown as GrovsException).errorCode)
    }

    // ==================== P02 ====================

    @Test
    fun `P02 a request revoked in flight completes exactly once, with a failure and no link`() {
        holdCleanup()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery {
            manager.generateLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            started.complete(Unit)
            release.await()
            LSResult.Success(GenerateLinkResponse(link))
        }

        val completions = CopyOnWriteArrayList<Pair<String?, Exception?>>()
        Grovs.generateLink(title = "t", tracking = null, listener = { l, e -> completions += l to e })
        waitFor("the request to reach the manager") { started.isCompleted }
        assertEquals("no completion while the request is in flight", 0, completions.size)

        disable()
        // The cleanup executor is held, so the worker is not cancelled: the success really does come
        // back, under a token that is no longer current.
        release.complete(Unit)
        waitFor("the revoked request to complete") { completions.isNotEmpty() }
        assertNever("a second completion") { completions.size > 1 }

        assertEquals("exactly one completion for an active caller", 1, completions.size)
        assertNull("a revoked request must never publish its link", completions.single().first)
        assertEquals(
            GrovsErrorCode.LINK_GENERATION_ERROR,
            (completions.single().second as GrovsException).errorCode,
        )
    }

    // ==================== P03 ====================

    @Test
    fun `P03 a success already queued for main dispatch is not published if consent goes first`() {
        holdCleanup()
        val produced = CompletableDeferred<Unit>()
        coEvery {
            manager.generateLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            produced.complete(Unit)
            LSResult.Success(GenerateLinkResponse(link))
        }

        val completions = CopyOnWriteArrayList<Pair<String?, Exception?>>()
        Grovs.generateLink(title = "t", tracking = null, listener = { l, e -> completions += l to e })

        // Drive the SDK, but never the main thread: the link is generated and its delivery is
        // queued for main, with the host not told yet.
        val deadline = System.currentTimeMillis() + 5_000
        while (!produced.isCompleted) {
            pumpSdkOnly()
            if (System.currentTimeMillis() > deadline) throw AssertionError("the manager never produced the link")
            Thread.sleep(5)
        }
        pumpSdkOnly()
        assertEquals("nothing delivered before main runs", 0, completions.size)

        disable()
        waitFor("the queued delivery to run") { completions.isNotEmpty() }

        assertEquals(1, completions.size)
        assertNull("a stale success must not be published", completions.single().first)
        assertEquals(
            GrovsErrorCode.LINK_GENERATION_ERROR,
            (completions.single().second as GrovsException).errorCode,
        )
    }

    // ==================== P04 ====================

    @Test
    fun `P04 a cancelled caller gets no late callback and unrelated host work keeps running`() {
        holdCleanup()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery {
            manager.generateLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            started.complete(Unit)
            release.await()
            LSResult.Success(GenerateLinkResponse(link))
        }

        val hostScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val completions = AtomicInteger()
        val sibling = AtomicInteger()
        val caller = hostScope.launch {
            // Cancellation must stay cancellation: catching it here would turn a cancelled caller
            // into a completed one and hide exactly what this test is about.
            try {
                Grovs.generateLink(title = "t", tracking = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A consent failure is still an answer to an active caller.
            }
            completions.incrementAndGet()
        }
        hostScope.launch { sibling.incrementAndGet() }
        waitFor("the request to reach the manager") { started.isCompleted }

        caller.cancel()
        release.complete(Unit)
        waitFor("the cancelled caller to finish") { caller.isCompleted }
        assertNever("a late callback to a cancelled caller") { completions.get() > 0 }

        assertEquals("a cancelled caller receives nothing", 0, completions.get())
        waitFor("the sibling to run") { sibling.get() == 1 }
        assertEquals("an unrelated host coroutine is untouched", 1, sibling.get())
        assertTrue("the host's own scope survives", hostScope.coroutineContext[Job]!!.isActive)
        hostScope.coroutineContext[Job]!!.cancel()
    }

    // ==================== P05 ====================

    @Test
    fun `P05 collection calls made while disabled are dropped and a later grant does not revive them`() {
        disable()

        Grovs.track(name = "offline_event")
        Grovs.trackScreenView(screenName = "OfflineScreen")
        Grovs.logInAppPurchase(originalJson = "{}")
        // Only now is consent granted - after the calls were made but before their dispatcher ran.
        grovsContext.settings.sdkEnabled = true
        assertNever("any dropped collection call reaching the manager") { false }

        coVerify(exactly = 0) { manager.track(any(), any(), any()) }
        coVerify(exactly = 0) { manager.trackScreenView(any(), any()) }
        coVerify(exactly = 0) { manager.logInAppPurchase(any()) }
    }

    @Test
    fun `P05 the same collection calls do reach the manager while consent is granted`() {
        Grovs.track(name = "online_event")
        Grovs.trackScreenView(screenName = "OnlineScreen")
        pump()
        pump()

        coVerify(exactly = 1) { manager.track("online_event", any(), any()) }
        coVerify(exactly = 1) { manager.trackScreenView("OnlineScreen", any()) }
    }

    // ==================== P06 ====================

    @Test
    fun `P06 desired configuration setters keep working while disabled and send nothing themselves`() {
        disable()

        Grovs.identifier = "user-9"
        Grovs.pushToken = "token-9"
        pump()

        // The setters reach the manager: the caller's latest intent is retained for a later grant.
        verify1 { manager.identifier = "user-9" }
        verify1 { manager.pushToken = "token-9" }
        // ...but no collection or explicit request was started by setting them.
        coVerify(exactly = 0) { manager.track(any(), any(), any()) }
        coVerify(exactly = 0) { manager.linkDetails(any()) }
    }

    private fun verify1(block: () -> Unit) = io.mockk.verify(exactly = 1) { block() }
}
