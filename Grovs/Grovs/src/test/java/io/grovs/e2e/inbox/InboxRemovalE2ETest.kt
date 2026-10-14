package io.grovs.e2e.inbox

import android.os.Looper
import io.grovs.Grovs
import io.grovs.GrovsMessaging
import io.grovs.GrovsNotificationsListener
import io.grovs.handlers.GrovsManager
import io.grovs.e2e.TestActivity
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.PushPayloads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InboxRemovalE2ETest : ContractTestBase() {

    private val onMain = CopyOnWriteArrayList<Boolean>()
    private fun mark() { onMain += Looper.myLooper() == Looper.getMainLooper() }

    private fun eachState(check: (state: String) -> Unit) {
        check("not configured")
        configure(enabled = false)
        check("disabled")
        Grovs.setSDK(true)
        foreground()
        pumpUntil("authentication") { backend.count("authenticate") >= 1 }
        pump()
        check("authenticated")
    }

    @Test
    fun `B1 a full session never calls the inbox routes`() {
        authenticated()
        Grovs.track("checkout_done")
        GrovsMessaging.handleMessage(application, PushPayloads.remoteMessage(data = mapOf("linksquared" to "a", "grovs_notification_id" to "1")))
        Grovs.displayMessagesFragment {}
        Grovs.numberOfUnreadMessages(null) {}
        runBlocking { Grovs.numberOfUnreadMessages() }
        background()
        foreground()
        settle(60_000)

        assertEquals(GrovsManager.AuthenticationState.AUTHENTICATED, manager().authenticationState)
        assertTrue("tracked event reached the backend", contract.batchEvents().any { it.optString("event") == "checkout_done" || it.optString("event_name") == "checkout_done" })
        assertEquals(emptyList<String>(), contract.inboxCalls())
    }

    @Test
    fun `B2 displayMessagesFragment calls onDismissed once on main and not inline in every state`() = eachState { state ->
        onMain.clear()
        Grovs.displayMessagesFragment { mark() }
        assertEquals("$state: not inline", 0, onMain.size)
        pump()
        assertEquals("$state: once", listOf(true), onMain.toList())
    }

    @Test
    fun `B3 numberOfUnreadMessages yields zero once on main and not inline in every state`() = eachState { state ->
        val results = CopyOnWriteArrayList<Int?>()
        onMain.clear()
        Grovs.numberOfUnreadMessages(launcherOrNull()) { mark(); results += it }
        assertEquals("$state: not inline", 0, results.size)
        pump()
        assertEquals("$state: once", listOf<Int?>(0), results.toList())
        assertEquals(listOf(true), onMain.toList())
    }

    @Test
    fun `B3 numberOfUnreadMessages skips a destroyed owner`() {
        authenticated()
        val live = CopyOnWriteArrayList<Int?>()
        Grovs.numberOfUnreadMessages(launcher()) { live += it }
        pump()
        assertEquals(listOf<Int?>(0), live.toList())

        val owner = Robolectric.buildActivity(TestActivity::class.java).setup()
        owner.pause().stop().destroy()
        val results = CopyOnWriteArrayList<Int?>()

        Grovs.numberOfUnreadMessages(owner.get()) { results += it }
        pump()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `B4 the suspend numberOfUnreadMessages returns zero`() {
        assertEquals(0, runBlocking { Grovs.numberOfUnreadMessages() })
        authenticated()
        assertEquals(0, runBlocking { Grovs.numberOfUnreadMessages() })
    }

    @Test
    fun `B5 the automatic notifications listener is never called`() {
        val calls = CopyOnWriteArrayList<Boolean>()
        Grovs.setOnAutomaticNotificationsListener(object : GrovsNotificationsListener {
            override fun onAutomaticNotificationClosed(isLast: Boolean) { calls += isLast }
        })
        authenticated()
        background()
        foreground()
        settle(60_000)

        assertEquals(GrovsManager.AuthenticationState.AUTHENTICATED, manager().authenticationState)
        assertTrue(calls.isEmpty())
    }

    private fun launcherOrNull(): TestActivity? = runCatching { launcher() }.getOrNull()
}
