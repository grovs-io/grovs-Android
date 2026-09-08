package io.grovs.handlers

import io.grovs.FakeClipboard
import io.grovs.FakeLocalCache
import io.grovs.TestFixtures
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.LogLevel
import io.grovs.service.IGrovsService
import io.grovs.utils.ClipDescriptionResult
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ClipboardHandlerTest {

    private lateinit var service: IGrovsService
    private lateinit var cache: FakeLocalCache
    private lateinit var clipboard: FakeClipboard

    private val grovsLink = "https://demo.sqd.link/abc?gd=device123"
    private val appDetails = TestFixtures.createAppDetails()

    @Before
    fun setUp() {
        DebugLogger.instance.logLevel = LogLevel.INFO
        service = mockk(relaxed = true)
        cache = FakeLocalCache(numberOfOpens = 0)
        clipboard = FakeClipboard()
        coEvery { service.clipboardStatus() } returns LSResult.Success(true)
        coEvery { service.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = grovsLink, data = null, tracking = null))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun handler(domains: List<String> = emptyList()) =
        ClipboardHandler(grovsService = service, localCache = cache, clipboard = clipboard, clipboardDomains = domains)

    // ==================== Arming ====================

    @Test
    fun `fresh install arms the flow`() {
        cache.numberOfOpens = 0
        val h = handler()
        assertTrue(h.isPending)
        assertTrue(cache.clipboardFlowPending)
    }

    @Test
    fun `existing install never arms the flow`() {
        cache.numberOfOpens = 3
        val h = handler()
        assertFalse(h.isPending)
        assertFalse(cache.clipboardFlowPending)
    }

    @Test
    fun `pending flag persists across a relaunch`() {
        handler()                       // fresh install arms it
        cache.numberOfOpens = 1         // opens counter moved on
        val relaunched = handler()      // same cache, new instance
        assertTrue(relaunched.isPending)
    }

    @Test
    fun `markResolved disarms the flow`() {
        val h = handler()
        h.markResolved()
        assertFalse(h.isPending)
        assertFalse(cache.clipboardFlowPending)
    }

    // ==================== Host check ====================

    @Test
    fun `host check accepts Grovs hosts with gd and rejects everything else`() {
        val h = handler()
        assertTrue(h.isGrovsLink("https://demo.sqd.link/abc?gd=1"))
        assertTrue(h.isGrovsLink("https://demo.test-sqd.link/abc?gd=1"))
        assertTrue(h.isGrovsLink("https://demo.grovs.link/abc?gd=1"))
        assertTrue(h.isGrovsLink("HTTPS://DEMO.SQD.LINK/abc?x=1&gd=1"))
        assertFalse("http rejected", h.isGrovsLink("http://demo.sqd.link/abc?gd=1"))
        assertFalse("missing gd", h.isGrovsLink("https://demo.sqd.link/abc"))
        assertFalse("empty gd", h.isGrovsLink("https://demo.sqd.link/abc?gd="))
        assertFalse("lookalike host", h.isGrovsLink("https://sqd.link.evil.com/abc?gd=1"))
        assertFalse("bare suffix host", h.isGrovsLink("https://sqd.link/abc?gd=1"))
        assertFalse("foreign host", h.isGrovsLink("https://example.com/abc?gd=1"))
        assertFalse("not a url", h.isGrovsLink("hello world"))
    }

    @Test
    fun `host check accepts configured domains exactly and as subdomains`() {
        val h = handler(domains = listOf("links.example.com"))
        assertTrue(h.isGrovsLink("https://links.example.com/abc?gd=1"))
        assertTrue(h.isGrovsLink("https://go.links.example.com/abc?gd=1"))
        assertFalse(h.isGrovsLink("https://example.com/abc?gd=1"))
        assertFalse(h.isGrovsLink("https://links.example.com.evil.com/abc?gd=1"))
    }

    @Test
    fun `host check without custom domains accepts only the built-in suffixes`() {
        val h = handler(domains = emptyList())
        assertTrue(h.isGrovsLink("https://demo.sqd.link/abc?gd=1"))
        assertFalse(h.isGrovsLink("https://links.example.com/abc?gd=1"))
    }

    @Test
    fun `normalizeDomains strips scheme path whitespace and dots and drops tld-only entries`() {
        val normalized = ClipboardHandler.normalizeDomains(
            listOf(" https://Links.Example.com/path ", ".go.example.com.", "com", "", "plain.host")
        )
        assertEquals(listOf("links.example.com", "go.example.com", "plain.host"), normalized)
    }

    @Test
    fun `normalizeDomains of null is empty`() {
        assertEquals(emptyList<String>(), ClipboardHandler.normalizeDomains(null))
    }

    @Test
    fun `normalizeDomains does not treat a mid-string scheme as the host`() {
        val normalized = ClipboardHandler.normalizeDomains(
            listOf("notaurl/foo?x=https://attacker.com/bar", "https://links.example.com/path")
        )
        assertEquals(listOf("links.example.com"), normalized)
    }

    // ==================== Flow ====================

    @Test
    fun `inactive project short-circuits without touching the clipboard`() = runTest {
        coEvery { service.clipboardStatus() } returns LSResult.Success(false)
        val h = handler()

        val outcome = h.runFlow(appDetails)

        assertEquals(ClipboardFlowOutcome.Resolved, outcome)
        assertFalse(h.isPending)
        assertEquals(0, clipboard.awaitAccessCount)
        assertEquals(0, clipboard.describeCount)
        assertEquals(0, clipboard.readCount)
    }

    @Test
    fun `status transport failure keeps the flow armed`() = runTest {
        coEvery { service.clipboardStatus() } returns LSResult.Error(java.io.IOException("down"))
        val h = handler()

        val outcome = h.runFlow(appDetails)

        assertEquals(ClipboardFlowOutcome.Retry, outcome)
        assertTrue(h.isPending)
        assertEquals(0, clipboard.readCount)
    }

    @Test
    fun `focus timeout keeps the flow armed and never reads`() = runTest {
        clipboard.accessGranted = false
        val h = handler()

        val outcome = h.runFlow(appDetails)

        assertEquals(ClipboardFlowOutcome.Retry, outcome)
        assertTrue(h.isPending)
        assertEquals(0, clipboard.describeCount)
        assertEquals(0, clipboard.readCount)
    }

    @Test
    fun `a throwing awaitAccess keeps the flow armed and never reads`() = runTest {
        clipboard.awaitAccessFailure = IllegalStateException("no decor view")
        val h = handler()

        val outcome = h.runFlow(appDetails)

        assertEquals(ClipboardFlowOutcome.Retry, outcome)
        assertTrue(h.isPending)
        assertEquals(0, clipboard.describeCount)
        assertEquals(0, clipboard.readCount)
    }

    @Test
    fun `no probable web url short-circuits without reading`() = runTest {
        clipboard.description = ClipDescriptionResult.NOT_URL
        val h = handler()

        val outcome = h.runFlow(appDetails)

        assertEquals(ClipboardFlowOutcome.Resolved, outcome)
        assertFalse(h.isPending)
        assertEquals(0, clipboard.readCount)
    }

    @Test
    fun `empty clipboard short-circuits without reading`() = runTest {
        clipboard.description = ClipDescriptionResult.NO_CONTENT
        val h = handler()

        assertEquals(ClipboardFlowOutcome.Resolved, h.runFlow(appDetails))
        assertEquals(0, clipboard.readCount)
    }

    @Test
    fun `inaccessible description keeps the flow armed and never reads`() = runTest {
        clipboard.description = ClipDescriptionResult.INACCESSIBLE
        val h = handler()

        assertEquals(ClipboardFlowOutcome.Retry, h.runFlow(appDetails))
        assertTrue(h.isPending)
        assertEquals(0, clipboard.readCount)
    }

    @Test
    fun `null read after focus keeps the flow armed`() = runTest {
        clipboard.text = null
        val h = handler()

        assertEquals(ClipboardFlowOutcome.Retry, h.runFlow(appDetails))
        assertTrue(h.isPending)
        assertEquals(1, clipboard.readCount)
        coVerify(exactly = 0) { service.payloadWithLinkFor(any()) }
    }

    @Test
    fun `a throwing clipboard maps to Retry`() = runTest {
        clipboard.failure = SecurityException("no clipboard for you")
        val h = handler()

        assertEquals(ClipboardFlowOutcome.Retry, h.runFlow(appDetails))
        assertTrue(h.isPending)
        coVerify(exactly = 0) { service.payloadWithLinkFor(any()) }
    }

    @Test
    fun `foreign url is never sent and the clipboard is untouched`() = runTest {
        clipboard.text = "https://docs.google.com/secret?gd=1"
        val h = handler()

        val outcome = h.runFlow(appDetails)

        assertEquals(ClipboardFlowOutcome.Resolved, outcome)
        assertFalse(h.isPending)
        assertEquals(0, clipboard.clearCount)
        coVerify(exactly = 0) { service.payloadWithLinkFor(any()) }
    }

    @Test
    fun `grovs link is sent verbatim and the clipboard is cleared on match`() = runTest {
        clipboard.text = grovsLink
        val h = handler()

        val outcome = h.runFlow(appDetails)

        assertTrue(outcome is ClipboardFlowOutcome.Matched)
        assertEquals(grovsLink, (outcome as ClipboardFlowOutcome.Matched).clipboardUrl)
        assertEquals(grovsLink, outcome.details.link)
        assertFalse(h.isPending)
        assertEquals(1, clipboard.clearCount)
        coVerify { service.payloadWithLinkFor(match { it.url == grovsLink }) }
        // The caller's AppDetails must not be mutated: runFlow sends a copy, per spec step 9.
        assertEquals(null, appDetails.url)
    }

    @Test
    fun `no match leaves the clipboard alone and clears the flag`() = runTest {
        clipboard.text = grovsLink
        coEvery { service.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(null, null, null))
        val h = handler()

        assertEquals(ClipboardFlowOutcome.Resolved, h.runFlow(appDetails))
        assertFalse(h.isPending)
        assertEquals(0, clipboard.clearCount)
    }

    @Test
    fun `match transport failure keeps the flow armed and the retry skips the second read`() = runTest {
        clipboard.text = grovsLink
        coEvery { service.payloadWithLinkFor(any()) } returns LSResult.Error(java.io.IOException("down"))
        val h = handler()

        assertEquals(ClipboardFlowOutcome.Retry, h.runFlow(appDetails))
        assertTrue(h.isPending)
        assertEquals(1, clipboard.readCount)

        coEvery { service.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(link = grovsLink, data = null, tracking = null))
        val second = h.runFlow(appDetails)

        assertTrue(second is ClipboardFlowOutcome.Matched)
        assertEquals("second run must not read the clipboard again", 1, clipboard.readCount)
        assertEquals(1, clipboard.clearCount)
    }

    @Test
    fun `the flow runs only once after a terminal outcome`() = runTest {
        clipboard.text = grovsLink
        val h = handler()

        assertTrue(h.runFlow(appDetails) is ClipboardFlowOutcome.Matched)
        val second = h.runFlow(appDetails)

        assertEquals(ClipboardFlowOutcome.Resolved, second)
        assertEquals(1, clipboard.readCount)
        coVerify(exactly = 1) { service.clipboardStatus() }
    }

    @Test
    fun `re-entry while running returns AlreadyRunning`() = runTest {
        val gate = CompletableDeferred<LSResult<Boolean>>()
        coEvery { service.clipboardStatus() } coAnswers { gate.await() }
        clipboard.text = grovsLink
        val h = handler()

        val first = async { h.runFlow(appDetails) }
        advanceUntilIdle()                         // first run is parked on the status call
        val second = h.runFlow(appDetails)
        assertEquals(ClipboardFlowOutcome.AlreadyRunning, second)

        gate.complete(LSResult.Success(true))
        assertTrue(first.await() is ClipboardFlowOutcome.Matched)
        assertEquals(1, clipboard.readCount)
    }

    @Test
    fun `markResolved mid-flight aborts before the clipboard read`() = runTest {
        val gate = CompletableDeferred<LSResult<Boolean>>()
        coEvery { service.clipboardStatus() } coAnswers { gate.await() }
        clipboard.text = grovsLink
        val h = handler()

        val run = async { h.runFlow(appDetails) }
        advanceUntilIdle()
        h.markResolved()                           // a fingerprint hit landed elsewhere
        gate.complete(LSResult.Success(true))

        assertEquals(ClipboardFlowOutcome.Retry, run.await())
        assertEquals(0, clipboard.readCount)
        assertFalse(h.isPending)
    }

    @Test
    fun `match landing after external resolve does not clear the clipboard`() = runTest {
        val gate = CompletableDeferred<LSResult<DeeplinkDetails>>()
        coEvery { service.payloadWithLinkFor(any()) } coAnswers { gate.await() }
        clipboard.text = grovsLink
        val h = handler()

        val run = async { h.runFlow(appDetails) }
        advanceUntilIdle()                         // parked on the match call, clipboard already read
        h.markResolved()
        gate.complete(LSResult.Success(DeeplinkDetails(link = grovsLink, data = null, tracking = null)))

        assertEquals(ClipboardFlowOutcome.Retry, run.await())
        assertEquals(0, clipboard.clearCount)
    }
}
