package io.grovs.e2e.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.dialog
import androidx.navigation.fragment.fragment

/**
 * Fixtures for Setup 01 — Single-Activity + Jetpack Navigation Component (fragment destinations).
 * The FragmentNavigator replaces fragments on each navigation, so every navigation drives a real
 * onFragmentResumed that the SDK auto-tracks.
 */

/** Base fragment that renders a visible FrameLayout so VisibleFragmentResolver treats it as eligible. */
open class S01BaseFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

class S01HomeFragment : S01BaseFragment()
class S01DetailFragment : S01BaseFragment()
class S01SettingsFragment : S01BaseFragment()
class S01LoginFragment : S01BaseFragment()

/** Dialog destination. DialogFragmentNavigator shows this via the NavHost's child FragmentManager. */
class S01DialogDestFragment : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

object S01Routes {
    const val HOME = "home"
    const val DETAIL = "detail"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val DIALOG = "dialog"

    // Deep link URIs registered on destinations, exercised by the deep-link navigation tests.
    const val DEEPLINK_DETAIL = "grovs-s01://detail"
    const val DEEPLINK_SETTINGS = "grovs-s01://settings"
}

/**
 * Host Activity. Builds the NavHostFragment + fragment-destination graph in onCreate. The graph is
 * set unconditionally, so on state restore NavController re-applies its saved back stack and lands on
 * the previously visible destination. [EXTRA_REDIRECT_TO] / [EXTRA_DEEPLINK_TO] navigate away from the
 * start destination in the same tick.
 */
class S01HostActivity : AppCompatActivity() {

    lateinit var navHostFragment: NavHostFragment
        private set
    val navController: NavController get() = navHostFragment.navController

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)

        navHostFragment = supportFragmentManager.findFragmentByTag(NAV_HOST_TAG) as? NavHostFragment
            ?: NavHostFragment().also { host ->
                supportFragmentManager.beginTransaction()
                    .replace(root.id, host, NAV_HOST_TAG)
                    .setPrimaryNavigationFragment(host)
                    .commitNow()
            }

        val controller = navHostFragment.navController
        controller.graph = controller.createGraph(startDestination = S01Routes.HOME) {
            fragment<S01HomeFragment>(S01Routes.HOME)
            fragment<S01DetailFragment>(S01Routes.DETAIL) {
                deepLink(S01Routes.DEEPLINK_DETAIL)
            }
            fragment<S01SettingsFragment>(S01Routes.SETTINGS) {
                deepLink(S01Routes.DEEPLINK_SETTINGS)
            }
            fragment<S01LoginFragment>(S01Routes.LOGIN)
            dialog<S01DialogDestFragment>(S01Routes.DIALOG)
        }

        if (savedInstanceState == null) {
            intent?.getStringExtra(EXTRA_REDIRECT_TO)?.let { route ->
                // Same-tick redirect: start dest and target both resume before the looper idles, so
                // their resolution jobs coalesce to the final leaf.
                controller.navigate(route)
            }
            intent?.getStringExtra(EXTRA_DEEPLINK_TO)?.let { uri ->
                controller.navigate(uri.toUri())
            }
        }
    }

    companion object {
        private const val NAV_HOST_TAG = "s01-nav-host"
        const val EXTRA_REDIRECT_TO = "s01_redirect_to"
        const val EXTRA_DEEPLINK_TO = "s01_deeplink_to"
    }
}
