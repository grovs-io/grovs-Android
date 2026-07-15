package io.grovs.e2e

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

open class TestFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = View.generateViewId()
    }
}

class FragmentHostTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        if (savedInstanceState == null) {
            val fragment = TestFragment()
            supportFragmentManager.beginTransaction()
                .add(root.id, fragment, "test-fragment")
                .setPrimaryNavigationFragment(fragment)
                .commitNow()
        }
    }
}

class VisibleLeafFragment : TestFragment()
class HiddenSiblingFragment : TestFragment()
class OffscreenSiblingFragment : TestFragment()

class NestedContainerFragment : TestFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) return

        val visible = VisibleLeafFragment()
        val hidden = HiddenSiblingFragment()
        val offscreen = OffscreenSiblingFragment().apply {
            userVisibleHint = false
        }

        childFragmentManager.beginTransaction()
            .add(view.id, visible, "visible-leaf")
            .add(view.id, hidden, "hidden-sibling")
            .hide(hidden)
            .add(view.id, offscreen, "offscreen-sibling")
            .setPrimaryNavigationFragment(hidden)
            .commitNow()
    }
}

class NestedFragmentHostTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        if (savedInstanceState == null) {
            val container = NestedContainerFragment()
            supportFragmentManager.beginTransaction()
                .add(root.id, container, "nested-container")
                .setPrimaryNavigationFragment(container)
                .commitNow()
        }
    }
}
