package io.grovs.inapp.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.grovs.R
import io.grovs.inapp.InAppCancellable
import io.grovs.inapp.InAppMainTimer
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.InAppPresentation
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppLayout
import io.grovs.inapp.model.InAppOrientation
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The `html` template: a WebView hidden until its page says it is ready, inside the SDK's own card and close.
 * The presenter puts what `attach` builds in an `InAppWindow`; the WebView outlives each window. Main thread only.
 */
internal class InAppHtmlView(
    private val presentation: InAppPresentation,
    private val actions: InAppActions,
    private val finish: (InAppOutcome) -> Unit,
    schedule: (delayMillis: Long, work: () -> Unit) -> InAppCancellable = InAppMainTimer::after,
    private val makeWebView: (Context) -> WebView = NEW_WEB_VIEW,
) {
    companion object {
        val NEW_WEB_VIEW: (Context) -> WebView = { WebView(it) }
        private const val MODAL_MARGIN_DP = 16f
        private const val PAGE_BACKGROUND = "(function(){var s=function(e){return e?getComputedStyle(e).backgroundColor:'';};" +
            "var b=s(document.body);return !b||/^rgba\\(.*,\\s*0\\)$/.test(b)?s(document.documentElement):b;})()"
    }

    private val page = presentation.campaign.content.page
    private val html = page?.html ?: ""
    private val layout = page?.layout ?: InAppLayout.FULL
    private val dismissible = presentation.campaign.content.dismissible
    private val dims = layout == InAppLayout.MODAL &&
        InAppJson.string(InAppStyle.content(presentation.campaign).get("backdrop")) != "none"
    private val main = Handler(Looper.getMainLooper())
    private var removeTransport: (() -> Unit)? = null
    private var rendererGone = false
    private var retiring: WebView? = null
    private var activity: Activity? = null
    private var window: InAppWindow? = null
    private var card: InAppHtmlCard? = null
    private var themeColor: Int? = null
    private var pageColor: Int? = null
    private var contentHeightDp = 0f
    private var availableDp = 0f
    private var safeBottom = 0
    private var imeBottom = 0
    // A banner leaves the app its keyboard until the person touches the page.
    private var focusable = !layout.isBanner

    val edge: InAppWindow.Edge = when (layout) {
        InAppLayout.BANNER_TOP -> InAppWindow.Edge.TOP
        InAppLayout.BANNER_BOTTOM -> InAppWindow.Edge.BOTTOM
        InAppLayout.FULL, InAppLayout.MODAL -> InAppWindow.Edge.FULL
    }

    val orientation: InAppOrientation get() = presentation.campaign.content.orientation

    /** Light icons over a dark page colour; only full and banner_top sit under the status bar. Null keeps the app's own. */
    val lightStatusIcons: Boolean?
        get() {
            if (!session.isShown || (layout != InAppLayout.FULL && layout != InAppLayout.BANNER_TOP)) return null
            val color = themeColor ?: pageColor ?: return null
            return !InAppHtmlDocument.isLight(color)
        }

    internal var root: FrameLayout? = null
        private set
    internal var webView: WebView? = null
        private set

    internal val session = InAppHtmlSession(
        presentation = presentation,
        schedule = schedule,
        requestPush = { done -> actions.requestPushPermission(openSettingsWhenDenied = false, done = done) },
        evaluate = { script -> webView?.evaluateJavascript(script, null) },
        onHeight = { dp -> heightReported(dp.toFloat()) },
        onReveal = { reveal() },
        finish = { outcome ->
            teardown()
            finish(outcome)
        },
    )

    fun attach(activity: Activity): View {
        this.activity = activity
        themeColor = InAppHtmlDocument.themeColor(html, isDark(activity))

        val root = FrameLayout(activity)
        val card = InAppHtmlCard(activity, if (layout == InAppLayout.MODAL) dp(InAppHtmlDocument.MODAL_WIDTH_DP) else 0, swipe())
        if (layout == InAppLayout.MODAL) {
            val radius = dp(14f).toFloat()
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            card.clipToOutline = true
        }
        if (layout != InAppLayout.FULL) card.elevation = dp(10f).toFloat()

        val web = webView ?: if (session.isFinished) null else createWebView(activity)
        if (web != null) {
            (web.parent as? ViewGroup)?.removeView(web)
            (web.context as? MutableContextWrapper)?.baseContext = activity
            card.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, if (layout == InAppLayout.FULL) MATCH_PARENT else dp(1f)))
        }
        card.addView(closeButton(activity))
        root.addView(card, cardParams())
        if (dims) root.setBackgroundColor(InAppStyle.BACKDROP)
        if (dismissible && layout == InAppLayout.MODAL) {
            // The card takes its own touches, so only a tap outside it reaches the root.
            card.isClickable = true
            root.setOnClickListener { if (session.isShown) session.leave("backdrop") }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyHeight() }
        root.alpha = if (session.isShown) 1f else 0f
        this.root = root
        this.card = card
        paintEdges()
        applyHeight()
        return root
    }

    fun bind(window: InAppWindow) {
        this.window = window
        applyWindowState()
        applyStatusBar()
    }

    fun detach() {
        webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            (web.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
        }
        root = null
        card = null
        window = null
        activity = null
    }

    fun leave(reason: String) = session.leave(reason)

    /** InAppWindow hands every back here. Until the page shows its window is not focusable, so back reaches the app. */
    fun onBack() {
        if (session.isShown && dismissible) session.leave("close_button")
    }

    fun applyInsets(safe: Insets, imeBottom: Int) {
        val root = root ?: return
        val card = card ?: return
        safeBottom = safe.bottom
        this.imeBottom = imeBottom
        val bottom = maxOf(safe.bottom, imeBottom)
        val margin = dp(MODAL_MARGIN_DP)
        when (layout) {
            InAppLayout.FULL -> (card.layoutParams as FrameLayout.LayoutParams).setMargins(safe.left, safe.top, safe.right, bottom)
            InAppLayout.MODAL -> root.setPadding(safe.left + margin, safe.top + margin, safe.right + margin, bottom + margin)
            InAppLayout.BANNER_TOP -> card.setPadding(safe.left, safe.top, safe.right, 0)
            InAppLayout.BANNER_BOTTOM -> card.setPadding(safe.left, 0, safe.right, bottom)
        }
        card.requestLayout()
        applyHeight()
    }

    /** The presenter could not draw it: everything goes, and nobody is told. */
    fun discard() {
        session.abandon()
        teardown()
    }

    // Window state

    private fun applyWindowState() {
        val shown = window ?: return
        shown.setTouchable(session.isShown)
        shown.setFocusable(session.isShown && focusable)
    }

    private fun applyStatusBar() {
        val icons = lightStatusIcons ?: return
        val dialogWindow = window?.dialog?.window ?: return
        WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView).isAppearanceLightStatusBars = !icons
    }

    // WebView

    /** Null when this device has no working WebView (missing, or being updated); the message then ends unshown. */
    private fun createWebView(activity: Activity): WebView? {
        var made: WebView? = null
        val web = try {
            makeWebView(MutableContextWrapper(activity)).also {
                made = it
                configureWebView(it)
                // Both transports reach the page only from its next load on.
                removeTransport = InAppHtmlTransport.install(it) { raw, mainFrame -> session.receive(raw, mainFrame) }
            }
        } catch (e: Exception) {
            return unavailable(made, e)
        } catch (e: LinkageError) {
            return unavailable(made, e)
        }
        webView = web
        session.start()
        val script = InAppHtmlDocument.injection(session.token, presentation.user, presentation.event, actions.pushCapability())
        web.loadDataWithBaseURL(null, InAppHtmlDocument.document(html, script), "text/html", "utf-8", null)
        return web
    }

    private fun unavailable(made: WebView?, error: Throwable): WebView? {
        DebugLogger.instance.log(LogLevel.ERROR, "In-app message ${presentation.campaign.id} needs a WebView this device cannot create: ${error.message}")
        removeTransport = null
        runCatching { made?.destroy() }
        // Not now: the presenter is still drawing.
        main.post { session.leave("app") }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView(web: WebView) {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setBackgroundColor(Color.WHITE)
        web.webViewClient = client
        if (layout.isBanner) {
            web.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !focusable) {
                    focusable = true
                    applyWindowState()
                }
                false
            }
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            session.blocksNavigation(request.isForMainFrame, request.hasGesture(), request.url.toString())

        @Deprecated("Called on API 21 to 23 only, which give no frame and no gesture.")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            session.blocksNavigation(mainFrame = true, gesture = false, url = url)

        // shouldOverrideUrlLoading never sees a POST: a 204 keeps the page where it is.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (request.isForMainFrame && InAppHtmlSession.isRemote(request.url.scheme)) noContent() else null

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (session.blocksPageStart()) view.stopLoading()
        }

        // Returning false would end the app with the page.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            DebugLogger.instance.log(LogLevel.ERROR, "In-app message ${presentation.campaign.id} page stopped")
            if (view === webView) {
                rendererGone = true
                session.leave("app")
                teardown()
            } else if (view === retiring) {
                retiring = null
                view.destroy()
            }
            return true
        }
    }

    private fun noContent() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun teardown() {
        // A finished message keeps nothing of its Activity.
        root = null
        card = null
        window = null
        activity = null
        val web = webView ?: return
        webView = null
        web.setOnTouchListener(null)
        (web.parent as? ViewGroup)?.removeView(web)
        (web.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
        val remove = removeTransport
        removeTransport = null
        // A WebView whose page process died must not be used again, only destroyed.
        if (rendererGone) {
            web.destroy()
            return
        }
        web.stopLoading()
        retiring = web
        // Next turn: this may run inside one of the page's own callbacks. A renderer that dies first destroys it instead.
        main.post {
            if (retiring !== web) return@post
            retiring = null
            remove?.invoke()
            web.destroy()
        }
    }

    // Showing

    private fun reveal() {
        root?.alpha = 1f
        applyWindowState()
        root?.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        if (themeColor == null && layout != InAppLayout.MODAL) readPageBackground()
        applyStatusBar()
    }

    private fun readPageBackground() {
        webView?.evaluateJavascript(PAGE_BACKGROUND) { result ->
            pageColor = InAppHtmlDocument.pageBackground(result)
            paintEdges()
            applyStatusBar()
        }
    }

    private fun paintEdges() {
        val edgeColor = themeColor ?: pageColor ?: InAppHtmlDocument.WHITE
        when (layout) {
            InAppLayout.FULL -> root?.setBackgroundColor(edgeColor)
            InAppLayout.MODAL -> card?.setBackgroundColor(InAppHtmlDocument.WHITE)
            InAppLayout.BANNER_TOP, InAppLayout.BANNER_BOTTOM -> card?.setBackgroundColor(edgeColor)
        }
    }

    // Layout

    private fun cardParams(): FrameLayout.LayoutParams = when (layout) {
        InAppLayout.FULL -> FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        InAppLayout.MODAL -> FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER)
        InAppLayout.BANNER_TOP -> FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP)
        InAppLayout.BANNER_BOTTOM -> FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
    }

    private fun heightReported(dp: Float) {
        refreshAvailable()
        contentHeightDp = dp
        applyHeight()
    }

    private fun refreshAvailable() {
        val activity = activity ?: return
        val now = safeHeightDp(activity)
        // A turn or a resized window: largest again until the page reports its new height.
        if (availableDp > 0f && abs(now - availableDp) > 1f) contentHeightDp = 0f
        availableDp = now
    }

    private fun applyHeight() {
        refreshAvailable()
        val web = webView ?: return
        if (layout == InAppLayout.FULL || availableDp <= 0f) return
        val wanted = if (contentHeightDp > 0f) contentHeightDp else Float.MAX_VALUE
        val target = dp(InAppHtmlDocument.clamp(wanted, layout, roomDp()) ?: return)
        val params = web.layoutParams ?: return
        if (params.height != target) {
            params.height = target
            web.layoutParams = params
        }
    }

    /** A modal sits inside its margins and above the keyboard; the keyboard does not count as a turn, so it is kept apart. */
    private fun roomDp(): Float {
        if (layout != InAppLayout.MODAL) return availableDp
        val keyboardDp = maxOf(imeBottom - safeBottom, 0) / density()
        return availableDp - keyboardDp - 2 * MODAL_MARGIN_DP
    }

    private fun safeHeightDp(activity: Activity): Float {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val height = if (decor.height > 0) decor.height else metrics.heightPixels
        val safe = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return (height - (safe?.top ?: 0) - (safe?.bottom ?: 0)) / metrics.density
    }

    // Close and gestures

    /** Drawn by the SDK whatever the page or close_button says, so a broken page can never trap anyone. */
    private fun closeButton(context: Context): View {
        val disc = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(InAppStyle.BLACK_45)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(context).apply {
            id = R.id.grovs_in_app_close
            contentDescription = context.getString(R.string.grovs_in_app_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { session.leave("close_button") }
            addView(disc, FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8f)
                marginEnd = dp(8f)
            })
            layoutParams = FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.TOP or Gravity.END)
        }
    }

    private fun swipe(): ((Float) -> Unit)? {
        if (!dismissible || !layout.isBanner) return null
        return { velocityY ->
            val away = if (layout == InAppLayout.BANNER_TOP) velocityY < 0 else velocityY > 0
            if (away && session.isShown) session.leave("swipe")
        }
    }

    private fun isDark(activity: Activity) =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun density(): Float = (activity ?: webView?.context)?.resources?.displayMetrics?.density ?: 1f

    private fun dp(value: Float): Int = (value * density()).roundToInt()
}

/** The page's card: a width cap for the modal, and a fling watcher for banners that never takes the page's touches. */
private class InAppHtmlCard(context: Context, private val maxWidth: Int, onFling: ((velocityY: Float) -> Unit)?) : FrameLayout(context) {
    private val detector = onFling?.let { fling ->
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityY) > abs(velocityX)) fling(velocityY)
                return false
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        detector?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val capped = if (maxWidth in 1 until width) MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(capped, heightMeasureSpec)
    }
}
