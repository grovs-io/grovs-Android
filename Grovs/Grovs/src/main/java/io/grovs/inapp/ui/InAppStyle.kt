package io.grovs.inapp.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppTemplate
import kotlin.math.pow

/** Colours and sizes of the native templates, as ARGB ints so plain JUnit can check them. */
internal object InAppStyle {
    const val BACKDROP = 0x8C000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val DARK_CARD = 0xFF1C1C1E.toInt()
    const val NEAR_BLACK = 0xFF121212.toInt()
    const val GREY = 0xFF545454.toInt()
    const val PRIMARY = 0xFF2A86FF.toInt()
    const val QUIET_LIGHT = 0xFFE9EEF5.toInt()
    const val WHITE_16 = 0x29FFFFFF
    const val BLACK_6 = 0x0F000000
    const val BLACK_45 = 0x73000000

    /** `#RRGGBB` or `#RRGGBBAA`; anything else is ignored. */
    fun color(value: JsonElement?): Int? {
        val written = InAppJson.string(value) ?: return null
        if (!written.startsWith("#") || (written.length != 7 && written.length != 9)) return null
        val hex = written.substring(1)
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val number = hex.toLong(16)
        val rgba = if (hex.length == 6) (number shl 8) or 0xFF else number
        val alpha = (rgba and 0xFF).toInt()
        val rgb = (rgba ushr 8).toInt() and 0xFFFFFF
        return (alpha shl 24) or rgb
    }

    private fun channel(argb: Int, shift: Int) = ((argb shr shift) and 0xFF) / 255.0

    fun isDark(argb: Int): Boolean =
        (channel(argb, 16) * 299 + channel(argb, 8) * 587 + channel(argb, 0) * 114) / 1000 < 0.5

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    internal fun luminance(argb: Int): Double {
        fun linear(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(channel(argb, 16)) + 0.7152 * linear(channel(argb, 8)) +
            0.0722 * linear(channel(argb, 0))
    }

    /** Light status bar icons over a colour darker than 0.179. */
    fun wantsLightIcons(argb: Int): Boolean = luminance(argb) < 0.179

    private fun objectAt(parent: JsonObject, key: String): JsonObject =
        parent.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

    fun content(campaign: InAppCampaign): JsonObject = objectAt(campaign.raw, "content")

    fun colors(campaign: InAppCampaign): JsonObject = objectAt(content(campaign), "colors")

    fun closeAsked(campaign: InAppCampaign): Boolean = InAppJson.bool(content(campaign).get("close_button")) != false

    fun showsClose(campaign: InAppCampaign): Boolean = campaign.content.dismissible && closeAsked(campaign)

    fun dims(campaign: InAppCampaign): Boolean =
        !campaign.template.isBanner && InAppJson.string(content(campaign).get("backdrop")) != "none"

    fun cornerRadiusDp(template: InAppTemplate): Int = when (template) {
        InAppTemplate.COVER, InAppTemplate.COVER_IMAGE, InAppTemplate.HEADER, InAppTemplate.FOOTER -> 0
        else -> 14
    }

    fun hasShadow(template: InAppTemplate): Boolean =
        template != InAppTemplate.COVER && template != InAppTemplate.COVER_IMAGE

    fun preferredWidthDp(template: InAppTemplate): Int = when (template) {
        InAppTemplate.INTERSTITIAL, InAppTemplate.INTERSTITIAL_IMAGE -> 480
        else -> 300
    }

    fun pictureRatio(template: InAppTemplate): Float = when (template) {
        InAppTemplate.INTERSTITIAL, InAppTemplate.INTERSTITIAL_IMAGE -> 9f / 16f
        InAppTemplate.HALF_INTERSTITIAL, InAppTemplate.HALF_INTERSTITIAL_IMAGE -> 10f / 13f
        else -> 0f
    }

    /** `key` is `portrait` or `landscape`; only https pictures are ever downloaded. */
    fun imageUrl(campaign: InAppCampaign, key: String): String? =
        InAppJson.string(objectAt(content(campaign), "image").get(key))?.takeIf { it.startsWith("https://") }
}

/** The card's colours: its own background when one is given, else the app's light or dark appearance. */
internal class InAppPalette(campaign: InAppCampaign, private val night: Boolean) {
    private val colors = InAppStyle.colors(campaign)
    private val background = InAppStyle.color(colors.get("background"))
    val hasBackground = background != null
    val onDark = background?.let(InAppStyle::isDark) ?: false

    fun pick(light: Int, dark: Int): Int =
        if (hasBackground) (if (onDark) dark else light) else (if (night) dark else light)

    val card: Int = background ?: if (night) InAppStyle.DARK_CARD else InAppStyle.WHITE
    val plainText = pick(InAppStyle.NEAR_BLACK, InAppStyle.WHITE)
    val quiet = pick(InAppStyle.QUIET_LIGHT, InAppStyle.WHITE_16)
    val title = InAppStyle.color(colors.get("title")) ?: plainText
    val message = InAppStyle.color(colors.get("message")) ?: plainText

    fun buttonBackground(index: Int, tints: JsonObject): Int = InAppStyle.color(tints.get("background"))
        ?: when {
            index == 0 -> InAppStyle.PRIMARY
            hasBackground -> InAppStyle.QUIET_LIGHT
            else -> quiet
        }

    fun buttonText(index: Int, tints: JsonObject): Int = InAppStyle.color(tints.get("text"))
        ?: when {
            index == 0 -> InAppStyle.WHITE
            hasBackground -> InAppStyle.NEAR_BLACK
            else -> plainText
        }
}
