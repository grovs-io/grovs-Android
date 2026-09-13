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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `a spent budget on a 5xx is retryable and leaves the intent unconsumed`() = runTest {
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
}
