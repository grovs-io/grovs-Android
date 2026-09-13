package io.grovs.e2e

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkInfo
import io.grovs.handlers.GrovsManager
import io.grovs.handlers.GrovsManager.AuthenticationFailure
import io.grovs.service.GrovsService
import io.grovs.utils.NetworkMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkRecoveryE2ETest : DrivenBackendTestBase() {

    private val connectivity
        get() = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var onlineInfo: NetworkInfo? = null

    private fun goOffline() {
        onlineInfo = connectivity.activeNetworkInfo
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertNull("the shadow reports no default network", connectivity.activeNetwork)
    }

    /** What Android does when a network appears. The shadow never calls callbacks on its own. */
    private fun networkReturns() {
        shadowOf(connectivity).setActiveNetworkInfo(onlineInfo)
        val network = connectivity.activeNetwork!!
        shadowOf(connectivity).networkCallbacks.forEach { it.onAvailable(network) }
        pump()
    }

    @Test
    fun `the SDK logs in as soon as the network comes back`() {
        backend.respond(authenticate, authOk)
        goOffline()
        configure()
        foreground()
        pumpUntil("the offline launch to be recorded") {
            manager().lastAuthenticationFailure == AuthenticationFailure.OFFLINE
        }

        networkReturns()

        // pumpUntil never moves virtual time, so the 10s timer cannot be what logs in here.
        pumpUntil("login right after the network returns") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
    }

    @Test
    fun `a new network does not retry a backend outage`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        foreground()
        pumpUntil("the launch to send its first attempt") { backend.count(authenticate) >= 1 }
        advance(20_000)
        val afterLaunch = backend.count(authenticate)
        assertEquals(GrovsService.MAX_ATTEMPTS.toInt(), afterLaunch)

        onlineInfo = connectivity.activeNetworkInfo
        networkReturns()
        pump()

        assertEquals("a network change does not fix a backend", afterLaunch, backend.count(authenticate))
    }

    @Test
    fun `the network coming back in the background does nothing`() {
        backend.respond(authenticate, authOk)
        goOffline()
        configure()
        pumpUntil("the offline launch to be recorded") {
            manager().lastAuthenticationFailure == AuthenticationFailure.OFFLINE
        }

        networkReturns()

        assertEquals("never opened, so no request", 0, backend.count(authenticate))
    }

    @Test
    fun `a repeated configure keeps exactly one network callback`() {
        configure()
        configure()
        configure()
        pump()

        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)
    }

    @Test
    @Config(sdk = [23])
    fun `old Android runs without a network callback and still logs in`() {
        backend.respond(authenticate, authOk)
        configure()
        pumpUntil("login") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }

        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    @Test
    fun `no connectivity service means online and no crash`() {
        val noService = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val monitor = NetworkMonitor(noService) {}

        monitor.start()
        assertTrue(monitor.isOnline())
        monitor.stop()
    }
}
