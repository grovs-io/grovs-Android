package io.grovs.inapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import io.grovs.R
import io.grovs.inapp.InAppImages
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppTemplate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal val semibold: Typeface
    get() = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false)
    else Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** The card's own frame: rounded, clipped, with a size limit and an optional swipe. */
internal class InAppCardLayout(context: Context) : FrameLayout(context) {
    var preferredWidth = 0
    var maxHeight = Int.MAX_VALUE
    /** `up` is true for a fling towards the top. */
    var onSwipe: ((up: Boolean) -> Unit)? = null
    private val shape = GradientDrawable()
    private val swipes = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityY) > abs(velocityX)) onSwipe?.invoke(velocityY < 0)
            return false
        }
    })

    init {
        background = shape
        clipToOutline = true
    }

    fun style(color: Int, radius: Float) {
        shape.setColor(color)
        shape.cornerRadius = radius
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onSwipe != null) swipes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var widthSpec = widthMeasureSpec
        if (preferredWidth > 0) {
            widthSpec = MeasureSpec.makeMeasureSpec(min(preferredWidth, MeasureSpec.getSize(widthMeasureSpec)), MeasureSpec.EXACTLY)
        }
        var heightSpec = heightMeasureSpec
        if (maxHeight < Int.MAX_VALUE && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            val given = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
            else MeasureSpec.getSize(heightMeasureSpec)
            heightSpec = MeasureSpec.makeMeasureSpec(min(maxHeight, given), MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthSpec, heightSpec)
    }
}

