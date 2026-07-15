package io.grovs.handlers

import android.view.View
import androidx.fragment.app.DialogFragment
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
        // Dialogs and bottom sheets are overlays, not screens: they are shown ON TOP of the current
        // screen without navigating away from it. Skipping DialogFragment makes modal handling
        // deterministic (otherwise a modal is tracked or not depending on whether the content is the
        // FragmentManager's primaryNavigationFragment). Track modal surfaces explicitly with
        // Grovs.trackScreenView(...) if you want them reported as screens.
        return fragment !is DialogFragment &&
            fragment.isAdded &&
            fragment.isResumed &&
            !fragment.isHidden &&
            fragment.userVisibleHint &&
            fragment.view?.visibility == View.VISIBLE
    }
}
