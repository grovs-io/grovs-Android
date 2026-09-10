package io.grovs.handlers

import io.grovs.FakeLocalCache
import io.grovs.model.AppDetails
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.LogLevel
import io.grovs.service.IGrovsService
import io.grovs.utils.ClipDescriptionResult
import io.grovs.utils.IClipboard
import io.grovs.utils.LSResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L03/L04 — the clipboard flow's barrier must cover consent, not only lookup staleness.
 *
 * Consent is withdrawn from inside the clipboard step itself, so by the time the flow reaches its
 * next barrier the revocation has already happened. Nothing is cancelled here: only the barrier
 * check can stop the flow, which is exactly what these tests are about.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConsentClipboardTest {

    /** A clipboard that can run an action inside any one of its steps, and counts every access. */
    private class SteppedClipboard(
        var onAwaitAccess: () -> Unit = {},
        var onDescribe: () -> Unit = {},
        var onRead: () -> Unit = {},
    ) : IClipboard {
        var awaitAccessCount = 0
        var describeCount = 0
        var readCount = 0
        var clearCount = 0
        var text: String? = "https://demo.sqd.link/abc?gd=1"

        override suspend fun awaitAccess(timeoutMs: Long): Boolean {
            awaitAccessCount++
            onAwaitAccess()
            return true
        }

        override suspend fun describe(): ClipDescriptionResult {
            describeCount++
            onDescribe()
            return ClipDescriptionResult.MAYBE_URL
        }

        override fun readText(): String? {
            readCount++
            onRead()
            return text
        }

        override fun clear() {
            clearCount++
            text = null
        }
    }

    private lateinit var grovsContext: GrovsContext
    private lateinit var service: IGrovsService
    private lateinit var clipboard: SteppedClipboard
    private lateinit var handler: ClipboardHandler

    private val appDetails = AppDetails(
        version = "1.0.0", build = "1", bundle = "io.grovs.test", device = "Test Device",
        deviceID = "device", userAgent = "UA", screenWidth = "1080", screenHeight = "1920",
        timezone = "UTC", language = "en-US", webglVendor = "V", webglRenderer = "R",
    )

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        DebugLogger.instance.logLevel = LogLevel.INFO
        grovsContext = GrovsContext()
        service = mockk(relaxed = true)
        coEvery { service.clipboardStatus() } returns LSResult.Success(true)
        coEvery { service.payloadWithLinkFor(any()) } returns
            LSResult.Success(DeeplinkDetails(link = "https://demo.sqd.link/abc?gd=1", data = null, tracking = null))
        clipboard = SteppedClipboard()
        handler = ClipboardHandler(
            grovsService = service,
            localCache = FakeLocalCache(numberOfOpens = 0),
            clipboard = clipboard,
            clipboardDomains = emptyList(),
        )
    }

    /** The barrier the manager installs around the flow: consent must still be current. */
    private fun consentBarrier(): () -> Boolean {
        val token = grovsContext.consent.tryAcquire(grovsContext.consent.currentConfiguration)!!
        return { grovsContext.consent.isCurrent(token) }
    }

    /** Withdraw and immediately re-grant: the flow's own token must stay dead regardless. */
    private fun revokeAndRegrant() {
        grovsContext.settings.sdkEnabled = false
        grovsContext.settings.sdkEnabled = true
    }

    @Test
    fun `L03 consent withdrawn during the focus wait stops the flow before any clipboard access`() = runTest {
        clipboard.onAwaitAccess = { revokeAndRegrant() }

        val outcome = handler.runFlow(appDetails, consentBarrier())

        assertEquals(ClipboardFlowOutcome.Superseded, outcome)
        assertEquals("the clipboard must not be described", 0, clipboard.describeCount)
        assertEquals("the clipboard must not be read", 0, clipboard.readCount)
        coVerify(exactly = 0) { service.payloadWithLinkFor(any()) }
        assertTrue("a cancelled flow is not a resolved one", handler.isPending)
    }

    @Test
    fun `L03 consent withdrawn during the description stops the flow before the read`() = runTest {
        clipboard.onDescribe = { revokeAndRegrant() }

        val outcome = handler.runFlow(appDetails, consentBarrier())

        assertEquals(ClipboardFlowOutcome.Superseded, outcome)
        assertEquals("the clipboard must not be read", 0, clipboard.readCount)
        coVerify(exactly = 0) { service.payloadWithLinkFor(any()) }
        assertTrue(handler.isPending)
    }

    @Test
    fun `L04 consent withdrawn after the read sends no match, clears nothing and stays armed`() = runTest {
        clipboard.onRead = { revokeAndRegrant() }

        val outcome = handler.runFlow(appDetails, consentBarrier())

        assertEquals(ClipboardFlowOutcome.Superseded, outcome)
        coVerify(exactly = 0) { service.payloadWithLinkFor(any()) }
        assertEquals("the user's clipboard must not be cleared", 0, clipboard.clearCount)
        assertTrue("cancellation is not a permanent clipboard decision", handler.isPending)
    }

    @Test
    fun `L04 consent withdrawn while the match is in flight applies none of its results`() = runTest {
        coEvery { service.payloadWithLinkFor(any()) } coAnswers {
            revokeAndRegrant()
            LSResult.Success(DeeplinkDetails(link = "https://demo.sqd.link/abc?gd=1", data = null, tracking = null))
        }

        val outcome = handler.runFlow(appDetails, consentBarrier())

        assertEquals(ClipboardFlowOutcome.Superseded, outcome)
        assertEquals("a revoked match must not clear the clipboard", 0, clipboard.clearCount)
        assertTrue("nor mark the flow resolved", handler.isPending)
    }

    @Test
    fun `L03 positive control - a consented flow reads, matches and clears`() = runTest {
        val outcome = handler.runFlow(appDetails, consentBarrier())

        assertTrue("expected a match, got $outcome", outcome is ClipboardFlowOutcome.Matched)
        assertEquals(1, clipboard.readCount)
        coVerify(exactly = 1) { service.payloadWithLinkFor(any()) }
        assertEquals("a real match clears the clipboard", 1, clipboard.clearCount)
        assertFalse("and ends the flow for this install", handler.isPending)
    }
}
