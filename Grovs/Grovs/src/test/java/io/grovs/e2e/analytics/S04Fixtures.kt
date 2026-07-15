package io.grovs.e2e.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle

/**
 * Test fixtures for Setup 04 — BottomNavigationView + hide()/show() fragment transactions.
 *
 * All fragments render a simple visible FrameLayout so the SDK's VisibleFragmentResolver can
 * see them (isAdded && isResumed && !isHidden && userVisibleHint && view.visibility == VISIBLE).
 */
open class S04BaseFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = View.generateViewId()
    }
}

class S04HomeFragment : S04BaseFragment()
class S04SearchFragment : S04BaseFragment()
class S04ProfileFragment : S04BaseFragment()
class S04DetailFragment : S04BaseFragment()

/**
 * The LEGACY tab host: all tab fragments are add()ed once and switched with hide()/show()
 * FragmentTransactions. NO lifecycle transition occurs on a switch and there is no NavController,
 * so onFragmentResumed does NOT fire when tabs are swapped. This is the SDK's known blind spot.
 */
class S04HostActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_TAB = "s04_start_tab"
        const val TAG_HOME = "s04-home"
        const val TAG_SEARCH = "s04-search"
        const val TAG_PROFILE = "s04-profile"
        const val TAG_DETAIL = "s04-detail"
        val TAB_TAGS = listOf(TAG_HOME, TAG_SEARCH, TAG_PROFILE)
    }

    private var containerId: Int = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = containerId }
        setContentView(root)

        if (savedInstanceState == null) {
            val startTab = intent?.getStringExtra(EXTRA_START_TAB) ?: TAG_HOME

            val home = S04HomeFragment()
            val search = S04SearchFragment()
            val profile = S04ProfileFragment()

            val tx = supportFragmentManager.beginTransaction()
                .add(containerId, home, TAG_HOME)
                .add(containerId, search, TAG_SEARCH)
                .add(containerId, profile, TAG_PROFILE)

            // Hide every tab except the initial one, and mark the initial one primary-nav.
            mapOf(TAG_HOME to home, TAG_SEARCH to search, TAG_PROFILE to profile).forEach { (tag, f) ->
                if (tag == startTab) {
                    tx.setPrimaryNavigationFragment(f)
                } else {
                    tx.hide(f)
                }
            }
            tx.commitNow()
        }
    }

    /**
     * The LEGACY switch: hide the currently shown tab and show the target. commitNow, no back stack.
     * This produces NO lifecycle change on any fragment — the shown/hidden ones were already
     * RESUMED — so the SDK never receives an onFragmentResumed callback for the switch.
     */
    fun switchTo(targetTag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (tag in TAB_TAGS) {
            val f = fm.findFragmentByTag(tag) ?: continue
            if (tag == targetTag) tx.show(f) else tx.hide(f)
        }
        tx.commitNow()
    }

    /** Opens a detail screen on top of the current tab with a real resume + back stack entry. */
    fun openDetail() {
        val detail = S04DetailFragment()
        supportFragmentManager.beginTransaction()
            .add(containerId, detail, TAG_DETAIL)
            .setPrimaryNavigationFragment(detail)
            .addToBackStack(TAG_DETAIL)
            .commit()
        supportFragmentManager.executePendingTransactions()
    }

    /** Pops the detail screen, revealing the tab underneath (which is NOT re-resumed). */
    fun popDetail() {
        supportFragmentManager.popBackStackImmediate()
    }
}

/**
 * The MODERN recommended tab swap, for contrast: all tabs added once, but switching is driven by
 * setMaxLifecycle(RESUMED/STARTED). Raising a fragment to RESUMED DOES fire onFragmentResumed, so
 * the SDK auto-tracks the switch. This is the fixture that proves the gap is specific to hide/show.
 */
class S04MaxLifecycleHostActivity : AppCompatActivity() {

    companion object {
        const val TAG_HOME = "s04ml-home"
        const val TAG_SEARCH = "s04ml-search"
        const val TAG_PROFILE = "s04ml-profile"
        val TAB_TAGS = listOf(TAG_HOME, TAG_SEARCH, TAG_PROFILE)
    }

    private var containerId: Int = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = containerId }
        setContentView(root)

        if (savedInstanceState == null) {
            val home = S04HomeFragment()
            val search = S04SearchFragment()
            val profile = S04ProfileFragment()

            supportFragmentManager.beginTransaction()
                .add(containerId, home, TAG_HOME)
                .add(containerId, search, TAG_SEARCH)
                .add(containerId, profile, TAG_PROFILE)
                // Only Home starts RESUMED; the others are capped at STARTED.
                .setMaxLifecycle(search, Lifecycle.State.STARTED)
                .setMaxLifecycle(profile, Lifecycle.State.STARTED)
                .setPrimaryNavigationFragment(home)
                .commitNow()
        }
    }

    /**
     * The modern switch: cap the outgoing tab at STARTED and raise the incoming tab to RESUMED.
     * Raising to RESUMED drives onFragmentResumed, which the SDK auto-tracks.
     */
    fun switchTo(targetTag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (tag in TAB_TAGS) {
            val f = fm.findFragmentByTag(tag) ?: continue
            if (tag == targetTag) {
                tx.setMaxLifecycle(f, Lifecycle.State.RESUMED)
                tx.setPrimaryNavigationFragment(f)
            } else {
                tx.setMaxLifecycle(f, Lifecycle.State.STARTED)
            }
        }
        tx.commitNow()
    }
}
