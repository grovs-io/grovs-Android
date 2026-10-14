package io.grovs.handlers

import io.grovs.service.HttpStatusException
import io.grovs.service.IGrovsService
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        reporter = PushOpenReporter(service, grovsContext.consent) { authenticated }
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
        reporter.reportPending()
        reporter.reportPending()

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `repeated taps on one campaign before authentication report once`() = runTest {
        val token = token()
        reporter.onTap("c1", "s1", token)
        reporter.onTap("c1", "s1", token)

        authenticated = true
        reporter.reportPending()

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
        reporter.reportPending()

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `a failing open is retried and dropped after three attempts`() = runTest {
        authenticated = true
        coEvery { service.pushOpen("c1", "s1") } returns LSResult.Error(IOException("down"))

        reporter.onTap("c1", "s1", token())
        repeat(5) { reporter.reportPending() }

        coVerify(exactly = PushOpenReporter.MAX_ATTEMPTS) { service.pushOpen("c1", "s1") }
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
        reporter.reportPending()

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
        reporter.reportPending()

        coVerify(exactly = 1) { service.pushOpen("c1", "s1") }
    }

    @Test
    fun `clear drops pending opens`() = runTest {
        reporter.onTap("c1", "s1", token())
        reporter.clear()

        authenticated = true
        reporter.reportPending()

        coVerify(exactly = 0) { service.pushOpen(any(), any()) }
    }

    @Test
    fun `an open held under withdrawn consent is never sent`() = runTest {
        reporter.onTap("c1", "s1", token())
        grovsContext.consent.revoke()
        grovsContext.consent.enable()

        authenticated = true
        reporter.reportPending()

        coVerify(exactly = 0) { service.pushOpen(any(), any()) }
    }
}
