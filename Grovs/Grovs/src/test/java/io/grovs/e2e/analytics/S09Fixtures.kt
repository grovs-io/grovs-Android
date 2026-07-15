package io.grovs.e2e.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * Fixtures for Setup 09 — nested fragments. A [S09HostActivity] hosts a [S09ContainerFragment]
 * (the activity's primaryNavigationFragment) whose childFragmentManager holds the leaf children,
 * optionally two levels deep to grandchildren.
 */

/** Base leaf fragment: owns a FrameLayout view with a unique id so it can itself host grandchildren. */
open class S09LeafFragment : Fragment() {
    /** The id of this fragment's own content view — attach grandchildren here. */
    var contentId: Int = 0
        private set

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = View.generateViewId()
        this@S09LeafFragment.contentId = id
    }
}

// Leaf children hosted inside the container's childFragmentManager.
class S09ChildAFragment : S09LeafFragment()
class S09ChildBFragment : S09LeafFragment()
class S09ChildCFragment : S09LeafFragment()

// Grandchildren hosted inside a leaf child's childFragmentManager (two levels deep).
class S09GrandchildXFragment : S09LeafFragment()
class S09GrandchildYFragment : S09LeafFragment()

// Leaf child of the "B" container, used for the activity-level container swap.
class S09ChildOfBFragment : S09LeafFragment()

/** A resumed fragment with a null view; must be skipped by the visible-view guard. */
class S09NoViewFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = null
}

/**
 * Container fragment whose childFragmentManager hosts the leaf children. When created with
 * [ARG_AUTO_CHILD_A] it seeds a single visible [S09ChildAFragment] on first creation.
 */
open class S09ContainerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = CONTENT_ID }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // On recreation/restore the FragmentManager restores the child fragments itself.
        if (savedInstanceState != null) return
        if (arguments?.getBoolean(ARG_AUTO_CHILD_A) == true) {
            childFragmentManager.beginTransaction()
                .add(CONTENT_ID, S09ChildAFragment(), "s09-childA")
                .commitNow()
        }
    }

    companion object {
        /** Stable content-view id so restored children re-attach across recreation. */
        const val CONTENT_ID = 0x0F090201
        const val ARG_AUTO_CHILD_A = "s09_auto_child_a"
    }
}

/** A second container variant that always seeds its own leaf child, for the activity-level swap. */
class S09ContainerBFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = CONTENT_ID }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) return
        childFragmentManager.beginTransaction()
            .add(CONTENT_ID, S09ChildOfBFragment(), "s09-childOfB")
            .commitNow()
    }

    companion object {
        const val CONTENT_ID = 0x0F090202
    }
}

/**
 * Host activity. Adds a [S09ContainerFragment] as the primaryNavigationFragment. [autoAddChildA]
 * is static so the seeding choice survives activity recreation for rotation/restore scenarios.
 */
class S09HostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = ROOT_ID }
        setContentView(root)

        if (savedInstanceState == null) {
            val container = S09ContainerFragment().apply {
                arguments = Bundle().apply { putBoolean(S09ContainerFragment.ARG_AUTO_CHILD_A, autoAddChildA) }
            }
            supportFragmentManager.beginTransaction()
                .add(ROOT_ID, container, S09_CONTAINER_TAG)
                .setPrimaryNavigationFragment(container)
                .commitNow()
        }
    }

    companion object {
        const val ROOT_ID = 0x0F090101
        const val S09_CONTAINER_TAG = "s09-container"

        /** When true, the seeded container auto-adds a visible ChildA on first creation. */
        @JvmStatic
        @Volatile
        var autoAddChildA: Boolean = false
    }
}
