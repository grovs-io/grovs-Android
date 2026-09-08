package io.grovs.model.events

import com.google.gson.Gson
import io.grovs.utils.InstantCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PaymentEventSessionTest {

    @Test
    fun `withSessionId stamps the session and keeps every other field`() {
        val original = PaymentEvent(
            eventType = PaymentEventType.BUY, appId = "io.grovs.test", priceCents = 499L, currency = "USD",
            date = InstantCompat.ofEpochMilli(1000L), transactionToken = "tok", originalTransactionId = 7,
            productId = "pro", store = true, link = "https://x.sqd.link/a",
        )

        val stamped = original.withSessionId("session-1")

        assertEquals("session-1", stamped.sessionId)
        assertEquals(original.productId, stamped.productId)
        assertEquals(original.transactionToken, stamped.transactionToken)
        assertEquals(original.link, stamped.link)
        assertEquals(original.store, stamped.store)
    }

    @Test
    fun `session_id is serialized and events persisted before the field decode with null`() {
        val gson = Gson()
        val json = gson.toJson(PaymentEvent(productId = "pro", sessionId = "session-1"))
        assertTrue(json, json.contains("\"session_id\":\"session-1\""))

        val legacy = gson.fromJson("""{"product_id":"pro","store":false}""", PaymentEvent::class.java)
        assertNull(legacy.sessionId)
    }
}
