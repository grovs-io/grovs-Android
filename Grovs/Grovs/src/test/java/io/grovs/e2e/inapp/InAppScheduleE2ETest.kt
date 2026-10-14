package io.grovs.e2e.inapp

import io.grovs.Grovs
import io.grovs.e2e.contract.InAppBackend
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.event
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppScheduleE2ETest : InAppTestBase() {

    private val zone = TimeZone.getDefault()
    @After fun restoreZone() = TimeZone.setDefault(zone)

    private val control get() = InAppCampaigns.campaign().id("control").triggers(event("other")).build()
    private fun assertControlShows() { Grovs.track("other"); pumpUntil("control") { recorder.shown.contains("control") } }

    private fun hhmm(cal: Calendar) = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    /** Local wall clock now plus [minutes], in the given zone. */
    private fun localIn(zone: TimeZone, minutes: Int) = Calendar.getInstance(zone).apply { add(Calendar.MINUTE, minutes) }
    /** schedule `days` uses 1 = Monday ... 7 = Sunday (cases.json README). */
    private fun isoDay(cal: Calendar) = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    @Test fun `I17 a campaign shows only from start_at and before end_at`() {
        val now = System.currentTimeMillis()
        start(
            InAppCampaigns.campaign().id("future").schedule(InAppBackend.stamp(now + 3_600_000)).build(),
            InAppCampaigns.campaign().id("ended").triggers(event("e2")).schedule("2026-09-01T00:00:00Z", InAppBackend.stamp(now - 60_000)).build(),
            control,
        )
        Grovs.track("cart_viewed"); Grovs.track("e2"); settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        assertControlShows()
    }

    @Test fun `I17 start_at is checked against the server corrected time`() {
        val now = System.currentTimeMillis()
        start(
            InAppCampaigns.campaign().id("future").schedule(InAppBackend.stamp(now + 3_600_000)).build(),
            control,
        )
        Grovs.track("cart_viewed"); settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        assertControlShows()
        tapClose()
        serverAhead(2 * 3_600_000L)
        reopen()
        Grovs.track("cart_viewed"); pumpUntil("start_at passed on the server clock") { recorder.shown.contains("future") }
    }

    @Test fun `I16 a daily window includes from and excludes to`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val utc = TimeZone.getTimeZone("UTC")
        start(
            InAppCampaigns.campaign().id("open").schedule("2026-09-01T00:00:00Z", from = hhmm(localIn(utc, 0)), to = hhmm(localIn(utc, 120))).build(),
            InAppCampaigns.campaign().id("closed").triggers(event("e2")).schedule("2026-09-01T00:00:00Z", from = hhmm(localIn(utc, -120)), to = hhmm(localIn(utc, 0))).build(),
            control,
        )
        Grovs.track("cart_viewed"); pumpUntil("inside [from, to)") { recorder.shown == listOf("open") }
        tapClose()
        Grovs.track("e2"); settle(3_000)
        assertTrue("to is excluded", "closed" !in recorder.shown)
        assertControlShows()
    }

    @Test fun `I15 local time uses the device offset, including half hour zones`() {
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")   // +330 minutes
        val utc = TimeZone.getTimeZone("UTC")
        TimeZone.setDefault(kolkata)
        start(
            InAppCampaigns.campaign().id("local").schedule("2026-09-01T00:00:00Z", from = hhmm(localIn(kolkata, -5)), to = hhmm(localIn(kolkata, 60))).build(),
            InAppCampaigns.campaign().id("utcWindow").triggers(event("e2"))
                .schedule("2026-09-01T00:00:00Z", from = hhmm(localIn(utc, -5)), to = hhmm(localIn(utc, 60))).build(),
            control,
        )
        Grovs.track("cart_viewed"); pumpUntil("local window") { recorder.shown == listOf("local") }
        tapClose()
        Grovs.track("e2"); settle(3_000)
        assertTrue("a window in UTC clock time is 5.5 hours off", "utcWindow" !in recorder.shown)
        assertControlShows()
    }

    @Test fun `I16 days use 1 for Monday`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val today = isoDay(localIn(TimeZone.getTimeZone("UTC"), 0))
        val other = today % 7 + 1
        start(
            InAppCampaigns.campaign().id("today").schedule("2026-09-01T00:00:00Z", days = listOf(today)).build(),
            InAppCampaigns.campaign().id("tomorrow").triggers(event("e2")).schedule("2026-09-01T00:00:00Z", days = listOf(other)).build(),
            control,
        )
        Grovs.track("cart_viewed"); pumpUntil("today") { recorder.shown == listOf("today") }
        tapClose()
        Grovs.track("e2"); settle(3_000)
        assertTrue("other day does not show", "tomorrow" !in recorder.shown)
        assertControlShows()
    }
}
