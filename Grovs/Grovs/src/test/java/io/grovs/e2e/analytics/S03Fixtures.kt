package io.grovs.e2e.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.fragment

/**
 * Fixtures for the S03 setup: BottomNavigationView + NavController (multi back stack tabs).
 * Tests drive the real NavController over real Fragment destinations, so the FragmentNavigator
 * fires onFragmentResumed callbacks (the AUTO tracking path).
 */

/** Route constants for the standard 3-tab graph plus a Detail child under Home. */
object S03Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val PROFILE = "profile"
    const val DETAIL = "detail"
    const val TAB_A = "tabA"
    const val TAB_B = "tabB"
}

/** Always supplies a VISIBLE view, so VisibleFragmentResolver treats it as an eligible leaf. */
open class S03BaseFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

class S03HomeFragment : S03BaseFragment()
class S03SearchFragment : S03BaseFragment()
class S03ProfileFragment : S03BaseFragment()
class S03DetailFragment : S03BaseFragment()

/**
 * Same simpleName ("S03DupTabFragment") as [io.grovs.e2e.analytics.alt.S03DupTabFragment] but a
 * different fully-qualified class, so dedup must key on the FQN to keep both distinct.
 */
class S03DupTabFragment : S03BaseFragment()

/**
 * Host Activity with a NavHostFragment and a graph of 3 top-level tab destinations plus a Detail
 * child under Home. Cold start lands on Home.
 */
class S03HostActivity : AppCompatActivity() {

    lateinit var navController: NavController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = CONTAINER_ID }
        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        var host = supportFragmentManager.findFragmentById(CONTAINER_ID) as? NavHostFragment
        if (host == null) {
            host = NavHostFragment()
            supportFragmentManager.beginTransaction()
                .replace(CONTAINER_ID, host, "s03-nav-host")
                .setPrimaryNavigationFragment(host)
                .commitNow()
        }

        navController = host.navController
        if (navController.currentDestination == null && savedInstanceState == null) {
            // A non-Home start destination simulates a deep link / restore landing on that tab.
            val start = intent?.getStringExtra(EXTRA_START_DEST) ?: S03Routes.HOME
            navController.graph = navController.createGraph(startDestination = start) {
                fragment<S03HomeFragment>(S03Routes.HOME)
                fragment<S03SearchFragment>(S03Routes.SEARCH)
                fragment<S03ProfileFragment>(S03Routes.PROFILE)
                fragment<S03DetailFragment>(S03Routes.DETAIL)
            }
        }
    }

    companion object {
        val CONTAINER_ID = View.generateViewId()
        const val EXTRA_START_DEST = "s03_start_dest"
    }
}

/**
 * Host Activity whose two tab destinations resolve to the SAME simpleName ("S03DupTabFragment")
 * from two different packages.
 */
class S03CollisionHostActivity : AppCompatActivity() {

    lateinit var navController: NavController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = CONTAINER_ID }
        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        var host = supportFragmentManager.findFragmentById(CONTAINER_ID) as? NavHostFragment
        if (host == null) {
            host = NavHostFragment()
            supportFragmentManager.beginTransaction()
                .replace(CONTAINER_ID, host, "s03-collision-nav-host")
                .setPrimaryNavigationFragment(host)
                .commitNow()
        }

        navController = host.navController
        if (navController.currentDestination == null && savedInstanceState == null) {
            navController.graph = navController.createGraph(startDestination = S03Routes.TAB_A) {
                fragment<S03DupTabFragment>(S03Routes.TAB_A)
                fragment<io.grovs.e2e.analytics.alt.S03DupTabFragment>(S03Routes.TAB_B)
            }
        }
    }

    companion object {
        val CONTAINER_ID = View.generateViewId()
    }
}
