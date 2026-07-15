package io.grovs.handlers

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NavigationScreenTrackerTest {

    private fun destination(
        route: String? = null,
        label: CharSequence? = null,
    ): NavDestination = NavDestination("test").apply {
        this.route = route
        this.label = label
    }

    @Test
    fun `screenNameFor prefers route over label`() {
        val name = NavigationScreenTracker.screenNameFor(
            destination(route = "home", label = "Home Label")
        )
        assertEquals("home", name)
    }

    @Test
    fun `screenNameFor falls back to label when route is absent`() {
        val name = NavigationScreenTracker.screenNameFor(
            destination(route = null, label = "Home Label")
        )
        assertEquals("Home Label", name)
    }

    @Test
    fun `screenNameFor ignores blank label and yields a non-blank name`() {
        val name = NavigationScreenTracker.screenNameFor(
            destination(route = null, label = "   ")
        )
        assertTrue(name.isNotBlank())
        assertNotEquals("   ", name)
    }

    @Test
    fun `attach registers once and is idempotent for the same controller`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val navController = NavController(context)

        assertTrue(NavigationScreenTracker.attach(navController) {})
        assertFalse(NavigationScreenTracker.attach(navController) {})
    }
}
