package io.grovs.handlers

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.grovs.FakeClipboard
import io.grovs.FakeLocalCache
import io.grovs.TestFixtures
import io.grovs.model.DeeplinkDetails
import io.grovs.service.HttpStatusException
import io.grovs.service.IGrovsService
import io.grovs.service.RetryableHttpException
import io.grovs.storage.CustomEventsStorage
import io.grovs.storage.EventsStorage
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** What the manager tells the facade about a tapped link, and whether the intent stays unconsumed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IntentOutcomeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val tapped = "https://demo.sqd.link/tapped"
    private val resolved = DeeplinkDetails(tapped, mapOf("k" to "v" as Object), null)

    private inner class Rig {
        val deadlineClock = TestScope()
        val service = mockk<IGrovsService>(relaxed = true)
        val context = GrovsContext().also { it.markAuthenticated("device", it.consent.currentConfiguration) }
        val cache = FakeLocalCache(numberOfOpens = 2)
        val events = EventsManager(app, context, "test", service, EventsStorage(app), cache).also {
            it.firstRequestTime = InstantCompat.now().minusMillis(20_000)
        }
        val custom = CustomEventsManager(app, context, service, CustomEventsStorage(app), startFlushTimer = false)
        val manager: GrovsManager

        init {
            val helper = mockk<IAppDetailsHelper>(relaxed = true)
            coEvery { helper.toAppDetails() } answers { TestFixtures.createAppDetails() }
            coEvery { service.payloadFor(any()) } returns LSResult.Success(DeeplinkDetails(null, null, null))
            coEvery { service.payloadWithLinkFor(any()) } returns LSResult.Success(resolved)
            coEvery { service.clipboardStatus() } returns LSResult.Success(false)
            manager = GrovsManager(app, app, context, "test", service, events, helper, custom,
                clipboardHandler = ClipboardHandler(service, cache, FakeClipboard(text = ""), emptyList()))
            manager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
            manager.attributionScope = deadlineClock.backgroundScope
        }
    }

    private fun tap(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(tapped))

    @Test
    fun `a resolved tap reports its link and details`() = runTest {
        val rig = Rig()
        val outcome = rig.manager.handleIntent(tap(), delayEvents = false)
        assertEquals(tapped, outcome.tappedLink)
        assertEquals(tapped, outcome.details?.link)
        assertNull(outcome.failure)
    }

    @Test
    fun `an intent without a link reports no tapped link`() = runTest {
        val rig = Rig()
        val outcome = rig.manager.handleIntent(Intent(), delayEvents = false)
        assertNull(outcome.tappedLink)
        assertNull(outcome.failure)
    }

    @Test
    fun `a 5xx is retryable and leaves the intent unconsumed`() = runTest {
        val rig = Rig()
        coEvery { rig.service.payloadWithLinkFor(any()) } returns
            LSResult.Error(RetryableHttpException(503, "Fetching payload - failed (503)."))
        val intent = tap()

        val first = rig.manager.handleIntent(intent, delayEvents = false, cacheIntent = true)
        assertEquals(tapped, first.tappedLink)
        assertEquals(RequestFailure.RETRYABLE, first.failure)
        assertNull(first.details)

        // The same intent object again is a fresh tapped-link lookup, not a repeated intent.
        coEvery { rig.service.payloadWithLinkFor(any()) } returns LSResult.Success(resolved)
        val second = rig.manager.handleIntent(intent, delayEvents = false, cacheIntent = true)
        assertEquals(tapped, second.details?.link)
        coVerify(exactly = 2) { rig.service.payloadWithLinkFor(any()) }
        coVerify(exactly = 0) { rig.service.payloadFor(any()) }
    }

    @Test
    fun `a transport failure is retryable`() = runTest {
        val rig = Rig()
        coEvery { rig.service.payloadWithLinkFor(any()) } returns LSResult.Error(IOException("reset"))
        val outcome = rig.manager.handleIntent(tap(), delayEvents = false)
        assertEquals(RequestFailure.RETRYABLE, outcome.failure)
    }

    @Test
    fun `a 4xx is final and consumes the intent`() = runTest {
        val rig = Rig()
        coEvery { rig.service.payloadWithLinkFor(any()) } returns
            LSResult.Error(HttpStatusException(404, "Fetching payload - failed (404)."))
        val intent = tap()

        val first = rig.manager.handleIntent(intent, delayEvents = false, cacheIntent = true)
        assertEquals(tapped, first.tappedLink)
        assertEquals(RequestFailure.REJECTED, first.failure)

        // Consumed: the same intent is now a repeated one and falls back to the fingerprint lookup.
        val second = rig.manager.handleIntent(intent, delayEvents = false, cacheIntent = true)
        assertNull(second.tappedLink)
        coVerify(exactly = 1) { rig.service.payloadWithLinkFor(any()) }
        coVerify(exactly = 1) { rig.service.payloadFor(any()) }
    }

    @Test
    fun `a tapped link superseded by a newer commit reports no tapped link`() = runTest {
        val rig = Rig()
        val older = "https://demo.sqd.link/older"
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadWithLinkFor(match { it.url == older }) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(older, null, null))
        }
        val first = async {
            rig.manager.handleIntent(Intent(Intent.ACTION_VIEW, Uri.parse(older)), delayEvents = false, sequence = 1)
        }
        started.await()
        val second = rig.manager.handleIntent(tap(), delayEvents = false, sequence = 2)
        assertEquals(tapped, second.details?.link)
        gate.complete(Unit)

        val outcome = first.await()
        assertNull("stale to the newer commit", outcome.details)
        assertNull("must never touch the pending slot", outcome.tappedLink)
        assertNull(outcome.failure)
    }

    @Test
    fun `a replayed lookup yields to a newer tap and reports nothing`() = runTest {
        val rig = Rig()
        val older = "https://demo.sqd.link/older"
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadWithLinkFor(match { it.url == older }) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(older, null, null))
        }
        coEvery { rig.service.payloadWithLinkFor(match { it.url == tapped }) } returns
            LSResult.Error(RetryableHttpException(503, "Fetching payload - failed (503)."))

        val replay = async {
            rig.manager.handleIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse(older)),
                delayEvents = false,
                sequence = 1,
                yieldToNewerTaps = true,
            )
        }
        started.await()
        // The newer tap starts, and fails, while the replay is still waiting on the backend.
        val newer = rig.manager.handleIntent(tap(), delayEvents = false, sequence = 2)
        assertEquals(tapped, newer.tappedLink)
        assertEquals(RequestFailure.RETRYABLE, newer.failure)
        gate.complete(Unit)

        val outcome = replay.await()
        assertNull("the replay yields even though its answer was a success", outcome.details)
        assertNull(outcome.tappedLink)
        assertEquals(2L, rig.manager.latestTapSequence)
    }
}
