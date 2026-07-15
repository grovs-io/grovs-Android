package io.grovs.e2e.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/**
 * Fixtures for Setup 07 — manual FragmentTransactions with back stack (replace + addToBackStack),
 * mimicking a pre-Navigation-Component app that swaps fragments by hand.
 *
 * replace() resumes the new fragment (onFragmentResumed fires -> auto-track); popBackStack()
 * re-resumes the revealed fragment. add()-on-top does not stop the fragment underneath, so popping
 * the added fragment does not re-resume the revealed one — the add()-vs-replace() gap.
 */

/** Base leaf fragment providing a simple, visible FrameLayout so VisibleFragmentResolver treats it as eligible. */
open class S07BaseFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = View.generateViewId()
        visibility = View.VISIBLE
    }
}

class S07HomeFragment : S07BaseFragment()
class S07ListFragment : S07BaseFragment()
class S07DetailFragment : S07BaseFragment()
class S07EditFragment : S07BaseFragment()

class S07PrimaryFragment : S07BaseFragment()
class S07SecondaryFragment : S07BaseFragment()

/** The visible leaf inside a nested Detail. */
class S07DetailChildFragment : S07BaseFragment()

/**
 * A Detail screen that itself hosts a child fragment in its own childFragmentManager.
 * The resolver must descend into the child and report the child as the leaf.
 */
class S07NestedDetailFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = CHILD_CONTAINER_ID
        visibility = View.VISIBLE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) return
        val child = S07DetailChildFragment()
        childFragmentManager.beginTransaction()
            .replace(CHILD_CONTAINER_ID, child, "s07-detail-child")
            .setPrimaryNavigationFragment(child)
            .commitNow()
    }

    companion object {
        const val CHILD_CONTAINER_ID = 0x50700777
    }
}

/** A dialog shown over Detail. */
class S07DialogFragment : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = View.generateViewId()
        visibility = View.VISIBLE
    }
}

/**
 * Single-container host. Root Home is installed on first create (no back stack entry, so a back
 * press at root exits). All other navigation goes through push()/pushAdd()/pop() helpers.
 *
 * CONTAINER_ID is a fixed constant (not View.generateViewId()) so the container id survives
 * recreate()/process-death and the FragmentManager can re-attach restored fragments.
 */
class S07HostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            id = CONTAINER_ID
            visibility = View.VISIBLE
        }
        setContentView(root)

        // On restore (rotation / process death) the FragmentManager restores the back stack itself.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(CONTAINER_ID, S07HomeFragment(), "s07-home")
                .commit()
            supportFragmentManager.executePendingTransactions()
        }
    }

    /** replace + addToBackStack(null) + commit — the canonical manual navigation step. */
    fun push(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, fragment)
            .addToBackStack(null)
            .commit()
        supportFragmentManager.executePendingTransactions()
    }

    /** replace + addToBackStack(name) — for named/inclusive multi-level pops. */
    fun pushNamed(fragment: Fragment, name: String) {
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, fragment)
            .addToBackStack(name)
            .commit()
        supportFragmentManager.executePendingTransactions()
    }

    /** add() on top (no replace) + addToBackStack — the revealed fragment underneath is NOT stopped. */
    fun pushAdd(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .add(CONTAINER_ID, fragment)
            .addToBackStack(null)
            .commit()
        supportFragmentManager.executePendingTransactions()
    }

    fun pop() {
        supportFragmentManager.popBackStack()
        supportFragmentManager.executePendingTransactions()
    }

    fun popInclusive(name: String) {
        supportFragmentManager.popBackStack(name, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.executePendingTransactions()
    }

    companion object {
        const val CONTAINER_ID = 0x50700100
    }
}
