package io.grovs.handlers

import android.os.Looper
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import io.grovs.settings.GrovsSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * C01–C12: the consent controller, its generation tokens, operation registration, commit admission
 * and configuration ownership.
 *
 * Races that are about admission versus revocation run on real threads lined up with latches and
 * barriers, never sleeps. Everything else drives a [TestCoroutineScheduler]. Every timeout fails
 * the test instead of letting an assertion pass vacuously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentControllerTest {

    private val teardown = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        teardown.asReversed().forEach { runCatching(it) }
        teardown.clear()
    }

    // ==================== Fixtures ====================

    /** Runs cancellation callbacks on the revoking thread, right after the controller lock is released. */
    private val inline = Executor { it.run() }

    /** Holds cancellation callbacks until [runAll], so tests can separate token invalidation from cancellation delivery. */
    private class HeldExecutor : Executor {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) {
            queue.add(command)
        }
        val pending: Int get() = queue.size
        fun runAll() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    private class RecordingHandle : ConsentCancellationHandle {
        val causes = CopyOnWriteArrayList<ConsentRevokedException>()
        override fun cancel(cause: ConsentRevokedException) {
            causes += cause
        }
    }

    /** A real thread whose failure is rethrown by [joinOrFail] instead of being lost. */
    private class RaceThread(name: String, body: () -> Unit) {
        private val error = AtomicReference<Throwable?>()
        private val thread = Thread({
            try {
                body()
            } catch (t: Throwable) {
                error.set(t)
            }
        }, name).apply { isDaemon = true }

        fun start() = apply { thread.start() }

        fun joinOrFail(seconds: Long = 10) {
            thread.join(TimeUnit.SECONDS.toMillis(seconds))
            assertFalse("${thread.name} is still running after ${seconds}s (deadlock or missed barrier)", thread.isAlive)
            error.get()?.let { throw AssertionError("${thread.name} failed: $it", it) }
        }
    }

    private fun controller(enabled: Boolean = true, executor: Executor = inline) =
        ConsentController(initiallyEnabled = enabled, cleanupExecutor = executor)

    private fun ConsentController.acquire(): ConsentToken =
        requireNotNull(tryAcquire(currentConfiguration)) { "expected an enabled controller to issue a token" }

    private fun CountDownLatch.awaitOrFail(what: String, seconds: Long = 5) {
        assertTrue("Timed out waiting for $what", await(seconds, TimeUnit.SECONDS))
    }

    private fun CyclicBarrier.awaitOrFail() {
        await(5, TimeUnit.SECONDS)
    }

    /** Waits for [this] to complete and returns its completion cause (null for a normal completion). */
    private fun Job.awaitCompletionCause(what: String): Throwable? {
        val cause = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        invokeOnCompletion {
            cause.set(it)
            done.countDown()
        }
        done.awaitOrFail(what)
        return cause.get()
    }

    private fun assertRevokedBy(expected: RevocationReason, cause: Throwable?, token: ConsentToken? = null) {
        assertTrue("expected a ConsentRevokedException, got $cause", cause is ConsentRevokedException)
        cause as ConsentRevokedException
        assertEquals(expected, cause.reason)
        if (token != null) assertSame(token, cause.token)
    }

    private fun singleThreadScope(name: String): CoroutineScope {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        teardown += {
            scope.cancel()
            executor.shutdownNow()
        }
        return scope
    }

    /** Waits until every task already submitted to [scope]'s single thread has run. */
    private fun drain(scope: CoroutineScope) {
        val drained = CountDownLatch(1)
        scope.launch { drained.countDown() }
        drained.awaitOrFail("the worker thread to drain")
    }

    // ==================== Settings / public-entry wiring ====================

    @Test
    fun `GrovsSettings sdkEnabled is a view of the controller and its writes are real transitions`() {
        val context = GrovsContext(StandardTestDispatcher())
        val consent = context.consent
        assertSame("GrovsContext must expose the settings' controller", context.settings.consent, consent)

        val token = consent.acquire()
        val handle = RecordingHandle()
        assertNotNull(consent.register(token, handle))
        val generation = consent.generation

        context.settings.sdkEnabled = true
        assertEquals("writing the current value is not a transition", generation, consent.generation)

        context.settings.sdkEnabled = false
        assertFalse(consent.isEnabled)
        assertFalse(context.settings.sdkEnabled)
        assertFalse("a false write must revoke, not just flip a Boolean", consent.isCurrent(token))
        // The default cleanup executor delivers the cancellation off this thread.
        val delivered = CountDownLatch(1)
        consent.pendingWork().invokeOnCompletion { delivered.countDown() }
        delivered.awaitOrFail("revocation cleanup")
        assertEquals(1, handle.causes.size)

        context.settings.sdkEnabled = true
        assertTrue(consent.isEnabled)
        assertTrue(context.settings.sdkEnabled)
        assertFalse("re-enabling never revives an old token", consent.isCurrent(token))
        assertTrue(consent.isCurrent(consent.acquire()))

        val standalone = GrovsSettings()
        assertTrue("the public no-arg GrovsSettings keeps its enabled default", standalone.sdkEnabled)
        standalone.sdkEnabled = false
        assertFalse(standalone.consent.isEnabled)
    }

    @Test
    fun `setSDK and configure route through the controller`() {
        val context = configureSingleton()
        val consent = context.consent
        val first = consent.acquire()

        Grovs.setSDK(false)
        assertFalse(consent.isCurrent(first))
        val disabledGeneration = consent.generation
        Grovs.setSDK(false)
        assertEquals("a repeated disable is not a transition", disabledGeneration, consent.generation)

        Grovs.setSDK(true)
        val enabledGeneration = consent.generation
        assertNotEquals(disabledGeneration, enabledGeneration)
        Grovs.setSDK(true)
        assertEquals("a repeated enable is not a transition", enabledGeneration, consent.generation)

        val before = consent.currentConfiguration
        Grovs.configure(RuntimeEnvironment.getApplication(), "same-key", useTestEnvironment = true,
            baseURL = null, autoTrackScreenViews = true, clipboardDomains = null, enabled = false)
        assertNotSame(before, consent.currentConfiguration)
        assertFalse(consent.isEnabled)
        assertFalse(context.settings.sdkEnabled)
    }

    // ==================== C01 ====================

    @Test
    fun `C01 disabled acquisition rejects, enabled acquisition succeeds, repeated toggles add no transitions`() {
        val consent = controller(enabled = false)
        val configuration = consent.currentConfiguration

        assertNull(consent.tryAcquire(configuration))
        assertSame(ConsentTransition.Unchanged, consent.revoke())
        assertNull("a no-op revoke must not admit anything", consent.tryAcquire(configuration))

        val enabled = consent.enable()
        assertTrue(enabled is ConsentTransition.Enabled)
        val token = consent.acquire()
        assertTrue(consent.isCurrent(token))

        val generation = consent.generation
        assertSame(ConsentTransition.Unchanged, consent.enable())
        assertEquals(generation, consent.generation)
        assertTrue("a repeated enable must not invalidate current tokens", consent.isCurrent(token))
        assertEquals(token.generation, consent.acquire().generation)

        assertTrue(consent.revoke() is ConsentTransition.Revoked)
        val revokedGeneration = consent.generation
        assertSame(ConsentTransition.Unchanged, consent.revoke())
        assertEquals(revokedGeneration, consent.generation)
        assertNull(consent.tryAcquire(configuration))
    }

    // ==================== C02 ====================

    @Test
    fun `C02 revoke invalidates every old token and no disable-enable cycle validates one again`() {
        val consent = controller()
        val old = listOf(consent.acquire(), consent.acquire())

        consent.revoke()
        old.forEach { assertFalse(consent.isCurrent(it)) }

        val seen = mutableSetOf(old.first().generation)
        repeat(3) {
            val enabled = consent.enable()
            assertTrue(enabled is ConsentTransition.Enabled)
            val fresh = consent.acquire()
            assertTrue("enable must produce a new generation", seen.add(fresh.generation))
            assertTrue("positive control: the fresh token is current", consent.isCurrent(fresh))

            for (token in old) {
                assertFalse("an old token became current again after enable #$it", consent.isCurrent(token))
                assertNull(consent.register(token, RecordingHandle()))
                assertNull(consent.tryAdmitCommit(token, CommitKind.STORAGE_TRANSACTION))
                try {
                    consent.ensureCurrent(token)
                    fail("ensureCurrent accepted a revoked token")
                } catch (e: ConsentRevokedException) {
                    assertEquals(RevocationReason.REVOKED, e.reason)
                    assertSame(token, e.token)
                }
            }
            consent.revoke()
        }
    }

    // ==================== C03 ====================

    @Test
    fun `C03 a child registered before revoke on another thread is cancelled`() {
        val consent = controller(executor = inline)
        val token = consent.acquire()
        val scope = singleThreadScope("c03-worker")
        val bodyStarted = CountDownLatch(1)
        val sideEffects = CopyOnWriteArrayList<String>()
        val child = AtomicReference<Job?>()

        val registrar = RaceThread("c03-registrar") {
            child.set(consent.launchOperation(token, scope) {
                bodyStarted.countDown()
                CompletableDeferred<Unit>().await()
                sideEffects += "ran after revoke"
            })
        }.start()
        registrar.joinOrFail()
        assertNotNull("positive control: the registration was admitted", child.get())
        bodyStarted.awaitOrFail("the child to start")

        val revoker = RaceThread("c03-revoker") { consent.revoke() }.start()
        revoker.joinOrFail()

        assertRevokedBy(RevocationReason.REVOKED, child.get()!!.awaitCompletionCause("the revoked child"), token)
        assertEquals(emptyList<String>(), sideEffects)
        assertEquals("completion must unregister the child", 0, consent.registrationCount())
    }

    @Test
    fun `C03 a registration attempted after revoke on another thread never starts`() {
        val consent = controller(executor = inline)
        val token = consent.acquire()
        val scope = singleThreadScope("c03-worker")
        val revoked = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val launched = AtomicReference<Job?>()
        val rawJob = Job()
        val rawRegistration = AtomicReference<ConsentRegistration?>()

        val revoker = RaceThread("c03-revoker") {
            consent.revoke()
            revoked.countDown()
        }
        val registrar = RaceThread("c03-registrar") {
            revoked.awaitOrFail("the revocation")
            launched.set(consent.launchOperation(token, scope) { started.set(true) })
            rawRegistration.set(consent.register(token, rawJob))
        }
        registrar.start()
        revoker.start()
        revoker.joinOrFail()
        registrar.joinOrFail()
        drain(scope)

        assertNull(launched.get())
        assertNull(rawRegistration.get())
        assertFalse("a rejected child must never start", started.get())
        assertRevokedBy(RevocationReason.REVOKED, rawJob.awaitCompletionCause("the rejected job"), token)
        assertEquals(0, consent.registrationCount())

        // Positive control on the same fixture.
        consent.enable()
        val ran = CountDownLatch(1)
        assertNotNull(consent.launchOperation(consent.acquire(), scope) { ran.countDown() })
        ran.awaitOrFail("an admitted child to run")
    }

    @Test
    fun `C03 bounded real-thread registration stress never leaves an admitted child uncancelled`() {
        val consent = controller(executor = inline)
        val workers = 6
        val maxAttemptsPerWorker = 20_000
        val toggles = 150
        val start = CyclicBarrier(workers + 1)
        val togglerDone = AtomicBoolean(false)

        class Attempt(val token: ConsentToken, val handle: RecordingHandle, val admitted: Boolean)
        val attempts = ConcurrentLinkedQueue<Attempt>()

        val threads = (1..workers).map { index ->
            RaceThread("c03-stress-$index") {
                start.awaitOrFail()
                // Keep registering for as long as the toggler runs (bounded), so attempts overlap every transition.
                var tries = 0
                while (!togglerDone.get() && tries++ < maxAttemptsPerWorker) {
                    val token = consent.tryAcquire(consent.currentConfiguration) ?: continue
                    val handle = RecordingHandle()
                    attempts += Attempt(token, handle, consent.register(token, handle) != null)
                }
            }.start()
        } + RaceThread("c03-stress-toggler") {
            start.awaitOrFail()
            repeat(toggles) {
                consent.revoke()
                consent.enable()
            }
            togglerDone.set(true)
        }.start()
        threads.forEach { it.joinOrFail(30) }

        consent.revoke()

        val admitted = attempts.filter { it.admitted }
        assertTrue("the stress run must admit some registrations", admitted.isNotEmpty())
        for (attempt in attempts) {
            if (attempt.admitted) {
                assertEquals("an admitted registration must be cancelled exactly once", 1, attempt.handle.causes.size)
                assertSame(attempt.token, attempt.handle.causes.single().token)
            } else {
                assertEquals("a rejected registration must never be cancelled", 0, attempt.handle.causes.size)
            }
        }
        assertEquals(0, consent.registrationCount())
        println("C03 stress: ${attempts.size} registrations, ${admitted.size} admitted, ${attempts.size - admitted.size} rejected")
    }

    // ==================== C04 ====================

    @Test
    fun `C04 an operation queued while enabled, revoked before it runs, stays dead after enable`() {
        val scheduler = TestCoroutineScheduler()
        val held = HeldExecutor()
        val consent = controller(executor = held)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val sideEffects = mutableListOf<String>()

        val queued = consent.launchOperation(consent.currentConfiguration, scope) { sideEffects += "old" }
        assertNotNull(queued)

        consent.revoke()
        consent.enable()
        // Cancellation has not been delivered (the executor holds it): the generation check alone must stop the body.
        assertEquals(1, held.pending)
        scheduler.advanceUntilIdle()

        assertEquals(emptyList<String>(), sideEffects)
        assertRevokedBy(RevocationReason.REVOKED, queued!!.awaitCompletionCause("the queued operation"))
        held.runAll()

        // Positive control: an operation admitted under the new generation runs on the same fixture.
        assertNotNull(consent.launchOperation(consent.currentConfiguration, scope) { sideEffects += "new" })
        scheduler.advanceUntilIdle()
        assertEquals(listOf("new"), sideEffects)
        assertEquals(0, consent.registrationCount())
    }

    // ==================== C05 ====================

    @Test
    fun `C05 a call made while disabled stays rejected even if its dispatcher runs after enable`() {
        val scheduler = TestCoroutineScheduler()
        val consent = controller(enabled = false)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val sideEffects = mutableListOf<String>()

        assertNull(consent.launchOperation(consent.currentConfiguration, scope) { sideEffects += "disabled call" })
        consent.enable()
        scheduler.advanceUntilIdle()
        assertEquals(emptyList<String>(), sideEffects)

        assertNotNull(consent.launchOperation(consent.currentConfiguration, scope) { sideEffects += "enabled call" })
        scheduler.advanceUntilIdle()
        assertEquals(listOf("enabled call"), sideEffects)
    }

    // ==================== C06 ====================

    @Test
    fun `C06 an old generation's cleanup in progress cannot cancel a child registered under the new generation`() {
        val cleanupStarted = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val consent = controller(executor = Executor { r -> Thread(r, "c06-cleanup").apply { isDaemon = true }.start() })
        teardown += { proceed.countDown() }

        val old = consent.acquire()
        assertNotNull(consent.register(old) { _ ->
            cleanupStarted.countDown()
            proceed.await(5, TimeUnit.SECONDS)
        })
        val oldChild = Job()
        assertNotNull(consent.register(old, oldChild))

        val revoked = consent.revoke() as ConsentTransition.Revoked
        cleanupStarted.awaitOrFail("the old generation's cleanup to start")

        val newChild = Job()
        val newRegistration = AtomicReference<ConsentRegistration?>()
        val registrar = RaceThread("c06-registrar") {
            consent.enable()
            newRegistration.set(consent.register(consent.acquire(), newChild))
        }.start()
        registrar.joinOrFail()
        assertNotNull(newRegistration.get())

        proceed.countDown()
        assertNull(revoked.cleanup.awaitCompletionCause("the old cleanup"))

        assertTrue(oldChild.isCancelled)
        assertTrue("the old cleanup cancelled a new-generation child", newChild.isActive)
        assertEquals(1, consent.registrationCount())
        newChild.cancel()
    }

    @Test
    fun `C06 old cleanup racing new registrations on real threads never touches the new generation`() {
        repeat(100) { round ->
            val consent = controller(executor = inline)
            val old = consent.acquire()
            val oldHandles = List(50) { RecordingHandle().also { h -> assertNotNull(consent.register(old, h)) } }
            val newHandle = RecordingHandle()
            val go = CyclicBarrier(2)

            val revoker = RaceThread("c06-revoker-$round") {
                go.awaitOrFail()
                consent.revoke() // cleanup runs inline on this thread, after the lock is released
            }.start()
            val registrar = RaceThread("c06-registrar-$round") {
                go.awaitOrFail()
                while (consent.isEnabled) Thread.yield()
                consent.enable()
                assertNotNull(consent.register(consent.acquire(), newHandle))
            }.start()
            revoker.joinOrFail()
            registrar.joinOrFail()

            oldHandles.forEach { assertEquals(1, it.causes.size) }
            assertEquals("round $round: the new child was cancelled by old cleanup", 0, newHandle.causes.size)
            assertEquals(1, consent.registrationCount())
        }
    }

    // ==================== C07 ====================

    @Test
    fun `C07 completion unregisters on success, error and cancellation, and revoke then touches nothing`() {
        val scheduler = TestCoroutineScheduler()
        val consent = controller(executor = inline)
        val token = consent.acquire()
        val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, e -> errors += e })

        // runOperation: success, error, host cancellation.
        val results = mutableListOf<Any?>()
        scope.launch { results += consent.runOperation(token) { 42 } }
        scope.launch {
            try {
                consent.runOperation(token) { throw IllegalStateException("boom") }
            } catch (e: IllegalStateException) {
                results += e.message
            }
        }
        val hostCancelled = scope.launch { consent.runOperation(token) { awaitCancellation() } }
        // launchOperation: success, error, cancellation.
        val launchedOk = consent.launchOperation(token, scope) { results += "launched" }!!
        val launchedError = consent.launchOperation(token, scope) { throw IllegalArgumentException("launched boom") }!!
        val launchedCancelled = consent.launchOperation(token, scope) { awaitCancellation() }!!
        // A bare handle, explicitly unregistered.
        val bare = RecordingHandle()
        consent.register(token, bare)!!.unregister()

        scheduler.advanceUntilIdle()
        // Completion order across operations is not part of the contract.
        assertEquals(setOf<Any?>(42, "boom", "launched"), results.toSet())
        assertEquals(3, results.size)
        assertEquals("only the two suspended operations are still registered", 2, consent.registrationCount())

        hostCancelled.cancel()
        launchedCancelled.cancel()
        scheduler.advanceUntilIdle()
        assertEquals(0, consent.registrationCount())
        assertEquals(listOf("launched boom"), errors.map { it.message })

        val revoked = consent.revoke() as ConsentTransition.Revoked
        assertTrue("nothing was left to clean up", revoked.cleanup.isCompleted)
        assertEquals(0, bare.causes.size)
        assertFalse(launchedOk.isCancelled)
        assertTrue(launchedError.isCancelled) // failed, not revoked
        assertSame(ConsentTransition.Unchanged, consent.revoke())
        assertEquals(0, consent.registrationCount())
    }

    // ==================== C08 ====================

    @Test
    fun `C08 host cancellation cancels only its operation and consent cancellation spares the host`() {
        val scheduler = TestCoroutineScheduler()
        val consent = controller(executor = inline)
        val token = consent.acquire()
        val host = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
        val sibling = host.launch { awaitCancellation() }

        val caught = AtomicReference<Throwable?>()
        val continuedAfterRevoke = AtomicBoolean(false)
        val survivingCaller = host.launch {
            try {
                consent.runOperation(token) { awaitCancellation() }
            } catch (e: ConsentRevokedException) {
                caught.set(e)
            }
            continuedAfterRevoke.set(true)
        }
        val cancelledCaller = host.launch { consent.runOperation(token) { awaitCancellation() } }
        val launchedIntoHost = consent.launchOperation(token, host) { awaitCancellation() }!!
        scheduler.advanceUntilIdle()
        assertEquals(3, consent.registrationCount())

        // Host cancellation of one caller: only its operation goes.
        cancelledCaller.cancel()
        scheduler.advanceUntilIdle()
        assertTrue(cancelledCaller.isCancelled)
        assertEquals(2, consent.registrationCount())
        assertTrue(survivingCaller.isActive)
        assertTrue(sibling.isActive)
        assertTrue(launchedIntoHost.isActive)

        // Consent cancellation: only the SDK children go.
        consent.revoke()
        scheduler.advanceUntilIdle()
        assertRevokedBy(RevocationReason.REVOKED, caught.get(), token)
        assertTrue("the caller must keep running after its SDK child was revoked", continuedAfterRevoke.get())
        assertTrue(launchedIntoHost.isCancelled)
        assertTrue("revocation must not cancel the host scope", host.coroutineContext[Job]!!.isActive)
        assertTrue("revocation must not cancel host siblings", sibling.isActive)
        assertEquals(0, consent.registrationCount())
        host.cancel()
    }

    // ==================== C09 ====================

    @Test
    fun `C09 nested operations inherit the original token and cannot reacquire consent after revocation`() {
        val scheduler = TestCoroutineScheduler()
        val held = HeldExecutor() // keeps the parent alive after revoke, so only the token check can stop the nested call
        val consent = controller(executor = held)
        val configuration = consent.currentConfiguration
        val parentToken = consent.acquire()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        val inherited = mutableListOf<ConsentToken?>()
        val nestedRanAfterRevoke = AtomicBoolean(false)
        val nestedFailure = AtomicReference<Throwable?>()
        val freshAvailable = AtomicBoolean(false)

        scope.launch {
            consent.runOperation(parentToken) {
                consent.runOperation(configuration) { inherited += currentConsentToken() }

                consent.revoke()
                consent.enable()
                freshAvailable.set(consent.tryAcquire(configuration) != null)
                try {
                    consent.runOperation(configuration) { nestedRanAfterRevoke.set(true) }
                } catch (e: ConsentRevokedException) {
                    nestedFailure.set(e)
                }
            }
        }
        scheduler.advanceUntilIdle()

        assertTrue("consent is current again, so a fresh token was available", freshAvailable.get())
        assertFalse("a nested call acquired fresh consent for a revoked parent", nestedRanAfterRevoke.get())
        assertRevokedBy(RevocationReason.REVOKED, nestedFailure.get(), parentToken)
        assertEquals(1, inherited.size)
        assertSame("a nested call must run under its parent's token", parentToken, inherited.single())

        // Positive control: a top-level operation after enable runs under a new token.
        val topLevel = AtomicReference<ConsentToken?>()
        scope.launch { consent.runOperation(configuration) { topLevel.set(currentConsentToken()) } }
        scheduler.advanceUntilIdle()
        assertNotNull(topLevel.get())
        assertNotEquals(parentToken.generation, topLevel.get()!!.generation)
        held.runAll()
    }

    // ==================== C10 ====================

    @Test
    fun `C10 a retired configuration cannot acquire, register, commit or keep work running`() {
        val consent = controller(executor = inline)
        val a = consent.currentConfiguration
        val aToken = consent.acquire()
        val aJob = Job()
        assertNotNull(consent.register(aToken, aJob))
        val aScopeChild = a.scope.launch { awaitCancellation() }
        val bStore = mutableListOf<String>()

        val retirement = consent.retireConfiguration(enabled = true)
        val b = retirement.current
        assertSame(a, retirement.retired)
        assertNotSame(a, b)
        assertTrue(consent.isEnabled)

        assertNull("A must not acquire even while consent is enabled", consent.tryAcquire(a))
        assertFalse(consent.isCurrent(aToken))
        assertNull(consent.register(aToken, RecordingHandle()))
        assertNull(consent.tryAdmitCommit(aToken, CommitKind.STORAGE_TRANSACTION)?.use { bStore += "from A" })
        assertNull(consent.launchOperation(a) { bStore += "A launched" })
        try {
            consent.ensureCurrent(aToken)
            fail("a retired configuration's token was accepted")
        } catch (e: ConsentRevokedException) {
            assertEquals(RevocationReason.CONFIGURATION_RETIRED, e.reason)
        }
        assertRevokedBy(RevocationReason.CONFIGURATION_RETIRED, aJob.awaitCompletionCause("A's registered job"), aToken)
        assertTrue(aScopeChild.awaitCompletionCause("A's lifetime child") is ConsentRevokedException)
        assertTrue(retirement.cleanup.isCompleted)

        // Positive control: B is live.
        val bToken = requireNotNull(consent.tryAcquire(b))
        consent.tryAdmitCommit(bToken, CommitKind.STORAGE_TRANSACTION)!!.use { bStore += "from B" }
        assertEquals(listOf("from B"), bStore)

        val c = consent.retireConfiguration(enabled = false)
        assertFalse(consent.isEnabled)
        assertNull(consent.tryAcquire(c.current))
        assertFalse(consent.isCurrent(bToken))
    }

    @Test
    fun `C10 same-key reconfiguration retires the previous managers as owners`() {
        val context = configureSingleton(apiKey = "same-key")
        val consent = context.consent
        val oldManager = currentGrovsManager()
        val oldNotifications = currentNotificationsManager()
        val oldToken = requireNotNull(consent.tryAcquire(oldManager.configuration))
        assertSame(oldManager.configuration, oldNotifications.configuration)

        Grovs.configure(RuntimeEnvironment.getApplication(), "same-key", useTestEnvironment = true)
        val newManager = currentGrovsManager()

        assertNotSame(oldManager, newManager)
        assertNotSame(oldManager.configuration, newManager.configuration)
        assertSame(newManager.configuration, currentNotificationsManager().configuration)
        assertNull("the old manager's configuration must not acquire after same-key reconfigure", consent.tryAcquire(oldManager.configuration))
        assertNull(consent.tryAcquire(oldNotifications.configuration))
        assertFalse(consent.isCurrent(oldToken))
        assertNull(consent.tryAdmitCommit(oldToken, CommitKind.STORAGE_TRANSACTION))
        assertNotNull("positive control: the replacement acquires", consent.tryAcquire(newManager.configuration))
    }

    // ==================== C11 ====================

    @Test
    fun `C11 a commit admitted before revoke finishes, and new work waits for it asynchronously`() {
        val scheduler = TestCoroutineScheduler()
        val consent = controller(executor = inline)
        val token = consent.acquire()
        val store = mutableListOf<String>()

        val permit = requireNotNull(consent.tryAdmitCommit(token, CommitKind.STORAGE_TRANSACTION))
        val revoked = consent.revoke() as ConsentTransition.Revoked
        assertFalse("an admitted commit keeps the revocation cleanup open", revoked.cleanup.isCompleted)
        val enabled = consent.enable() as ConsentTransition.Enabled
        assertFalse(enabled.priorWork.isCompleted)

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        scope.launch {
            enabled.priorWork.join()
            store += "resume"
        }
        scheduler.advanceUntilIdle()
        assertEquals("dependent work must wait for the admitted commit", emptyList<String>(), store)

        permit.use { store += "committed" }
        permit.close() // idempotent
        assertTrue(revoked.cleanup.isCompleted)
        assertEquals(0, consent.outstandingCommitCount())
        scheduler.advanceUntilIdle()
        assertEquals(listOf("committed", "resume"), store)
    }

    @Test
    fun `C11 a commit not admitted before revoke never starts`() {
        val consent = controller(executor = inline)
        val token = consent.acquire()
        val store = mutableListOf<String>()

        consent.revoke()
        assertNull(consent.tryAdmitCommit(token, CommitKind.ACKNOWLEDGEMENT)?.use { store += "stale" })
        consent.enable()
        assertNull(consent.tryAdmitCommit(token, CommitKind.ACKNOWLEDGEMENT)?.use { store += "stale after enable" })
        assertEquals(emptyList<String>(), store)
        assertEquals(0, consent.outstandingCommitCount())

        consent.tryAdmitCommit(consent.acquire(), CommitKind.ACKNOWLEDGEMENT)!!.use { store += "current" }
        assertEquals(listOf("current"), store)
    }

    @Test
    fun `C11 commit versus revoke on real threads in both orders`() {
        // Commit admitted first: revoke returns promptly while the commit is still writing; the write lands.
        run {
            val consent = controller(executor = inline)
            val token = consent.acquire()
            val store = CopyOnWriteArrayList<String>()
            val admitted = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val revokeReturned = CountDownLatch(1)
            val committer = RaceThread("c11-committer") {
                val permit = requireNotNull(consent.tryAdmitCommit(token, CommitKind.AUTHENTICATION))
                admitted.countDown()
                permit.use {
                    finish.awaitOrFail("the revoker")
                    store += "admitted commit"
                }
            }.start()
            val revoker = RaceThread("c11-revoker") {
                admitted.awaitOrFail("admission")
                val revoked = consent.revoke() as ConsentTransition.Revoked
                assertFalse(revoked.cleanup.isCompleted)
                revokeReturned.countDown()
                finish.countDown()
                assertNull(revoked.cleanup.awaitCompletionCause("the commit to finish"))
            }.start()
            committer.joinOrFail()
            revoker.joinOrFail()
            assertEquals(0, revokeReturned.count)
            assertEquals(listOf("admitted commit"), store)
        }
        // Revoke first: the commit is refused and its body never runs.
        run {
            val consent = controller(executor = inline)
            val token = consent.acquire()
            val store = CopyOnWriteArrayList<String>()
            val revoked = CountDownLatch(1)
            val revoker = RaceThread("c11-revoker") {
                consent.revoke()
                revoked.countDown()
            }.start()
            val committer = RaceThread("c11-committer") {
                revoked.awaitOrFail("the revocation")
                consent.tryAdmitCommit(token, CommitKind.AUTHENTICATION)?.use { store += "unadmitted commit" }
            }.start()
            revoker.joinOrFail()
            committer.joinOrFail()
            assertEquals(emptyList<String>(), store)
        }
    }

    // ==================== C12 ====================

    @Test
    fun `C12 revoke returns promptly while cleanup is stalled and dependent work waits asynchronously`() {
        val consent = ConsentController() // production cleanup executor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        teardown += { release.countDown() }
        assertNotNull(consent.register(consent.acquire()) { _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        })

        val start = System.nanoTime()
        val revoked = consent.revoke() as ConsentTransition.Revoked
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("revoke took ${elapsedMs}ms with a stalled cleanup handler", elapsedMs < BUDGET_MS)
        entered.awaitOrFail("the stalled handler to be running")
        assertFalse(revoked.cleanup.isCompleted)

        val enabled = consent.enable() as ConsentTransition.Enabled
        assertFalse("enable must expose the stalled cleanup as prior work", enabled.priorWork.isCompleted)

        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val dependentRan = AtomicBoolean(false)
        scope.launch {
            enabled.priorWork.join()
            dependentRan.set(true)
        }
        scheduler.advanceUntilIdle() // returns: the dependent is suspended, not blocking this thread
        assertFalse(dependentRan.get())

        release.countDown()
        assertNull(revoked.cleanup.awaitCompletionCause("the stalled cleanup"))
        enabled.priorWork.awaitCompletionCause("prior work")
        scheduler.advanceUntilIdle()
        assertTrue(dependentRan.get())
    }

    @Test
    fun `C12 setSDK stays prompt on the main thread while revocation cleanup is stalled`() {
        val context = configureSingleton()
        val consent = context.consent
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        teardown += { release.countDown() }
        assertNotNull(consent.register(consent.acquire()) { _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        })
        assertSame(Looper.getMainLooper(), Looper.myLooper())

        var start = System.nanoTime()
        Grovs.setSDK(false)
        var elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("setSDK(false) took ${elapsedMs}ms", elapsedMs < BUDGET_MS)
        entered.awaitOrFail("the stalled handler to be running")
        assertFalse(context.settings.sdkEnabled)

        start = System.nanoTime()
        Grovs.setSDK(true)
        elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("setSDK(true) took ${elapsedMs}ms while cleanup was stalled", elapsedMs < BUDGET_MS)
        assertTrue(context.settings.sdkEnabled)
        assertFalse("the handler is still stalled", consent.pendingWork().isCompleted)
        release.countDown()
    }

    @Test
    fun `C12 cancellation callbacks can re-enter the controller without deadlock`() {
        val consent = controller(executor = inline)
        val reentered = CopyOnWriteArrayList<String>()
        val innerHandle = RecordingHandle()
        val outer = consent.acquire()

        consent.register(outer) { cause ->
            reentered += "isEnabled=${consent.isEnabled}"
            reentered += "old token current=${consent.isCurrent(cause.token!!)}"
            reentered += "old register=${consent.register(cause.token!!, RecordingHandle()) != null}"
            consent.enable()
            val fresh = consent.acquire()
            reentered += "fresh register=${consent.register(fresh, innerHandle) != null}"
            consent.revoke() // nested revocation from inside a cancellation callback
        }
        val job = Job()
        consent.register(outer, job)
        job.invokeOnCompletion { reentered += "completion handler pending=${consent.pendingWork().isCompleted}" }

        val revoker = RaceThread("c12-revoker") { consent.revoke() }.start()
        revoker.joinOrFail(5)

        assertEquals(
            listOf(
                "isEnabled=false",
                "old token current=false",
                "old register=false",
                "fresh register=true",
            ),
            reentered.take(4),
        )
        assertTrue(reentered.any { it.startsWith("completion handler") })
        assertEquals("the nested revoke cancelled the re-entrant registration", 1, innerHandle.causes.size)
        assertFalse(consent.isEnabled)
        assertEquals(0, consent.registrationCount())
    }

    // ==================== Singleton helpers ====================

    private fun configureSingleton(apiKey: String = "test-api-key"): GrovsContext {
        val application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent controller tests")
        E2ETestUtils.setupMockScreenResolution()
        // Never advanced: configure() hands authentication to this dispatcher and nothing runs.
        val context = GrovsContext(serialDispatcher = StandardTestDispatcher())
        Grovs::class.java.getDeclaredField("grovsContext").apply {
            isAccessible = true
            set(E2ETestUtils.getGrovsInstance(), context)
        }
        teardown += { E2ETestUtils.resetGrovsSingleton() }
        Grovs.configure(application, apiKey, useTestEnvironment = true)
        return context
    }

    private fun currentGrovsManager(): GrovsManager =
        Grovs::class.java.getDeclaredField("grovsManager").apply { isAccessible = true }
            .get(E2ETestUtils.getGrovsInstance()) as GrovsManager

    private fun currentNotificationsManager(): NotificationsManager =
        Grovs::class.java.getDeclaredField("notificationsManager").apply { isAccessible = true }
            .get(E2ETestUtils.getGrovsInstance()) as NotificationsManager

    companion object {
        private const val BUDGET_MS = 500L
    }
}
