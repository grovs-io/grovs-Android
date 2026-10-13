package io.grovs.inapp.ui

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.InAppTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppStyleTest {
    private fun color(text: String) = InAppStyle.color(JsonPrimitive(text))

    @Test
    fun coloursAreReadOnlyAsHex() {
        assertEquals(0xFF2A86FF.toInt(), color("#2A86FF"))
        assertEquals(0x802A86FF.toInt(), color("#2a86ff80"))
        assertNull(color("red"))
        assertNull(color("#12345"))
        assertNull(color("#GG0000"))
        assertNull(color("#+12345"))
        assertNull(InAppStyle.color(JsonPrimitive(42)))
        assertNull(InAppStyle.color(null))
    }

    @Test
    fun aDarkCardIsMeasuredByPerceivedBrightness() {
        assertTrue(InAppStyle.isDark(0xFF000000.toInt()))
        assertTrue(InAppStyle.isDark(0xFF1C1C1E.toInt()))
        assertFalse(InAppStyle.isDark(0xFFFFFFFF.toInt()))
        assertFalse(InAppStyle.isDark(0xFFE9EEF5.toInt()))
    }

    @Test
    fun statusBarIconsFollowLuminance() {
        assertTrue(InAppStyle.wantsLightIcons(0xFF000000.toInt()))
        assertFalse(InAppStyle.wantsLightIcons(0xFF2A86FF.toInt()))
        assertTrue(InAppStyle.wantsLightIcons(0xFF1C1C1E.toInt()))
        assertFalse(InAppStyle.wantsLightIcons(0xFFFFFFFF.toInt()))
        assertFalse(InAppStyle.wantsLightIcons(0xFFFFD60A.toInt()))
    }

    @Test
    fun luminanceIsTheWcagValue() {
        assertEquals(0.0, InAppStyle.luminance(0xFF000000.toInt()), 0.0001)
        assertEquals(1.0, InAppStyle.luminance(0xFFFFFFFF.toInt()), 0.0001)
        assertEquals(0.2476, InAppStyle.luminance(0xFF2A86FF.toInt()), 0.001)
    }

    @Test
    fun templateSizesFollowTheAppendix() {
        assertEquals(0, InAppStyle.cornerRadiusDp(InAppTemplate.COVER))
        assertEquals(0, InAppStyle.cornerRadiusDp(InAppTemplate.HEADER))
        assertEquals(14, InAppStyle.cornerRadiusDp(InAppTemplate.HALF_INTERSTITIAL))
        assertEquals(480, InAppStyle.preferredWidthDp(InAppTemplate.INTERSTITIAL_IMAGE))
        assertEquals(300, InAppStyle.preferredWidthDp(InAppTemplate.HALF_INTERSTITIAL))
        assertEquals(9f / 16f, InAppStyle.pictureRatio(InAppTemplate.INTERSTITIAL), 0.0001f)
        assertEquals(10f / 13f, InAppStyle.pictureRatio(InAppTemplate.HALF_INTERSTITIAL_IMAGE), 0.0001f)
        assertFalse(InAppStyle.hasShadow(InAppTemplate.COVER_IMAGE))
        assertTrue(InAppStyle.hasShadow(InAppTemplate.FOOTER))
    }

    @Test
    fun closeAndBackdropFollowTheContent() {
        assertTrue(InAppStyle.showsClose(InAppUiFixtures.campaign()))
        assertFalse(InAppStyle.showsClose(InAppUiFixtures.campaign("""{"content":{"close_button":false}}""")))
        assertFalse(InAppStyle.showsClose(InAppUiFixtures.campaign(
            """{"content":{"dismissible":false,"buttons":[{"id":"b1","text":"OK","action":{"type":"close"}}]}}""")))
        assertTrue(InAppStyle.dims(InAppUiFixtures.campaign()))
        assertFalse(InAppStyle.dims(InAppUiFixtures.campaign("""{"content":{"backdrop":"none"}}""")))
        assertFalse(InAppStyle.dims(InAppUiFixtures.campaign("""{"template":"header"}""")))
    }

    @Test
    fun onlyHttpsImagesCount() {
        val campaign = InAppUiFixtures.campaign(
            """{"content":{"image":{"portrait":"https://cdn.example/p.png","landscape":"http://cdn.example/l.png"}}}""")
        assertEquals("https://cdn.example/p.png", InAppStyle.imageUrl(campaign, "portrait"))
        assertNull(InAppStyle.imageUrl(campaign, "landscape"))
    }

    @Test
    fun withoutAColourThePaletteFollowsTheAppearance() {
        val plain = InAppUiFixtures.campaign()
        assertEquals(InAppStyle.WHITE, InAppPalette(plain, night = false).card)
        assertEquals(InAppStyle.DARK_CARD, InAppPalette(plain, night = true).card)
        assertEquals(InAppStyle.NEAR_BLACK, InAppPalette(plain, night = false).plainText)
        assertEquals(InAppStyle.WHITE, InAppPalette(plain, night = true).plainText)
    }

    @Test
    fun aGivenColourDecidesTheTextWhateverTheAppearance() {
        val dark = InAppUiFixtures.campaign("""{"content":{"colors":{"background":"#101010","title":"#FF0000"}}}""")
        val palette = InAppPalette(dark, night = false)
        assertTrue(palette.onDark)
        assertEquals(InAppStyle.WHITE, palette.plainText)
        assertEquals(0xFFFF0000.toInt(), palette.title)
        assertEquals(InAppStyle.WHITE, palette.message)
    }

    @Test
    fun buttonColoursFollowTheirPlaceAndOverrides() {
        val custom = InAppUiFixtures.campaign("""{"content":{"colors":{"background":"#FFFFFF"}}}""")
        val palette = InAppPalette(custom, night = true)
        val none = JsonObject()
        assertEquals(InAppStyle.PRIMARY, palette.buttonBackground(0, none))
        assertEquals(InAppStyle.WHITE, palette.buttonText(0, none))
        assertEquals(InAppStyle.QUIET_LIGHT, palette.buttonBackground(1, none))
        assertEquals(InAppStyle.NEAR_BLACK, palette.buttonText(1, none))
        val tints = JsonObject().apply { addProperty("background", "#00FF00"); addProperty("text", "#0000FF") }
        assertEquals(0xFF00FF00.toInt(), palette.buttonBackground(0, tints))
        assertEquals(0xFF0000FF.toInt(), palette.buttonText(1, tints))
        val plain = InAppPalette(InAppUiFixtures.campaign(), night = true)
        assertEquals(InAppStyle.WHITE_16, plain.buttonBackground(1, none))
        assertEquals(InAppStyle.WHITE, plain.buttonText(1, none))
    }
}
