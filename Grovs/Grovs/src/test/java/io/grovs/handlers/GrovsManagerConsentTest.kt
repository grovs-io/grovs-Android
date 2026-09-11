package io.grovs.handlers

import android.app.Application
import android.content.Context
import io.grovs.FakeLocalCache
import io.grovs.TestAssertions.assertAuthenticated
import io.grovs.TestAssertions.assertUnauthenticated
import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.DebugLogger
import io.grovs.model.GetDeviceResponse
import io.grovs.model.LogLevel
import io.grovs.service.GatedExecutor
import io.grovs.service.IGrovsService
import io.grovs.service.useConsentController
import io.grovs.utils.GVRetryResult
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.LSResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authentication under consent operation ownership.
 *
 * The manager runs against a controllable service so each test can park authentication at an exact
 * point and drive consent around it. Assertions inspect requests, launch records and published
 * state, never the enabled flag alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GrovsManagerConsentTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var grovsContext: GrovsContext
    private lateinit var service: IGrovsService
    private lateinit var eventsManager: IEventsManager
    private lateinit var appDetailsHelper: IAppDetailsHelper
    private lateinit var manager: GrovsManager

    private val launches = AtomicInteger()
    private var heldCleanup: GatedExecutor? = null

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        context = RuntimeEnvironment.getApplication()
        application = RuntimeEnvironment.getApplication()
        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true

        service = mockk(relaxed = true)
        eventsManager = mockk(relaxed = true)
        appDetailsHelper = mockk(relaxed = true)
        coEvery { appDetailsHelper.toAppDetails() } returns appDetails()
        every { appDetailsHelper.deviceID } returns "test-device-id"
        coEvery { eventsManager.logAppLaunchEvents() } coAnswers { launches.incrementAndGet(); Unit }

        DebugLogger.instance.logLevel = LogLevel.INFO
        buildManager()
    }

    /**
     * Constructs the manager against whatever controller [grovsContext] currently holds. Pass
     * [attributesSyncScope] to run its attribute sync in a scope the test drives.
     */
    private fun buildManager(attributesSyncScope: CoroutineScope? = null) {
        manager = GrovsManager(
            context = context,
            application = application,
            grovsContext = grovsContext,
            apiKey = "test-api-key",
            grovsService = service,
            eventsManager = eventsManager,
            appDetailsHelper = appDetailsHelper,
            localCache = FakeLocalCache(numberOfOpens = 1),
            attributesSyncScope = attributesSyncScope,
        )
    }

    /** Replaces [manager] with one whose attribute sync runs in [scope]. */
    private fun useAttributeSyncScope(scope: CoroutineScope) {
        manager.close()
        buildManager(attributesSyncScope = scope)
    }

    /**
     * Replaces the controller with one whose revocation cleanup is held, then rebuilds the manager
     * so it belongs to it. Cancellation is never delivered, so a test can prove that the generation
     * check alone rejects a late result rather than relying on the child being cancelled first.
     */
    private fun holdCleanup(): GatedExecutor = GatedExecutor().also {
        heldCleanup = it
        grovsContext.useConsentController(ConsentController(cleanupExecutor = it))
        buildManager()
    }

    @After
    fun tearDown() {
        heldCleanup?.open()
        manager.close()
        unmockkAll()
    }

    private fun appDetails() = AppDetails(
        version = "1.0.0", build = "1", bundle = "io.grovs.test", device = "Test Device",
        deviceID = "test-device-id", userAgent = "Test User Agent", screenWidth = "1080",
        screenHeight = "1920", timezone = "UTC", language = "en-US",
        webglVendor = "Test Vendor", webglRenderer = "Test Renderer",
    )

    private fun authResponse() = AuthenticationResponse(
        grovsId = "grovs-1", uriScheme = "grovs", sdkIdentifier = null, sdkAttributes = null,
    )

    /**
     * Runs [body] and then releases [gates] on every path. A production write parked on a gate can
     * be [kotlinx.coroutines.NonCancellable], so an assertion that fires while a gate is still shut
     * would hang the test run instead of reporting the failure.
     */
    private inline fun <T> releasing(vararg gates: CompletableDeferred<Unit>, body: () -> T): T =
        try {
            body()
        } finally {
            gates.forEach { it.complete(Unit) }
        }

    private fun deviceRespondsImmediately() {
        every { service.getDeviceFor(any()) } returns flowOf(GVRetryResult.Success(GetDeviceResponse(lastSeen = null)))
    }

    /** A01 */
    @Test
    fun `A01 revoking during the device lookup sends no authenticate request and records no launch`() = runTest {
        val lookupReached = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        every { service.getDeviceFor(any()) } returns flow {
            lookupReached.complete(Unit)
            releaseLookup.await()
            emit(GVRetryResult.Success(GetDeviceResponse(lastSeen = null)))
        }

        val authenticating = async { manager.authenticate() }
        releasing(releaseLookup) {
            runCurrent()
            lookupReached.await()

            grovsContext.settings.sdkEnabled = false
            // The lookup answers only after revocation, so the operation is beyond the flag check
            // and must be stopped by its token instead.
            releaseLookup.complete(Unit)
            advanceUntilIdle()
        }

        assertFalse("a revoked lookup must not authenticate", authenticating.await())
        verify(exactly = 0) { service.authenticate(any()) }
        assertEquals("no launch may be recorded", 0, launches.get())
        assertUnauthenticated(manager, context = "after revocation during the device lookup")
    }

    /** A02 */
    @Test
    fun `A02 an authentication response withheld across a disable-enable cycle cannot authenticate`() = runTest {
        // Revocation cancellation is held, so the collector below is never cancelled: the response
        // really does arrive, after consent was granted again. Only the token check can reject it.
        holdCleanup()
        deviceRespondsImmediately()
        val requestSent = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        every { service.authenticate(any()) } returns flow {
            requestSent.complete(Unit)
            releaseResponse.await()
            emit(GVRetryResult.Success(authResponse()))
        }

        val authenticating = async { manager.authenticate() }
        releasing(releaseResponse) {
            runCurrent()
            requestSent.await()

            // Consent is granted again before the response lands. The old token must stay dead.
            grovsContext.settings.sdkEnabled = false
            grovsContext.settings.sdkEnabled = true
            releaseResponse.complete(Unit)
            advanceUntilIdle()
        }

        assertFalse("the old operation must not report success", authenticating.await())
        assertEquals("a revoked response records no launch", 0, launches.get())
        assertUnauthenticated(manager, context = "after a disable-enable cycle around the response")
    }

    /** A03 */
    @Test
    fun `A03 a launch commit admitted before revocation finishes once and the next grant waits for it`() = runTest {
        deviceRespondsImmediately()
        every { service.authenticate(any()) } returns flowOf(GVRetryResult.Success(authResponse()))
        val writing = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        coEvery { eventsManager.logAppLaunchEvents() } coAnswers {
            writing.complete(Unit)
            releaseWrite.await()
            launches.incrementAndGet()
            Unit
        }

        val authenticating = async { manager.authenticate() }
        val enabled = releasing(releaseWrite) {
            runCurrent()
            writing.await()

            // Revocation loses the race: the commit was admitted before it.
            val revoked = grovsContext.consent.revoke()
            assertTrue("revoke is a transition here", revoked is ConsentTransition.Revoked)
            val enabled = grovsContext.consent.enable() as ConsentTransition.Enabled
            assertFalse(
                "the next grant's prior work must not be complete while the launch write is in flight",
                enabled.priorWork.isCompleted,
            )
            assertEquals("the launch write has not finished yet", 0, launches.get())
            enabled
        }

        advanceUntilIdle()
        authenticating.await()

        assertEquals("the admitted commit records exactly one launch", 1, launches.get())
        assertAuthenticated(manager, context = "after a launch commit admitted before revocation")
        enabled.priorWork.join()
    }

    // ==================== A05-A07: desired configuration under consent ====================

    /** A05 */
    @Test
    fun `A05 identifier, push token and attributes set while disabled are sent as one latest snapshot on enable`() = runTest {
        useAttributeSyncScope(backgroundScope)
        authenticateSuccessfully()

        grovsContext.settings.sdkEnabled = false
        // Several writes, including a clear, while nothing may be sent.
        manager.identifier = "user-1"
        manager.identifier = "user-2"
        manager.pushToken = "token-1"
        manager.attributes = mapOf("plan" to "pro")
        manager.pushToken = null
        // The sync lives in backgroundScope, which runCurrent drives; advanceUntilIdle stops as
        // soon as no foreground work is left, so it would never let the sync run.
        runCurrent()
        coVerify(exactly = 0) { service.updateAttributes(any(), any(), any()) }

        grovsContext.settings.sdkEnabled = true
        manager.onEnabled()
        runCurrent()

        // Exactly one request, carrying the last value of each field - the null push token included.
        coVerify(exactly = 1) { service.updateAttributes("user-2", mapOf("plan" to "pro"), null) }
    }

    /** A06 */
    @Test
    fun `A06 a stale attribute success cannot clear the dirty state a newer value established`() = runTest {
        holdCleanup()
        useAttributeSyncScope(backgroundScope)
        authenticateSuccessfully()

        val oldSent = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val sent = CopyOnWriteArrayList<String?>()
        coEvery { service.updateAttributes(any(), any(), any()) } coAnswers {
            val identifier = firstArg<String?>()
            sent += identifier
            if (identifier == "old") {
                oldSent.complete(Unit)
                // Non-cooperative on purpose: this success is released after the revocation and
                // after a newer value exists, so only a revision check can reject it.
                withContext(NonCancellable) { releaseOld.await() }
                LSResult.Success(true)
            } else {
                // The newer value is never acknowledged, so its dirty state must survive.
                LSResult.Error(java.io.IOException("transport failure"))
            }
        }

        releasing(releaseOld) {
            manager.identifier = "old"
            runCurrent()
            oldSent.await()

            grovsContext.settings.sdkEnabled = false
            grovsContext.settings.sdkEnabled = true
            // Queued behind the parked old job, which the setter cancels but which is unwinding
            // non-cooperatively; releasing it lets the stale success land first.
            manager.identifier = "new"
            releaseOld.complete(Unit)
            runCurrent()
            assertTrue("the newer value must have been sent, got $sent", sent.contains("new"))
        }

        // The stale success belongs to a revision and a generation that are both gone. If it were
        // allowed to clear the dirty state, the unacknowledged newer value would never be retried.
        val before = sent.count { it == "new" }
        manager.onEnabled()
        runCurrent()
        assertEquals(
            "the unacknowledged newer value must still be pending, sends so far: $sent",
            before + 1,
            sent.count { it == "new" },
        )
    }

    /** A07' */
    @Test
    fun `A07 aliases staged while disabled are synced once on enable, an empty map included`() = runTest {
        authenticateSuccessfully()
        coEvery { service.syncScreenAliases(any()) } returns LSResult.Success(true)

        grovsContext.settings.sdkEnabled = false
        manager.setScreenAliases(mapOf("io.grovs.Home" to "Home"))
        manager.setScreenAliases(emptyMap())
        advanceUntilIdle()
        coVerify(exactly = 0) { service.syncScreenAliases(any()) }

        grovsContext.settings.sdkEnabled = true
        manager.onEnabled()
        advanceUntilIdle()

        // The latest pending set wins, and clearing the aliases is a set like any other.
        coVerify(exactly = 1) { service.syncScreenAliases(emptyMap()) }
    }

    /** Drives the manager to AUTHENTICATED through the ordinary path. */
    private suspend fun authenticateSuccessfully() {
        deviceRespondsImmediately()
        every { service.authenticate(any()) } returns flowOf(GVRetryResult.Success(authResponse()))
        assertTrue("the fixture must authenticate", manager.authenticate())
    }
}
