package io.grovs.inapp.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Picture, text, buttons and question from top to bottom. When the room runs out the picture gives way
 * first, then the text scrolls, then the question; buttons always keep their height.
 */
internal class InAppStackLayout(context: Context) : ViewGroup(context) {
    var picture: View? = null
        set(value) {
            swap(field, value)
            field = value
        }
    var text: View? = null
        set(value) {
            swap(field, value)
            field = value
        }
    var buttons: View? = null
        set(value) {
            swap(field, value)
            field = value
        }
    var question: View? = null
        set(value) {
            swap(field, value)
            field = value
        }

    /** Picture height over width; 0 lets a full-screen picture take what is left. */
    var ratio = 0f
    var fillPicture = false
    /** No picture on a full-screen card: equal room above the text and above the buttons. */
    var centred = false
    var spacing = 0
    private var leftover = 0

    private fun swap(old: View?, new: View?) {
        if (old === new) return
        old?.let(::removeView)
        new?.let { addView(it) }
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(max(0, size), MeasureSpec.EXACTLY)

    private fun natural(view: View?, width: Int): Int {
        view ?: return 0
        view.measure(exactly(width), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return view.measuredHeight
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val limit = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2 else MeasureSpec.getSize(heightMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        val present = listOfNotNull(picture, text, buttons, question).size
        val fixed = natural(buttons, inner) + spacing * max(0, present - 1) + paddingTop + paddingBottom
        val questionHeight = min(natural(question, inner), max(0, limit - fixed))
        val textNatural = natural(text, inner)
        val room = max(0, limit - fixed - questionHeight)
        var pictureHeight = 0
        val textHeight: Int
        if (picture != null && fillPicture) {
            textHeight = min(textNatural, room)
            pictureHeight = room - textHeight
        } else {
            if (picture != null) pictureHeight = min((inner * ratio).roundToInt(), max(0, room - textNatural))
            textHeight = min(textNatural, room - pictureHeight)
        }
        val content = fixed + questionHeight + pictureHeight + textHeight
        val height = if (mode == MeasureSpec.EXACTLY) limit else min(content, limit)
        leftover = max(0, height - content)
        picture?.measure(exactly(inner), exactly(pictureHeight))
        text?.measure(exactly(inner), exactly(textHeight))
        buttons?.let { it.measure(exactly(inner), exactly(it.measuredHeight)) }
        question?.measure(exactly(inner), exactly(questionHeight))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        var first = true
        fun place(view: View?) {
            view ?: return
            if (!first) y += spacing
            first = false
            view.layout(paddingLeft, y, paddingLeft + view.measuredWidth, y + view.measuredHeight)
            y += view.measuredHeight
        }
        place(picture)
        if (centred) y += leftover / 2
        place(text)
        if (centred && buttons != null) y += leftover - leftover / 2
        place(buttons)
        place(question)
    }
}
