package io.grovs.e2e.push

import io.grovs.e2e.contract.BackendContract
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.PushPayloads
import io.grovs.e2e.contract.PushPayloads.tapIntent
import io.grovs.handlers.GrovsManager
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushDedupeE2ETest : ContractTestBase() {

    private fun tap(messageId: String = "0:m1", campaign: String = PushPayloads.CAMPAIGN) =
        tapIntent(PushPayloads.data(campaign = campaign, link = null), messageId)

    @Test
    fun `P27 the same launcher intent seen again reports and delivers once`() {
        authenticated()
        foreground(tap())
        advance(5_000)
        foreground()
        foreground()
        advance(5_000)

        assertEquals(1, pushOpens())
        assertEquals(1, deepLinks.delivered.size)

        // Control: a push with a new id is still reported in the same state.
        foreground(tap("0:control"))
        advance(5_000)
        assertEquals(2, pushOpens())
    }

    @Test
    fun `P27 a new intent with a handled message id is ignored`() {
        authenticated()
        foreground(tap("0:same"))
        advance(5_000)
        foreground(tap("0:same"))
        advance(5_000)

        assertEquals(1, pushOpens())
        assertEquals(1, deepLinks.delivered.size)

        foreground(tap("0:control"))
        advance(5_000)
        assertEquals(2, pushOpens())
    }

    @Test
    fun `P27 a handled push redelivered after process death is ignored`() {
        authenticated()
        foreground(tap("0:kept"))
        advance(5_000)

        simulateProcessDeath()
        backend.phase = "after-restart"
        configure()
        foreground(tap("0:kept"))
        pumpUntil("authentication after the restart") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
        advance(10_000)

        assertEquals(0, pushOpens("after-restart"))
        assertEquals(1, deepLinks.delivered.size)

        foreground(tap("0:control"))
        advance(5_000)
        assertEquals(1, pushOpens("after-restart"))
    }

    @Test
    fun `P27 recreation while the open is in flight sends one open`() {
        val gate = CountDownLatch(1)
        backend.answer("push_open") { gate.await(10, TimeUnit.SECONDS); BackendContract.json(200, BackendContract.PUSH_OPEN_RECORDED) }
        authenticated()
        val intent = tap("0:rot")
        foreground(intent)
        pumpUntil("the open in flight") { pushOpens() >= 1 }

        // A configuration change: the same intent arrives in a new Activity instance.
        Robolectric.buildActivity(io.grovs.e2e.TestActivity::class.java, intent).setup()
        gate.countDown()
        advance(10_000)

        assertEquals(1, pushOpens())
        assertEquals(1, deepLinks.delivered.size)
    }

    private fun handle51Pushes() {
        contract.knownPushCampaigns = (0..50).map { "c$it" }.toSet()
        authenticated()
        (0..50).forEach { foreground(tap("0:id$it", "c$it")); pump() }
        advance(10_000)
        assertEquals(51, pushOpens())
    }

    @Test
    fun `P28 the newest handled push is still remembered after 51 pushes`() {
        handle51Pushes()

        backend.phase = "replay"
        foreground(tap("0:id50", "c50"))
        advance(5_000)
        assertEquals("the newest is still remembered", 0, pushOpens("replay"))

        foreground(tap("0:control", "c1"))
        advance(5_000)
        assertEquals(1, pushOpens("replay"))
    }

    @Test
    @Ignore("F-2: the oldest handled push id is remembered for the whole process, beyond the last 50")
    fun `P28 only the last 50 handled pushes are remembered`() {
        handle51Pushes()

        backend.phase = "replay"
        foreground(tap("0:id0", "c0"))
        advance(5_000)
        assertEquals("the oldest was forgotten", 52, deepLinks.delivered.size)
        assertEquals("the oldest is reported again", 1, pushOpens("replay"))
    }

    @Test
    fun `P28 after a restart only the last 50 handled pushes are remembered`() {
        handle51Pushes()

        simulateProcessDeath()
        backend.phase = "after-restart"
        configure()
        foreground()
        pumpUntil("authentication after the restart") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
        foreground(tap("0:id50", "c50"))
        advance(5_000)
        assertEquals("the newest is still remembered", 0, pushOpens("after-restart"))

        foreground(tap("0:id0", "c0"))
        advance(5_000)
        assertEquals("the oldest was forgotten", 1, pushOpens("after-restart"))
    }
}
