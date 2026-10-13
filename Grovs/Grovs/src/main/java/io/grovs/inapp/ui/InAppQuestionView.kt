package io.grovs.inapp.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.grovs.R
import io.grovs.inapp.model.InAppAnswer
import io.grovs.inapp.model.InAppQuestionType

/** The question's part of a card: stars or options, then the comment box. */
internal class InAppQuestionView(
    private val card: InAppCardView,
    private val session: InAppMessageSession,
    private val banner: Boolean,
) {
    private val context = card.context
    private val palette = card.palette

    fun build(besideClose: Boolean): View? {
        val question = session.campaign.content.question ?: return null
        val step = session.step
        if (step is InAppStep.Thanks) return null
        val rendered = session.presentation.rendered
        val textless = rendered.title.isNullOrEmpty() && rendered.message.isNullOrEmpty()
        val area = LinearLayout(context).apply {
            id = R.id.grovs_in_app_question
            orientation = LinearLayout.VERTICAL
            // With no text above it, the question starts below the close button.
            if (!banner) setPaddingRelative(card.dp(16), card.dp(if (textless) 48 else 0), card.dp(16), card.dp(20))
            else if (besideClose) setPaddingRelative(0, 0, card.dp(InAppCardView.CLOSE_CLEARANCE), 0)
        }
        if (step is InAppStep.Comment) {
            commentForm(step).forEach { piece ->
                // The comment box brings its own height.
                val params = piece.layoutParams as? LinearLayout.LayoutParams ?: fullWidth()
                add(area, piece, params)
            }
            return area
        }
        when (question.type) {
            InAppQuestionType.STARS -> {
                area.gravity = if (banner) Gravity.START else Gravity.CENTER_HORIZONTAL
                add(area, starRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            InAppQuestionType.CHOICE -> question.options.forEach { option ->
                val pill = button(option.text, palette.quiet, palette.plainText)
                pill.tag = "grovs-in-app-answer-${option.id}"
                pill.setOnClickListener { session.answer(InAppAnswer.Option(option.id)) }
                add(area, pill)
            }
        }
        return area
    }

    private fun fullWidth() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun add(area: LinearLayout, view: View, params: LinearLayout.LayoutParams = fullWidth()) {
        if (area.childCount > 0) params.topMargin = card.dp(8)
        area.addView(view, params)
    }

    /** A pill whose touch area grows to the minimum where a banner draws it shorter. */
    private fun button(title: String, background: Int, textColor: Int): TextView {
        val pill = card.pill(title, background, textColor)
        if (banner) card.card.smallTargets += pill
        return pill
    }

    private fun starRow(): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (star in 1..5) {
            val control = ImageView(context).apply {
                setImageResource(R.drawable.grovs_in_app_star)
                imageTintList = ColorStateList.valueOf(palette.pick(InAppStyle.GREY, InAppStyle.WHITE))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = context.getString(R.string.grovs_in_app_stars, star)
                tag = "grovs-in-app-answer-$star"
                isClickable = true
                isFocusable = true
                setOnClickListener { session.answer(InAppAnswer.Stars(star)) }
            }
            row.addView(control, LinearLayout.LayoutParams(card.dp(InAppCardView.MIN_TOUCH), card.dp(InAppCardView.MIN_TOUCH)))
        }
        return row
    }

    private fun commentForm(step: InAppStep.Comment): List<View> {
        val prompt = card.label(step.prompt, if (banner) 13f else 15f, bold = true, color = palette.plainText,
            viewId = R.id.grovs_in_app_prompt)
        prompt.labelFor = R.id.grovs_in_app_comment
        val box = EditText(context).apply {
            id = R.id.grovs_in_app_comment
            setText(step.text)
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(palette.plainText)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply {
                cornerRadius = card.dp(10).toFloat()
                setColor(palette.quiet)
            }
            setPaddingRelative(card.dp(8), card.dp(10), card.dp(8), card.dp(10))
            addTextChangedListener(Clipping { session.draft(it) })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, card.dp(if (banner) 64 else 88))
        }
        val send = button(context.getString(R.string.grovs_in_app_send), InAppStyle.PRIMARY, InAppStyle.WHITE).apply {
            id = R.id.grovs_in_app_send
            setOnClickListener { session.send() }
        }
        val skip = button(context.getString(R.string.grovs_in_app_skip), palette.quiet, palette.plainText).apply {
            id = R.id.grovs_in_app_skip
            setOnClickListener { session.skip() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(skip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = card.dp(10) })
        }
        return listOf(prompt, box, row)
    }

    /** Keeps the box within the limit, a paste included, and hands every change to the session. */
    private class Clipping(private val keep: (String) -> Unit) : TextWatcher {
        private var clipping = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            if (clipping) return
            if (s.length > InAppCommentLimit.UNITS) {
                clipping = true
                s.replace(0, s.length, InAppCommentLimit.clip(s.toString()))
                clipping = false
            }
            keep(s.toString())
        }
    }
}
