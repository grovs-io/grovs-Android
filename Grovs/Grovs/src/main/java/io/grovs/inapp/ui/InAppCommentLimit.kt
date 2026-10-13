package io.grovs.inapp.ui

import java.text.BreakIterator
import java.util.Locale

/** The backend's measure for a comment: UTF-16 units, as JavaScript's `length` counts them. */
internal object InAppCommentLimit {
    const val UNITS = 500

    /** The first 500 units, cut back to a character boundary so no character is left in pieces. */
    fun clip(text: String): String {
        if (text.length <= UNITS) return text
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
        characters.setText(text)
        val end = if (characters.isBoundary(UNITS)) UNITS else characters.preceding(UNITS)
        return text.substring(0, end)
    }
}