/** Draws one native message: the backdrop, its card and what is on it. Knows nothing of triggers or caps. */
@SuppressLint("ViewConstructor")
internal class InAppCardView(
    context: Context,
    private val session: InAppMessageSession,
    private val images: InAppImages,
    night: Boolean,
) : FrameLayout(context) {
    private val campaign = session.campaign
    private val template = campaign.template
    private val banner = template.isBanner
    val palette = InAppPalette(campaign, night)
    val card = InAppCardLayout(context)
    private val skirt = View(context)
    private val portrait = decode(InAppStyle.imageUrl(campaign, "portrait"))
    private val landscape = decode(InAppStyle.imageUrl(campaign, "landscape"))
    private val hasPicture = portrait != null || landscape != null
    private var picture: ImageView? = null
    private var content: View? = null
    private var close: View? = null
    private var safe = Insets.NONE
    private var imeBottom = 0

    /** The most a banner may take, from the screen; the window sets it. */
    var screenHeight = Int.MAX_VALUE
        set(value) {
            field = value
            applyLayout()
        }

    internal val shownImage: Bitmap?
        get() = (picture?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

    init {
        id = R.id.grovs_in_app_root
        setBackgroundColor(if (InAppStyle.dims(campaign)) InAppStyle.BACKDROP else Color.TRANSPARENT)
        skirt.id = R.id.grovs_in_app_skirt
        skirt.visibility = GONE
        skirt.setBackgroundColor(palette.card)
        addView(skirt, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        card.id = R.id.grovs_in_app_card
        card.style(palette.card, dp(InAppStyle.cornerRadiusDp(template)).toFloat())
        if (InAppStyle.hasShadow(template)) card.elevation = dp(6).toFloat()
        addView(card)
        if (!banner && campaign.content.dismissible) setOnClickListener { session.leave("backdrop") }
        if (banner && campaign.content.dismissible) {
            card.onSwipe = { up -> if (up == (template == InAppTemplate.HEADER)) session.leave("swipe") }
        }
        rebuild()
    }

    /** Builds the card again for the current step. */
    fun rebuild() {
        card.removeAllViews()
        // A tap on the card is never a backdrop tap.
        card.setOnClickListener {}
        val art = pictureView()
        val built = if (banner) bannerContent(art) else stackContent(art)
        val full = template == InAppTemplate.COVER || template == InAppTemplate.COVER_IMAGE
        card.addView(built, LayoutParams(LayoutParams.MATCH_PARENT, if (full) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT))
        content = built
        campaign.content.tapAction?.let { tap ->
            val target = if (template.isImageOnly && art != null) art else card
            target.setOnClickListener { session.clickTap() }
            if (template.isImageOnly) {
                target.contentDescription = context.getString(
                    if (tap.type == InAppActionType.OPEN_URL) R.string.grovs_in_app_open else R.string.grovs_in_app_continue)
            }
        }
        close = if (InAppStyle.showsClose(campaign)) closeButton().also { card.addView(it) } else null
        applyLayout()
        card.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    fun applyInsets(safe: Insets, imeBottom: Int) {
        this.safe = safe
        this.imeBottom = imeBottom
        applyLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        chooseImage(w, h)
    }

    private fun chooseImage(w: Int, h: Int) {
        val wide = w > h
        picture?.setImageBitmap((if (wide) landscape else portrait) ?: portrait ?: landscape)
    }

    private fun decode(url: String?): Bitmap? {
        val file = images.file(url)?.takeIf { it.exists() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        })
    }

    /** Halves the picture while its short side still covers the screen's long side, the most it can fill. */
    private fun sampleSize(width: Int, height: Int): Int {
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        var sample = 1
        while (max(width, height) / sample > MAX_IMAGE_SIDE || min(width, height) / (sample * 2) >= screen) sample *= 2
        return sample
    }

    private fun pictureView(): ImageView? {
        if (!hasPicture) return null
        return ImageView(context).apply {
            id = R.id.grovs_in_app_picture
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (banner) {
                val radius = dp(8).toFloat()
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
                clipToOutline = true
            }
            picture = this
            chooseImage(this@InAppCardView.width, this@InAppCardView.height)
        }
    }

    private fun stackContent(art: ImageView?): View {
        val full = template == InAppTemplate.COVER || template == InAppTemplate.COVER_IMAGE
        return InAppStackLayout(context).apply {
            picture = art
            ratio = InAppStyle.pictureRatio(template)
            fillPicture = full
            centred = full && art == null && !template.isImageOnly
            if (!template.isImageOnly) {
                text = textBlock()?.let(::scrolling)
                buttons = buttonRow(besideClose = false)
                question = questionArea(besideClose = false)
            }
        }
    }

    private fun bannerContent(art: ImageView?): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        art?.let { row.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }) }
        val body = InAppStackLayout(context).apply { spacing = dp(8) }
        val text = textBlock()
        body.text = text?.let(::scrolling)
        var besideClose = text == null && InAppStyle.showsClose(campaign)
        body.buttons = buttonRow(besideClose)?.also { besideClose = false }
        body.question = questionArea(besideClose)
        row.addView(body, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Title and message as drawn for the current step. */
    private fun shownText(): Pair<String, String> =
        (session.presentation.rendered.title ?: "") to (session.presentation.rendered.message ?: "")

    private fun textBlock(): View? {
        val (title, message) = shownText()
        if (title.isEmpty() && message.isEmpty()) return null
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (!banner) setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        var besideClose = InAppStyle.showsClose(campaign) && (banner || !hasPicture)
        fun add(label: TextView) {
            val params = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (block.childCount > 0) params.topMargin = dp(if (banner) 2 else 8)
            if (besideClose) {
                besideClose = false
                params.marginEnd = dp(CLOSE_CLEARANCE)
                if (banner) label.minHeight = dp(BANNER_CLOSE_REACH)
            }
            block.addView(label, params)
        }
        if (title.isNotEmpty()) add(label(title, if (banner) 15f else 20f, bold = true, color = palette.title, viewId = R.id.grovs_in_app_title))
        if (message.isNotEmpty()) add(label(message, if (banner) 13f else 15f, bold = false, color = palette.message, viewId = R.id.grovs_in_app_message))
        return block
    }

    fun label(text: String, sizeSp: Float, bold: Boolean, color: Int, viewId: Int): TextView = TextView(context).apply {
        id = viewId
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun scrolling(inner: View): View = ScrollView(context).apply {
        id = R.id.grovs_in_app_text
        isFillViewport = false
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buttonRow(besideClose: Boolean): View? {
        val defined = campaign.content.buttons
        if (defined.isEmpty()) return null
        val raw = InAppStyle.content(campaign).get("buttons")?.takeIf { it.isJsonArray }?.asJsonArray
        val row = LinearLayout(context).apply {
            id = R.id.grovs_in_app_buttons
            orientation = LinearLayout.HORIZONTAL
            if (!banner) setPadding(dp(16), 0, dp(16), dp(20))
            else if (besideClose) setPadding(0, 0, dp(CLOSE_CLEARANCE), 0)
        }
        // In a banner a lone button keeps its own width.
        val spans = !banner || defined.size > 1
        defined.forEachIndexed { index, button ->
            val tints = raw?.takeIf { index < it.size() }?.get(index)?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("colors")?.takeIf { it.isJsonObject }?.asJsonObject ?: com.google.gson.JsonObject()
            val title = session.presentation.rendered.buttons.getOrNull(index) ?: button.text
            val control = pill(title, palette.buttonBackground(index, tints), palette.buttonText(index, tints))
            control.tag = "grovs-in-app-button-${button.id}"
            control.setOnClickListener { session.clickButton(index) }
            val params = if (spans) LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            if (index > 0) params.marginStart = dp(10)
            row.addView(control, params)
        }
        return row
    }

    /** The question's answers; none until questions are drawn. */
    @Suppress("UNUSED_PARAMETER")
    private fun questionArea(besideClose: Boolean): View? = null

    /** A button drawn as the message's own buttons are. */
    fun pill(title: String, background: Int, textColor: Int): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (banner) 13f else 15f)
        typeface = semibold
        val vertical = dp(if (banner) 8 else 12)
        setPadding(dp(12), vertical, dp(12), vertical)
        minHeight = dp(if (banner) 32 else 48)
        this.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(background)
        }
        isClickable = true
        isFocusable = true
    }

    private fun closeButton(): View {
        // Over a picture the button needs its own dark disc; over the card a faint one is enough.
        val faint = banner || !hasPicture
        val disc = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = semibold
            setTextColor(if (faint) palette.pick(InAppStyle.GREY, InAppStyle.WHITE) else InAppStyle.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (faint) palette.pick(InAppStyle.BLACK_6, InAppStyle.WHITE_16) else InAppStyle.BLACK_45)
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(context).apply {
            id = R.id.grovs_in_app_close
            contentDescription = context.getString(R.string.grovs_in_app_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { session.leave("close_button") }
            addView(disc, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
    }

    private fun applyLayout() {
        val built = content ?: return
        val bottom = max(safe.bottom, imeBottom)
        var cardTop = 0
        var cardEnd = 0
        val params: LayoutParams
        when (template) {
            InAppTemplate.COVER, InAppTemplate.COVER_IMAGE -> {
                params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                // A picture that fills the screen runs under the status bar; text never does.
                val edge = template == InAppTemplate.COVER_IMAGE && hasPicture
                built.setPadding(
                    if (edge) 0 else safe.left, if (hasPicture) 0 else safe.top,
                    if (edge) 0 else safe.right, if (edge) 0 else bottom,
                )
                cardTop = safe.top
                cardEnd = safe.right
                card.maxHeight = Int.MAX_VALUE
            }
            InAppTemplate.HEADER -> {
                params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
                built.setPadding(safe.left + dp(16), safe.top + dp(12), safe.right + dp(16), dp(12))
                cardTop = safe.top
                cardEnd = safe.right
                card.maxHeight = max(0, screenHeight - safe.bottom - dp(16))
            }
            InAppTemplate.FOOTER -> {
                params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                params.bottomMargin = imeBottom
                built.setPadding(safe.left + dp(16), dp(12), safe.right + dp(16), (if (imeBottom > 0) 0 else safe.bottom) + dp(12))
                cardEnd = safe.right
                card.maxHeight = max(0, screenHeight - safe.top - dp(16) - imeBottom)
            }
            else -> {
                params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                params.setMargins(safe.left + dp(16), safe.top + dp(16), safe.right + dp(16), bottom + dp(16))
                card.preferredWidth = dp(InAppStyle.preferredWidthDp(template))
            }
        }
        card.layoutParams = params
        val lifted = template == InAppTemplate.FOOTER && imeBottom > 0
        skirt.visibility = if (lifted) VISIBLE else GONE
        skirt.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, imeBottom, Gravity.BOTTOM)
        close?.layoutParams = LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = cardTop + dp(if (banner) 10 else 12) - dp(10)
            marginEnd = cardEnd + dp(12) - dp(10)
        }
        requestLayout()
    }

    private companion object {
        /** The close button (28) and a gap (8), measured from the content's trailing margin. */
        const val CLOSE_CLEARANCE = 36
        /** How far a banner's close reaches below the top of its content. */
        const val BANNER_CLOSE_REACH = 26
        const val MAX_IMAGE_SIDE = 2048
    }
}
