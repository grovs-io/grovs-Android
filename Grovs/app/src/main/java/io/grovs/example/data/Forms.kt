package io.grovs.example.data

import android.os.Build
import io.grovs.model.events.PaymentEventType

enum class TriState(val label: String) {
    DEFAULT("Default"), ON("On"), OFF("Off");

    fun toBoolean(): Boolean? = when (this) {
        DEFAULT -> null
        ON -> true
        OFF -> false
    }
}

enum class CallStyle(val label: String) { COROUTINE("coroutine"), CALLBACK("callback") }

data class Redirect(val url: String = "", val openAppIfInstalled: Boolean = false)

const val SAMPLE_IMAGE_1 = "https://picsum.photos/seed/grovs1/1200/630"
const val SAMPLE_IMAGE_2 = "https://picsum.photos/seed/grovs2/1200/630"
val SAMPLE_REDIRECTS = listOf("https://www.youtube.com", "https://www.google.com", "https://grovs.io")

data class LinkForm(
    val title: String = "Test title",
    val subtitle: String = "Test subtitle",
    val data: List<KeyValue> = listOf(KeyValue("param1", "Test value")),
    val imageUrl: String = "",
    val tags: List<String> = emptyList(),
    val utmOn: Boolean = false,
    val utmSource: String = "test_source",
    val utmMedium: String = "test_medium",
    val utmCampaign: String = "test_campaign",
    val redirectsOn: Boolean = false,
    val android: Redirect = Redirect("https://www.youtube.com"),
    val ios: Redirect = Redirect("https://www.google.com"),
    val desktop: Redirect = Redirect(),
    val previewAndroid: TriState = TriState.DEFAULT,
    val previewIos: TriState = TriState.DEFAULT,
    val clipboardAndroid: TriState = TriState.DEFAULT,
    val clipboardIos: TriState = TriState.DEFAULT,
) {
    fun moreSummary(): String = listOf(
        if (utmOn) "UTM on" else "UTM off",
        if (tags.isEmpty()) "no tags" else "${tags.size} tags",
        if (imageUrl.isBlank()) "no image" else "image",
        if (redirectsOn) "redirects on" else "no redirects",
    ).joinToString(" · ")

    fun overridesSummary(): String {
        val set = listOf(previewAndroid, previewIos, clipboardAndroid, clipboardIos).count { it != TriState.DEFAULT }
        return if (set == 0) "all default" else "$set set"
    }
}

data class EventForm(
    val name: String = "demo_tapped",
    val properties: List<KeyValue> = emptyList(),
    val tags: List<String> = emptyList(),
)

data class ScreenForm(
    val name: String = "Checkout",
    val properties: List<KeyValue> = emptyList(),
)

data class PurchaseForm(
    val type: PaymentEventType = PaymentEventType.BUY,
    val amount: String = "4.99",
    val currency: String = "USD",
    val productId: String = "pro_monthly",
    val startDaysAgo: Int = 0,
    val playJson: String = "",
) {
    fun label(): String {
        val cents = parseAmountToCents(amount) ?: return "Log purchase"
        return "Log ${currencySymbol(currency)}${formatCents(cents)} ${type.name.lowercase()}"
    }

    fun fullLabel(): String = "${label()} · $currency · $productId"

    fun isValid(): Boolean = parseAmountToCents(amount) != null && currency.isNotBlank() && productId.isNotBlank()
}

data class Identity(val identifier: String?, val attributes: List<KeyValue>)

val DEFAULT_ATTRIBUTES = listOf(KeyValue("param1", "value1"), KeyValue("param2", "123"), KeyValue("param3", "true"))

fun deviceName(): String = "@${Build.MANUFACTURER} ${Build.MODEL}"
