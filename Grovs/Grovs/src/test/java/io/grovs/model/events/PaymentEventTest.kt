package io.grovs.model.events

import io.grovs.utils.InstantCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PaymentEventTest {

    // ==================== Sample JSON fixtures ====================

    private val singleProductJson = """
        {
          "orderId": "GPA.3375-2069-1505-17920",
          "packageName": "com.example.myapp",
          "productId": "premium_upgrade",
          "purchaseTime": 1711987200000,
          "purchaseState": 0,
          "purchaseToken": "kfbopgjmhcilnaedpgimhfao.AO-J1Oz3G7kVwFjf2yLNpMqbXHkGOaQmvzKjRZBhL9oKfjsdhUEOihJrek5xSL9a",
          "quantity": 1,
          "acknowledged": false
        }
    """.trimIndent()

    private val subscriptionJson = """
        {
          "orderId": "GPA.3313-5055-3402-86973",
          "packageName": "com.example.myapp",
          "productId": "monthly_sub",
          "purchaseTime": 1711987200000,
          "purchaseState": 0,
          "purchaseToken": "aobelhfickmedjnpgkao.AO-J1OyQMaBVPktisgJHEwO0bMVaJkLp3NxydGT8Q4kFmBqWsfUExwCPfYk",
          "autoRenewing": true,
          "acknowledged": true
        }
    """.trimIndent()

    private val v5ProductIdsJson = """
        {
          "orderId": "GPA.3382-6190-8532-40496",
          "packageName": "com.example.myapp",
          "productIds": ["yearly_pro_plan"],
          "purchaseTime": 1711987200000,
          "purchaseState": 0,
          "purchaseToken": "hpnldaekifocjgbm.AO-J1OwHVf5hZ0kTtaE8gQMx9yJFkRuBip7mkNGWC3dfoJr2qX4Dthv6EOxFtNmKls7a2",
          "quantity": 1,
          "acknowledged": false,
          "autoRenewing": true
        }
    """.trimIndent()

    // ==================== Parsing Tests ====================

    @Test
    fun `parses single product purchase with v4 productId field`() {
        val events = PaymentEvent.fromOriginalJson(singleProductJson)

        assertEquals("Should return exactly 1 event", 1, events.size)
        val event = events[0]
        assertEquals(PaymentEventType.BUY, event.eventType)
        assertEquals("com.example.myapp", event.appId)
        assertEquals("premium_upgrade", event.productId)
        assertEquals("kfbopgjmhcilnaedpgimhfao.AO-J1Oz3G7kVwFjf2yLNpMqbXHkGOaQmvzKjRZBhL9oKfjsdhUEOihJrek5xSL9a", event.transactionToken)
        assertTrue("store should be true for Google Play purchases", event.store)
    }

    @Test
    fun `parses subscription purchase`() {
        val events = PaymentEvent.fromOriginalJson(subscriptionJson)

        assertEquals("Should return exactly 1 event", 1, events.size)
        val event = events[0]
        assertEquals(PaymentEventType.BUY, event.eventType)
        assertEquals("com.example.myapp", event.appId)
        assertEquals("monthly_sub", event.productId)
        assertEquals("aobelhfickmedjnpgkao.AO-J1OyQMaBVPktisgJHEwO0bMVaJkLp3NxydGT8Q4kFmBqWsfUExwCPfYk", event.transactionToken)
        assertTrue("store should be true", event.store)
    }

    @Test
    fun `parses v5+ productIds array`() {
        val events = PaymentEvent.fromOriginalJson(v5ProductIdsJson)

        assertEquals("Should return exactly 1 event", 1, events.size)
        val event = events[0]
        assertEquals("yearly_pro_plan", event.productId)
        assertEquals("com.example.myapp", event.appId)
        assertTrue("store should be true", event.store)
    }

    @Test
    fun `returns empty list for missing productId and productIds`() {
        val json = """
            {
              "orderId": "GPA.1234-5678-9012-34567",
              "packageName": "com.example.myapp",
              "purchaseTime": 1711987200000,
              "purchaseState": 0,
              "purchaseToken": "sometoken"
            }
        """.trimIndent()

        val events = PaymentEvent.fromOriginalJson(json)
        assertTrue("Should return empty list when no product identifiers", events.isEmpty())
    }

    @Test
    fun `returns empty list for invalid JSON`() {
        val events = PaymentEvent.fromOriginalJson("this is not json at all {{{")
        assertTrue("Should return empty list for invalid JSON", events.isEmpty())
    }

    @Test
    fun `returns empty list for empty string`() {
        val events = PaymentEvent.fromOriginalJson("")
        assertTrue("Should return empty list for empty string", events.isEmpty())
    }

    @Test
    fun `converts purchaseTime epoch millis to InstantCompat`() {
        val events = PaymentEvent.fromOriginalJson(singleProductJson)

        assertEquals(1, events.size)
        val event = events[0]
        assertNotNull("date should not be null", event.date)
        val expectedDate = InstantCompat.ofEpochMilli(1711987200000L)
        assertEquals(expectedDate, event.date)
    }

    @Test
    fun `uses purchaseToken as transactionToken`() {
        val events = PaymentEvent.fromOriginalJson(singleProductJson)

        assertEquals(1, events.size)
        assertEquals(
            "kfbopgjmhcilnaedpgimhfao.AO-J1Oz3G7kVwFjf2yLNpMqbXHkGOaQmvzKjRZBhL9oKfjsdhUEOihJrek5xSL9a",
            events[0].transactionToken
        )
    }

    @Test
    fun `sets store to true for all parsed purchases`() {
        val singleEvents = PaymentEvent.fromOriginalJson(singleProductJson)
        val subEvents = PaymentEvent.fromOriginalJson(subscriptionJson)
        val v5Events = PaymentEvent.fromOriginalJson(v5ProductIdsJson)

        assertTrue("Single product: store should be true", singleEvents[0].store)
        assertTrue("Subscription: store should be true", subEvents[0].store)
        assertTrue("v5 product: store should be true", v5Events[0].store)
    }

    @Test
    fun `custom eventType is passed through`() {
        val cancelEvents = PaymentEvent.fromOriginalJson(
            singleProductJson,
            eventType = PaymentEventType.CANCEL
        )
        assertEquals(PaymentEventType.CANCEL, cancelEvents[0].eventType)

        val refundEvents = PaymentEvent.fromOriginalJson(
            singleProductJson,
            eventType = PaymentEventType.REFUND
        )
        assertEquals(PaymentEventType.REFUND, refundEvents[0].eventType)
    }
}
