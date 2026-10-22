package io.grovs.example.actions

import io.grovs.example.data.EventForm
import io.grovs.example.data.KeyValue
import io.grovs.example.data.PurchaseForm
import io.grovs.example.data.ScreenForm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventActionsTest {

    @Test
    fun settersCannotRunAgain() {
        assertFalse(EventActions.globalTags(listOf("qa")).repeatable)
        assertFalse(EventActions.aliases(listOf(KeyValue("MainActivity", "Home"))).repeatable)
    }

    @Test
    fun testsCanRunAgain() {
        assertTrue(EventActions.track(EventForm()).repeatable)
        assertTrue(EventActions.screen(ScreenForm()).repeatable)
        assertTrue(EventActions.trigger("demo_tapped").repeatable)
        assertTrue(EventActions.purchase(PurchaseForm(), 0L)!!.repeatable)
    }

    @Test
    fun purchaseNeedsAmountCurrencyAndProduct() {
        assertNotNull(EventActions.purchase(PurchaseForm(), 0L))
        assertNull(EventActions.purchase(PurchaseForm(amount = "x"), 0L))
        assertNull(EventActions.purchase(PurchaseForm(currency = " "), 0L))
        assertNull(EventActions.purchase(PurchaseForm(productId = ""), 0L))
    }
}
