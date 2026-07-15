package io.grovs.handlers

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import java.util.Collections
import java.util.WeakHashMap

/**
 * Bridges a Jetpack [NavController] to screen tracking by observing destination changes. This is the
 * opt-in path for navigation that the lifecycle-based auto-tracker cannot see well — notably
 * Navigation-Compose and route-based graphs, where there are no Fragments to resume.
 *
 * Leak safety: the listener is held by the [NavController], and this object keeps only a *weak*
 * reference to the controller (to dedupe repeat [attach] calls). So the listener lives and dies with
 * the controller — when the hosting Activity/Fragment is destroyed, both are collected. Nothing here
 * keeps a destroyed controller alive.
 */
internal object NavigationScreenTracker {

    // Weak keys: prevents double-registering on the same controller without pinning it in memory.
    private val tracked: MutableSet<NavController> =
        Collections.newSetFromMap(WeakHashMap<NavController, Boolean>())

    /**
     * Registers a destination listener that reports each destination as a screen. The listener fires
     * immediately with the current destination. Returns false (and does nothing) if this controller
     * is already tracked, so repeat calls cannot double-register.
     */
    @Synchronized
    fun attach(navController: NavController, onScreen: (String) -> Unit): Boolean {
        if (!tracked.add(navController)) return false
        navController.addOnDestinationChangedListener { _, destination, _ ->
            onScreen(screenNameFor(destination))
        }
        return true
    }

    /**
     * Derives a screen name from a destination, preferring the most stable/meaningful identifier:
     * the route (Compose / route-based graphs), then the `android:label`, then the resource-derived
     * display name as a last resort (never blank).
     */
    fun screenNameFor(destination: NavDestination): String =
        destination.route
            ?: destination.label?.toString()?.takeIf { it.isNotBlank() }
            ?: destination.displayName
}
