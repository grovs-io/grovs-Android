package io.grovs.handlers

import io.grovs.service.HttpStatusException
import io.grovs.service.IGrovsService
import io.grovs.storage.EventsStorage
import io.grovs.storage.PushOpen
import io.grovs.storage.PushOpenStorage
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushOpenReporterTest {

    private lateinit var grovsContext: GrovsContext
    private lateinit var service: IGrovsService
    private var authenticated = false
    private lateinit var reporter: PushOpenReporter

    @Before
    fun setUp() {
        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true
        service = mockk()
        coEvery { service.pushOpen(any(), any()) } returns LSResult.Success(true)
        reporter = newReporter()
    }

    private fun storage() = PushOpenStorage(RuntimeEnvironment.getApplication())

    private fun newReporter() = PushOpenReporter(service, grovsContext.consent, storage()) { authenticated }

    /** The process dies: memory is gone, the disk stays and consent starts a new generation. */
    private fun relaunch() {
        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true
        reporter = newReporter()
    }

    private fun token(): ConsentToken =
        grovsContext.consent.tryAcquire(grovsContext.consent.currentConfiguration)!!

    @Test
    fun `a tapped push reports its campaign`() = runTest {
        authenticated = true
        reporter.onTap("c1", "s1", token())
        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `an open before authentication is held and sent once authenticated`() = runTest {
        reporter.onTap("c1", "s1", token())
        coVerify(exactly = 0) { service.pushOpen(any(), any()) }

        authenticated = true
        reporter.reportPending(token())
        reporter.reportPending(token())

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `repeated taps on one campaign before authentication report once`() = runTest {
        val token = token()
        reporter.onTap("c1", "s1", token)
        reporter.onTap("c1", "s1", token)

        authenticated = true
        reporter.reportPending(token())

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `a held retry keeps the first tap's session when the campaign is tapped again`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } returnsMany listOf(LSResult.Error(IOException("down")), LSResult.Success(true))

        reporter.onTap("c1", "s1", token())
        reporter.onTap("c1", "s2", token())

        coVerify(exactly = 2) { service.pushOpen("c1", "s1") }
        coVerify(exactly = 0) { service.pushOpen("c1", "s2") }
    }

    @Test
    fun `a terminal status drops the open`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } returns LSResult.Error(HttpStatusException(404, "not found"))

        reporter.onTap("c1", "s1", token())
        reporter.reportPending(token())

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `a failing open is retried until it is reported, with no attempt cap`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } returnsMany List(5) { LSResult.Error(IOException("down")) } + LSResult.Success(true)

        reporter.onTap("c1", "s1", token())
        repeat(7) { reporter.reportPending(token()) }

        coVerify(exactly = 6) { service.pushOpen("c1", "s1") }
        assertTrue(storage().getAll().isEmpty())
    }

    @Test
    fun `a failed open answering after consent is withdrawn is not retried`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } coAnswers {
            grovsContext.consent.revoke()
            LSResult.Error(IOException("down"))
        }

        reporter.onTap("c1", "s1", token())
        grovsContext.consent.enable()
        reporter.reportPending(token())

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `consent toggled before a failed open answers is not retried`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } coAnswers {
            grovsContext.consent.revoke()
            grovsContext.consent.enable()
            LSResult.Error(IOException("down"))
        }

        reporter.onTap("c1", "s1", token())
        reporter.reportPending(token())

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `clear drops pending opens`() = runTest {
        reporter.onTap("c1", "s1", token())
        reporter.clear()

        authenticated = true
        reporter.reportPending(token())

        coVerify(exactly = 0) { service.pushOpen(any(), any()) }
    }

    @Test
    fun `an open held under withdrawn consent is never sent`() = runTest {
        reporter.onTap("c1", "s1", token())
        grovsContext.consent.revoke()
        grovsContext.consent.enable()

        authenticated = true
        reporter.reportPending(token())

        coVerify(exactly = 0) { service.pushOpen(any(), any()) }
    }

    @Test
    fun `a failed open survives the app closing and is sent after the relaunch`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } returns LSResult.Error(IOException("down"))
        reporter.onTap("c1", "s1", token())

        relaunch()
        coEvery { service.pushOpen("c1", "s1") } returns LSResult.Success(true)
        reporter.reportPending(token())

        coVerify(exactly = 2) { service.pushOpen("c1", "s1") }
        assertTrue(storage().getAll().isEmpty())
    }

    @Test
    fun `an open tapped before authentication survives the app closing`() = runTest {
        reporter.onTap("c1", "s1", token())

        relaunch()
        authenticated = true
        reporter.reportPending(token())

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `an open stays on disk while its report is in flight`() = runTest {
        authenticated = true
        var savedDuringSend = emptyList<PushOpen>()
        coEvery { service.pushOpen("c1", "s1") } coAnswers {
            savedDuringSend = storage().getAll()
            LSResult.Success(true)
        }

        reporter.onTap("c1", "s1", token())

        assertEquals(listOf("c1"), savedDuringSend.map { it.campaignId })
        assertTrue(storage().getAll().isEmpty())
    }

    @Test
    fun `a terminal status removes the open from disk`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } returns LSResult.Error(HttpStatusException(400, "bad"))

        reporter.onTap("c1", "s1", token())

        assertTrue(storage().getAll().isEmpty())
    }

    @Test
    fun `clear removes saved opens so a relaunch sends nothing`() = runTest {
        reporter.onTap("c1", "s1", token())
        reporter.clear()

        relaunch()
        authenticated = true
        reporter.reportPending(token())

        coVerify(exactly = 0) { service.pushOpen(any(), any()) }
    }

    @Test
    fun `a tap under withdrawn consent is not saved`() = runTest {
        val stale = token()
        grovsContext.consent.revoke()
        reporter.onTap("c1", "s1", stale)

        assertTrue(storage().getAll().isEmpty())
    }

    @Test
    fun `a saved open older than the event age limit is not sent`() = runTest {
        val old = InstantCompat.now().minusMillis(EventsStorage.MAX_EVENT_AGE_MS + 60_000)
        storage().addIfAbsent(PushOpen("c1", "s1", old))

        authenticated = true
        reporter.reportPending(token())

        coVerify(exactly = 0) { service.pushOpen(any(), any()) }
    }

    @Test
    fun `saved opens of different campaigns are all sent, oldest first`() = runTest {
        reporter.onTap("c1", "s1", token())
        reporter.onTap("c2", "s1", token())

        relaunch()
        authenticated = true
        reporter.reportPending(token())

        coVerifyOrder {
            service.pushOpen("c1", "s1")
            service.pushOpen("c2", "s1")
        }
    }
}
