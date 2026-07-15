package io.grovs.handlers

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

internal object VisibleFragmentResolver {

    fun findVisibleLeaf(fragmentManager: FragmentManager): Fragment? {
        val candidate = fragmentManager.primaryNavigationFragment
            ?.takeIf(::isEligible)
            ?: fragmentManager.fragments
                .asReversed()
                .firstOrNull(::isEligible)
            ?: return null

        return findVisibleLeaf(candidate.childFragmentManager) ?: candidate
    }

    private fun isEligible(fragment: Fragment): Boolean {
        return fragment.isAdded &&
            fragment.isResumed &&
            !fragment.isHidden &&
            fragment.userVisibleHint &&
            fragment.view?.visibility == View.VISIBLE
    }
}
