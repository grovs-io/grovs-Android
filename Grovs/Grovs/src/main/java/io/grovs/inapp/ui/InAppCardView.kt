package io.grovs.inapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
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
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal val semibold: Typeface
    get() = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false)
    else Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** Hands a touch to whichever of several views it was meant for; a view holds only one TouchDelegate. */
private class InAppTouchTargets(host: View) : TouchDelegate(Rect(), host) {
    val targets = mutableListOf<TouchDelegate>()

    /** The target that took the gesture's DOWN, kept until UP or CANCEL. */
    private var active: TouchDelegate? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) active = targets.firstOrNull { send(it, event) }
        else active?.let { send(it, event) }
        val handled = active != null
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) active = null
        return handled
    }

    /** A TouchDelegate moves the event it is given, so each one gets its own copy. */
    private fun send(target: TouchDelegate, event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        try {
            return target.onTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }
}

/** The card's own frame: rounded, clipped, with a size limit and an optional swipe. */
internal class InAppCardLayout(context: Context) : FrameLayout(context) {
    var preferredWidth = 0
    var maxHeight = Int.MAX_VALUE
    /** `up` is true for a fling towards the top. */
    var onSwipe: ((up: Boolean) -> Unit)? = null
    /** Views drawn shorter than a touch target; their touch area grows to [minTouchHeight]. */
    val smallTargets = mutableListOf<View>()
    var minTouchHeight = 0
    private val shape = GradientDrawable()
    private val touchTargets = InAppTouchTargets(this)
    /** What a scrolling view under the finger could still do when the gesture began. */
    private var couldScrollDown = false
    private var couldScrollUp = false
    private val swipes = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityY) <= abs(velocityX)) return false
            val up = velocityY < 0
            // A fling up moves the content down; it belongs to the scroller while it can still go.
            if (if (up) couldScrollDown else couldScrollUp) return false
            onSwipe?.invoke(up)
            return false
        }
    })

    init {
        background = shape
        clipToOutline = true
        touchDelegate = touchTargets
    }

    fun style(color: Int, radius: Float) {
        shape.setColor(color)
        shape.cornerRadius = radius
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onSwipe != null) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                couldScrollDown = scrollsAt(this, ev.x, ev.y, 1)
                couldScrollUp = scrollsAt(this, ev.x, ev.y, -1)
            }
            swipes.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** True when a view under (x, y), in [view]'s coordinates, can scroll in [direction]. */
    private fun scrollsAt(view: View, x: Float, y: Float, direction: Int): Boolean {
        if (view !== this && view.canScrollVertically(direction)) return true
        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val childX = x + group.scrollX - child.left
            val childY = y + group.scrollY - child.top
            if (child.visibility == VISIBLE && childX >= 0 && childY >= 0 && childX < child.width && childY < child.height &&
                scrollsAt(child, childX, childY, direction)) return true
        }
        return false
    }

    /** Taps that reach the card itself are never backdrop taps. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        touchTargets.targets.clear()
        for (target in smallTargets) {
            if (!target.isAttachedToCard()) continue
            val area = boundsInCard(target)
            val grow = max(0, minTouchHeight - area.height())
            area.top -= (grow + 1) / 2
            area.bottom += (grow + 1) / 2
            touchTargets.targets += TouchDelegate(area, target)
        }
    }

    private fun View.isAttachedToCard(): Boolean {
        var node: View? = this
        while (node != null && node !== this@InAppCardLayout) node = node.parent as? View
        return node != null
    }

    private fun boundsInCard(view: View): Rect {
        var x = 0
        var y = 0
        var node: View = view
        while (node !== this) {
            val parent = node.parent as View
            x += node.left - parent.scrollX
            y += node.top - parent.scrollY
            node = parent
        }
        return Rect(x, y, x + view.width, y + view.height)
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
    private val portrait = images.file(InAppStyle.imageUrl(campaign, "portrait"))
    private val landscape = images.file(InAppStyle.imageUrl(campaign, "landscape"))
    private val decoded = mutableMapOf<File, Bitmap?>()
    private val hasPicture = resources.displayMetrics.let { bitmapFor(it.widthPixels, it.heightPixels) } != null
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
        // Back closes the message, so the backdrop needs no accessibility action of its own.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
        card.smallTargets.clear()
        card.minTouchHeight = dp(MIN_TOUCH)
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

    /**
     * FrameLayout centres on the whole view and then shifts by the margins, so the keyboard's margin
     * could lift a centred card past the top. It is centred in the room the margins leave instead.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val params = card.layoutParams as? LayoutParams ?: return
        if (params.gravity != Gravity.CENTER) return
        val x = params.leftMargin + (width - params.leftMargin - params.rightMargin - card.measuredWidth) / 2
        val y = params.topMargin + (height - params.topMargin - params.bottomMargin - card.measuredHeight) / 2
        card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        chooseImage(w, h)
    }

    private fun chooseImage(w: Int, h: Int) {
        val shown = picture ?: return
        if (w <= 0 || h <= 0) return
        shown.setImageBitmap(bitmapFor(w, h))
    }

    /** The picture for this orientation, else the other one when it is missing or does not decode. */
    private fun bitmapFor(w: Int, h: Int): Bitmap? {
        val order = if (w > h) listOf(landscape, portrait) else listOf(portrait, landscape)
        return order.filterNotNull().asSequence().mapNotNull(::decodeOnce).firstOrNull()
    }

    private fun decodeOnce(file: File): Bitmap? {
        if (!decoded.containsKey(file)) decoded[file] = decode(file)
        return decoded[file]
    }

    private fun decode(file: File): Bitmap? {
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
        val cap = max(MAX_IMAGE_SIDE, screen)
        while (max(width, height) / sample > cap || min(width, height) / (sample * 2) >= screen) sample *= 2
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
                question = questionArea(besideClose = false)?.let { scrolling(it, viewId = View.NO_ID) }
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
        body.question = questionArea(besideClose)?.let { scrolling(it, viewId = View.NO_ID) }
        row.addView(body, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Title and message as drawn for the current step; the thanks takes their place. */
    private fun shownText(): Pair<String, String> {
        if (session.step is InAppStep.Thanks) return (campaign.content.question?.thanks ?: "") to ""
        return (session.presentation.rendered.title ?: "") to (session.presentation.rendered.message ?: "")
    }

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

    private fun scrolling(inner: View, viewId: Int = R.id.grovs_in_app_text): View = ScrollView(context).apply {
        id = viewId
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
            else if (besideClose) setPaddingRelative(0, 0, dp(CLOSE_CLEARANCE), 0)
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
            if (banner) card.smallTargets += control
            val params = if (spans) LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            if (index > 0) params.marginStart = dp(10)
            row.addView(control, params)
        }
        return row
    }

    private fun questionArea(besideClose: Boolean): View? = InAppQuestionView(this, session, banner).build(besideClose)

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
        val endInset = if (layoutDirection == LAYOUT_DIRECTION_RTL) safe.left else safe.right
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
                cardEnd = endInset
                card.maxHeight = Int.MAX_VALUE
            }
            InAppTemplate.HEADER -> {
                params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
                built.setPadding(safe.left + dp(16), safe.top + dp(12), safe.right + dp(16), dp(12))
                cardTop = safe.top
                cardEnd = endInset
                card.maxHeight = max(0, screenHeight - safe.bottom - dp(16))
            }
            InAppTemplate.FOOTER -> {
                params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                params.bottomMargin = imeBottom
                built.setPadding(safe.left + dp(16), dp(12), safe.right + dp(16), (if (imeBottom > 0) 0 else safe.bottom) + dp(12))
                cardEnd = endInset
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

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        applyLayout()
    }

    companion object {
        /** The close button (28) and a gap (8), measured from the content's trailing margin. */
        const val CLOSE_CLEARANCE = 36
        /** How far a banner's close reaches below the top of its content. */
        private const val BANNER_CLOSE_REACH = 26
        private const val MAX_IMAGE_SIDE = 2048
        const val MIN_TOUCH = 48
    }
}
