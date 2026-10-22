package io.grovs.example.data

import io.grovs.model.events.PaymentEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormsJsonTest {

    @Test
    fun linkFormRoundTrip() {
        val form = LinkForm(
            title = "T", data = listOf(KeyValue("a", "1", ValueType.TEXT)), imageUrl = SAMPLE_IMAGE_2,
            tags = listOf("qa"), utmOn = true, utmCampaign = "spring", redirectsOn = true,
            android = Redirect("https://a.com", true), previewIos = TriState.ON, clipboardAndroid = TriState.OFF,
        )
        assertEquals(form, decodeLinkForm(encodeLinkForm(form)))
    }

    @Test
    fun missingOrBadJsonGivesDefaults() {
        assertEquals(LinkForm(), decodeLinkForm(null))
        assertEquals(LinkForm(), decodeLinkForm("{oops"))
        assertEquals(LinkForm(), decodeLinkForm("{}"))
        assertEquals(PurchaseForm(), decodePurchaseForm("{}"))
    }

    @Test
    fun otherFormsRoundTrip() {
        val event = EventForm("signup", listOf(KeyValue("level", "3")), listOf("qa"))
        assertEquals(event, decodeEventForm(encodeEventForm(event)))
        val screen = ScreenForm("Paywall", listOf(KeyValue("from", "home")))
        assertEquals(screen, decodeScreenForm(encodeScreenForm(screen)))
        val purchase = PurchaseForm(PaymentEventType.REFUND, "9.99", "EUR", "coins_100", 30, "{}")
        assertEquals(purchase, decodePurchaseForm(encodePurchaseForm(purchase)))
    }

    @Test
    fun identityKeepsNullIdentifier() {
        val fallback = Identity("@Pixel", DEFAULT_ATTRIBUTES)
        val none = Identity(null, listOf(KeyValue("plan", "pro")))
        assertEquals(none, decodeIdentity(encodeIdentity(none), fallback))
        assertEquals(fallback, decodeIdentity(null, fallback))
        assertNull(decodeIdentity(encodeIdentity(none), fallback).identifier)
    }

    @Test
    fun stringListsRoundTrip() {
        assertEquals(listOf("a", "b"), decodeStrings(encodeStrings(listOf("a", "b"))))
        assertEquals(emptyList<String>(), decodeStrings("bad"))
    }

    @Test
    fun summaries() {
        assertEquals("UTM off · no tags · no image · no redirects", LinkForm().moreSummary())
        assertEquals("all default", LinkForm().overridesSummary())
        assertEquals("2 set", LinkForm(previewIos = TriState.ON, clipboardIos = TriState.OFF).overridesSummary())
        assertEquals("Log $4.99 buy", PurchaseForm().label())
        assertEquals("Log $4.99 buy · USD · pro_monthly", PurchaseForm().fullLabel())
        assertEquals("Log purchase", PurchaseForm(amount = "x").label())
    }
}
