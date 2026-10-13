package io.grovs.inapp.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.graphics.Insets
import io.grovs.R
import io.grovs.inapp.InAppImages
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InAppCardViewTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var activity: Activity
    private lateinit var images: InAppImages
    private val outcomes = mutableListOf<InAppOutcome>()
    private val width = 393
    private val height = 852

    private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        images = InAppUiFixtures.images(folder.newFolder("img"), mapOf(
            InAppUiFixtures.PORTRAIT to png(200, 400), InAppUiFixtures.LANDSCAPE to png(400, 200)))
    }

    private val withImage = """"image":{"portrait":"${InAppUiFixtures.PORTRAIT}","landscape":"${InAppUiFixtures.LANDSCAPE}"}"""

    private fun make(changes: String = "{}", message: String = "Welcome", night: Boolean = false,
                     w: Int = width, h: Int = height, safe: Insets = Insets.of(0, 24, 0, 48)): InAppCardView {
        val json = InAppUiFixtures.json(changes)
        InAppUiFixtures.preload(images, json)
        val campaign = InAppUiFixtures.campaign(changes)
        val presentation = InAppUiFixtures.presentation(campaign, message = if (campaign.template.isImageOnly) null else message)
        val view = InAppCardView(activity, InAppMessageSession(presentation) { outcomes += it }, images, night)
        view.screenHeight = h
        view.applyInsets(safe, 0)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    /** Where `view` sits inside `root`, from the laid out frames. */
    private fun bounds(root: View, view: View): Rect {
        var x = 0
        var y = 0
        var node: View? = view
        while (node != null && node !== root) {
            val parent = node.parent as? View
            x += node.left - (parent?.scrollX ?: 0)
            y += node.top - (parent?.scrollY ?: 0)
            node = parent
        }
        return Rect(x, y, x + view.width, y + view.height)
    }

    private fun find(root: View, tag: String): View = root.findViewWithTag(tag) ?: error("no view tagged $tag")

    private val nativeTemplates = listOf("cover", "interstitial", "half_interstitial", "header", "footer")
    private val imageTemplates = listOf("cover_image", "interstitial_image", "half_interstitial_image")

    @Test
    fun everyTemplateDrawsInsideTheScreen() {
        for (template in nativeTemplates + imageTemplates) {
            val view = make("""{"template":"$template","content":{$withImage}}""")
            val card = bounds(view, view.card)
            assertTrue("$template $card", card.left >= 0 && card.top >= 0 && card.right <= width && card.bottom <= height)
            assertTrue("$template has a picture", view.findViewById<View>(R.id.grovs_in_app_picture) != null)
        }
    }

    @Test
    fun cardsAreCentredAtTheirPreferredWidthAndBannersSpanTheScreen() {
        assertEquals(width - 32, make("""{"template":"interstitial"}""").card.width)
        assertEquals(300, make("""{"template":"half_interstitial"}""").card.width)
        val header = make("""{"template":"header"}""")
        assertEquals(0, header.card.top)
        assertEquals(width, header.card.width)
        val footer = make("""{"template":"footer"}""")
        assertEquals(height, footer.card.bottom)
        val cover = make("""{"template":"cover"}""")
        assertEquals(Rect(0, 0, width, height), Rect(cover.card.left, cover.card.top, cover.card.right, cover.card.bottom))
    }

    @Test
    fun bannersDoNotDimTheApp() {
        assertEquals(InAppStyle.BACKDROP, (make().background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(0, (make("""{"template":"header"}""").background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(0, (make("""{"content":{"backdrop":"none"}}""").background as android.graphics.drawable.ColorDrawable).color)
    }

    @Test
    fun theCloseButtonSitsInTheTopEndCornerOfEveryCard() {
        for (template in nativeTemplates) {
            val view = make("""{"template":"$template"}""")
            val close = view.findViewById<View>(R.id.grovs_in_app_close)
            val inCard = bounds(view.card, close)
            val safeTop = if (template == "cover" || template == "header") 24 else 0
            val gap = if (template == "header" || template == "footer") 10 else 12
            assertEquals(template, safeTop + gap - 10, inCard.top)
            assertEquals(template, view.card.width - 2, inCard.right)
            assertEquals(48, close.width)
        }
    }

    @Test
    fun theCloseButtonCanBeLeftOutAndADismissibleFalseCardHasNone() {
        assertNull(make("""{"content":{"close_button":false}}""").findViewById(R.id.grovs_in_app_close))
        assertNull(make("""{"content":{"dismissible":false,"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}""")
            .findViewById(R.id.grovs_in_app_close))
    }

    @Test
    fun onlyTheFirstPieceOfTextStopsShortOfTheClose() {
        val view = make()
        val title = view.findViewById<View>(R.id.grovs_in_app_title)
        val message = view.findViewById<View>(R.id.grovs_in_app_message)
        assertEquals(36, (title.layoutParams as ViewGroup.MarginLayoutParams).marginEnd)
        assertEquals(0, (message.layoutParams as ViewGroup.MarginLayoutParams).marginEnd)
    }

    @Test
    fun closeReportsOnce() {
        val close = make().findViewById<View>(R.id.grovs_in_app_close)
        close.performClick()
        close.performClick()
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Dismissed("close_button")), outcomes)
    }

    @Test
    fun aButtonReportsItsIdAndItsFilledLink() {
        val changes = """{"content":{"buttons":[
            {"id":"b1","text":"Later","action":{"type":"close"}},
            {"id":"b2","text":"Go","action":{"type":"open_url","url":"https://acme.example/{{user.plan}}"}}],
            "tap_action":{"type":"open_url","url":"https://acme.example/tap"}}}"""
        val json = InAppUiFixtures.json(changes)
        val campaign = InAppUiFixtures.campaign(changes)
        val presentation = InAppUiFixtures.presentation(campaign,
            urls = listOf("https://acme.example/pro", "https://acme.example/tap"))
        InAppUiFixtures.preload(images, json)
        val view = InAppCardView(activity, InAppMessageSession(presentation) { outcomes += it }, images, false)
        find(view, "grovs-in-app-button-b2").performClick()
        val clicked = outcomes.single() as InAppOutcome.Clicked
        assertEquals("b2", clicked.buttonId)
        assertEquals(InAppActionType.OPEN_URL, clicked.action.type)
        assertEquals("https://acme.example/pro", clicked.action.url)
    }

    @Test
    fun theTapActionIsOnTheCardAndOnThePictureForImageOnlyTemplates() {
        val tap = """"tap_action":{"type":"key_value","payload":{"k":"v"}}"""
        make("""{"content":{$tap}}""").card.performClick()
        assertEquals(InAppActionType.KEY_VALUE, (outcomes.removeAt(0) as InAppOutcome.Clicked).action.type)
        val image = make("""{"template":"interstitial_image","content":{$withImage,$tap}}""")
        val picture = image.findViewById<View>(R.id.grovs_in_app_picture)
        assertEquals("Continue", picture.contentDescription)
        picture.performClick()
        assertNull((outcomes.single() as InAppOutcome.Clicked).buttonId)
    }

    @Test
    fun aBackdropTapClosesOnlyADismissibleCard() {
        make().performClick()
        assertEquals(InAppOutcome.Dismissed("backdrop"), outcomes.single())
        outcomes.clear()
        make("""{"content":{"dismissible":false,"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}""").performClick()
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun longTextScrollsAndKeepsTheButtonsInside() {
        RuntimeEnvironment.setFontScale(2f)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = make("""{"content":{"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}""",
            message = "Long text. ".repeat(400))
        val scroll = view.findViewById<ScrollView>(R.id.grovs_in_app_text)
        assertTrue(scroll.height < scroll.getChildAt(0).height)
        val buttons = bounds(view, view.findViewById(R.id.grovs_in_app_buttons))
        assertTrue(buttons.bottom <= bounds(view, view.card).bottom)
    }

    @Test
    fun shortTextDoesNotScroll() {
        val scroll = make().findViewById<ScrollView>(R.id.grovs_in_app_text)
        assertEquals(scroll.getChildAt(0).height, scroll.height)
    }

    @Test
    fun aCoverWithNoPictureKeepsItsTextInTheMiddle() {
        val view = make("""{"template":"cover","content":{"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}""")
        val text = bounds(view, view.findViewById(R.id.grovs_in_app_text))
        val buttons = bounds(view, view.findViewById(R.id.grovs_in_app_buttons))
        val above = text.top - 24
        val between = buttons.top - text.bottom
        assertTrue("$above vs $between", abs(above - between) <= 1)
    }

    @Test
    fun aFullScreenPictureRunsUnderTheStatusBarButTextDoesNot() {
        val cover = make("""{"template":"cover","content":{$withImage}}""")
        assertEquals(0, bounds(cover, cover.findViewById(R.id.grovs_in_app_picture)).top)
        val coverImage = make("""{"template":"cover_image","content":{$withImage}}""")
        assertEquals(Rect(0, 0, width, height), bounds(coverImage, coverImage.findViewById(R.id.grovs_in_app_picture)))
        val plain = make("""{"template":"cover"}""")
        assertTrue(bounds(plain, plain.findViewById(R.id.grovs_in_app_text)).top >= 24)
    }

    @Test
    fun thePictureFollowsTheOrientation() {
        val tall = make("""{"content":{$withImage}}""")
        val wide = make("""{"content":{$withImage}}""", w = height, h = width)
        assertTrue(tall.shownImage!!.height > tall.shownImage!!.width)
        assertTrue(wide.shownImage!!.width > wide.shownImage!!.height)
    }

    @Test
    fun aLargePictureIsDecodedNoBiggerThanTheScreenNeeds() {
        images = InAppUiFixtures.images(folder.newFolder("big"), mapOf(InAppUiFixtures.PORTRAIT to png(2000, 2000)))
        val view = make("""{"content":{"image":{"portrait":"${InAppUiFixtures.PORTRAIT}"}}}""")
        assertEquals(1000, view.shownImage!!.width)
    }

    @Test
    @Config(qualifiers = "w1440dp-h3200dp-mdpi")
    fun aPictureOnABigScreenIsNotCappedBelowTheScreen() {
        images = InAppUiFixtures.images(folder.newFolder("huge"), mapOf(InAppUiFixtures.PORTRAIT to png(2400, 2400)))
        val view = make("""{"template":"cover","content":{"image":{"portrait":"${InAppUiFixtures.PORTRAIT}"}}}""",
            w = 1440, h = 3200)
        assertEquals(2400, view.shownImage!!.width)
    }

    private fun rtl(view: InAppCardView, w: Int = width, h: Int = height): InAppCardView {
        view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        assertEquals(View.LAYOUT_DIRECTION_RTL, view.card.layoutDirection)
        return view
    }

    private fun supportRtl() {
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
    }

    @Test
    fun inRightToLeftTheCloseSitsAtTheLeftAndClearsTheBannerButtons() {
        supportRtl()
        val changes = """{"template":"footer","content":{"buttons":[
            {"id":"b1","text":"One","action":{"type":"close"}},{"id":"b2","text":"Two","action":{"type":"close"}}]}}"""
        val campaign = InAppUiFixtures.campaign(changes)
        val presentation = InAppUiFixtures.presentation(campaign, title = null, message = null)
        val view = InAppCardView(activity, InAppMessageSession(presentation) { outcomes += it }, images, false)
        view.screenHeight = height
        view.applyInsets(Insets.of(0, 24, 0, 48), 0)
        rtl(view)
        val close = bounds(view, view.findViewById(R.id.grovs_in_app_close))
        assertTrue("$close", close.left < width / 2)
        for (id in listOf("b1", "b2")) {
            val button = bounds(view, find(view, "grovs-in-app-button-$id"))
            assertTrue("$id $button overlaps $close", !Rect.intersects(button, Rect(close).apply { inset(10, 10) }))
        }
    }

    @Test
    fun inRightToLeftTheCloseKeepsClearOfTheLeftInset() {
        supportRtl()
        val view = rtl(make("""{"template":"header"}""", safe = Insets.of(40, 24, 0, 48)))
        assertEquals(42, bounds(view.card, view.findViewById(R.id.grovs_in_app_close)).left)
    }

    /** A click is posted, and a view runs posted work only once it is in a window. */
    private fun attached(view: InAppCardView): InAppCardView {
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return view
    }

    private fun tap(root: View, x: Int, y: Int) {
        val time = android.os.SystemClock.uptimeMillis()
        root.dispatchTouchEvent(android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0))
        root.dispatchTouchEvent(android.view.MotionEvent.obtain(time, time + 50, android.view.MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun aBannerButtonTakesTapsJustAboveAndBelowIt() {
        val changes = """{"template":"footer","content":{"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}"""
        val view = attached(make(changes))
        val button = bounds(view, find(view, "grovs-in-app-button-b1"))
        assertTrue("${button.height()}", button.height() < 40)
        tap(view, button.centerX(), button.top - 7)
        assertEquals("b1", (outcomes.single() as InAppOutcome.Clicked).buttonId)
        outcomes.clear()
        val again = attached(make(changes))
        // The drawn button's last row is bottom - 1.
        tap(again, button.centerX(), button.bottom - 1 + 7)
        assertEquals("b1", (outcomes.single() as InAppOutcome.Clicked).buttonId)
    }

    @Test
    fun aTapOnTheCardIsNotABackdropTapAndTheCardIsNotAButton() {
        val view = attached(make())
        assertTrue(!view.card.isClickable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
        val card = bounds(view, view.card)
        tap(view, card.left + 4, card.bottom - 4)
        assertTrue(outcomes.isEmpty())
        tap(view, 4, 4)
        assertEquals(InAppOutcome.Dismissed("backdrop"), outcomes.single())
    }

    private fun fling(root: View, x: Int, fromY: Int, toY: Int) {
        val time = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, y: Int) =
            root.dispatchTouchEvent(android.view.MotionEvent.obtain(time, time + at, action, x.toFloat(), y.toFloat(), 0))
        send(android.view.MotionEvent.ACTION_DOWN, 0, fromY)
        for (step in 1..5) send(android.view.MotionEvent.ACTION_MOVE, step * 10L, fromY + (toY - fromY) * step / 5)
        send(android.view.MotionEvent.ACTION_UP, 60, toY)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun aBannerSwipeLeavesAScrollingTextAlone() {
        val short = make("""{"template":"header"}""")
        val shortText = bounds(short, short.findViewById(R.id.grovs_in_app_text))
        fling(short, shortText.centerX(), shortText.bottom - 2, shortText.bottom - 2 - 200)
        assertEquals(InAppOutcome.Dismissed("swipe"), outcomes.single())
        outcomes.clear()
        val long = make("""{"template":"header"}""", message = "Long text. ".repeat(400))
        val scroll = long.findViewById<ScrollView>(R.id.grovs_in_app_text)
        assertTrue(scroll.canScrollVertically(1))
        val text = bounds(long, scroll)
        fling(long, text.centerX(), text.bottom - 2, text.bottom - 2 - 200)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun theRenderedTextAndButtonTitlesAreShown() {
        val changes = """{"content":{"title":"Hello {{user.name}}","buttons":[
            {"id":"b1","text":"Buy {{user.plan}}","action":{"type":"close"},"colors":{"background":"#112233","text":"#445566"}}]}}"""
        val campaign = InAppUiFixtures.campaign(changes)
        val presentation = InAppUiFixtures.presentation(campaign, title = "Hello Ana", buttons = listOf("Buy Pro"))
        val view = InAppCardView(activity, InAppMessageSession(presentation) { outcomes += it }, images, false)
        assertEquals("Hello Ana", view.findViewById<android.widget.TextView>(R.id.grovs_in_app_title).text.toString())
        val button = find(view, "grovs-in-app-button-b1") as android.widget.TextView
        assertEquals("Buy Pro", button.text.toString())
        assertEquals(0xFF445566.toInt(), button.currentTextColor)
        val fill = (button.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
        assertEquals(0xFF112233.toInt(), fill)
    }

    @Test
    fun aBannerSwipeTowardsItsEdgeCloses() {
        val header = make("""{"template":"header"}""")
        header.card.onSwipe!!.invoke(true)
        assertEquals(InAppOutcome.Dismissed("swipe"), outcomes.single())
        outcomes.clear()
        make("""{"template":"footer"}""").card.onSwipe!!.invoke(true)
        assertTrue(outcomes.isEmpty())
        assertNull(make("""{"template":"footer","content":{"dismissible":false,"buttons":[{"id":"b","text":"OK","action":{"type":"close"}}]}}""").card.onSwipe)
    }

    @Test
    fun theFooterRisesAboveTheKeyboardOverAStripInTheCardColour() {
        val view = make("""{"template":"footer"}""")
        view.applyInsets(Insets.of(0, 24, 0, 48), 300)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        assertEquals(height - 300, view.card.bottom)
        val skirt = view.findViewById<View>(R.id.grovs_in_app_skirt)
        assertEquals(View.VISIBLE, skirt.visibility)
        assertEquals(height - 300, skirt.top)
    }

    @Test
    fun withoutColoursTheCardFollowsTheAppearance() {
        assertEquals(InAppStyle.WHITE, make(night = false).palette.card)
        assertEquals(InAppStyle.DARK_CARD, make(night = true).palette.card)
        assertNotNull(make(night = true).findViewById<View>(R.id.grovs_in_app_title))
    }

    @Test
    fun anImageOnlyCardHasNoText() {
        val view = make("""{"template":"half_interstitial_image","content":{$withImage}}""")
        assertNull(view.findViewById(R.id.grovs_in_app_text))
        assertSame(view.card, view.findViewById<View>(R.id.grovs_in_app_card))
    }
}
