package io.grovs.e2e

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import io.grovs.handlers.GrovsManager
import io.grovs.handlers.GrovsManager.AuthenticationFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineAuthenticationE2ETest : DrivenBackendTestBase() {

    private val connectivity
        get() = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Robolectric's default is a connected mobile network. Clearing it leaves no default network. */
    private fun goOffline(): NetworkInfo? {
        val online = connectivity.activeNetworkInfo
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertNull("the shadow reports no default network", connectivity.activeNetwork)
        return online
    }

    @Test
    fun `an offline launch sends nothing and is recorded as offline`() {
        backend.respond(authenticate, authOk)
        goOffline()
        configure()
        foreground()
        pumpUntil("the launch to finish") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED ||
                manager().lastAuthenticationFailure != null
        }

        assertEquals("no device lookup while offline", 0, backend.count(device))
        assertEquals("no login while offline", 0, backend.count(authenticate))
        assertEquals(AuthenticationFailure.OFFLINE, manager().lastAuthenticationFailure)
    }

    @Test
    fun `going offline during the device lookup does not spend the login budget`() {
        backend.fail(device)
        backend.respond(authenticate, authOk)
        configure()
        pumpUntil("the first device lookup") { backend.count(device) >= 1 }
        goOffline()
        advance(20_000)

        assertEquals("login is skipped once the phone is offline", 0, backend.count(authenticate))
        assertEquals(AuthenticationFailure.OFFLINE, manager().lastAuthenticationFailure)
        assertEquals(GrovsManager.AuthenticationState.UNAUTHENTICATED, manager().authenticationState)
    }

    @Test
    fun `a failed device lookup while online still goes on to log in`() {
        backend.fail(device)
        backend.respond(authenticate, authOk)
        configure()
        advanceUntil("login after the lookup budget") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
    }
}
