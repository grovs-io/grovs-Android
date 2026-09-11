package io.grovs.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.Grovs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class EventPropertiesE2ETest : ScreenTrackingTestBase() {
    @Test
    fun `track delivers malformed and subsequent valid events without an uncaught exception`() = runTest {
        verifyDelivery(screen = false) { name, properties -> Grovs.track(name, properties) }
    }

    @Test
    fun `trackScreenView delivers malformed and subsequent valid screens without an uncaught exception`() = runTest {
        verifyDelivery(screen = true) { name, properties -> Grovs.trackScreenView(name, properties) }
    }

    private suspend fun verifyDelivery(screen: Boolean, send: (String, Map<String, Any>) -> Unit) {
        configure(autoTrack = false)
        E2ETestUtils.getAuthenticationJob()!!.join()
        E2ETestUtils.assertAuthenticationCompleted()

        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            val cycle = mutableListOf<Any>()
            cycle.add(cycle)
            var tooDeep: Any = "leaf"
            repeat(17) { tooDeep = listOf(tooDeep) }
            val invalid = mapOf(
                "nonFinite" to mapOf("values" to listOf(Double.NaN)),
                "hugeNumber" to BigDecimal("1E+1000"),
                "cycle" to cycle,
                "tooDeep" to tooDeep,
                "unsupported" to arrayOf(Any()),
            )
            val valid = mapOf("order" to mapOf("sku" to "abc", "quantities" to listOf(1, 2)))

            val mixed = sendAndRead("mixed_properties", invalid + valid, send)
            assertName(mixed, "mixed_properties", screen)
            val properties = mixed.getJSONObject("properties")
            invalid.keys.forEach { assertFalse("Invalid property $it was sent", properties.has(it)) }
            val order = properties.getJSONObject("order")
            assertEquals("abc", order.getString("sku"))
            assertEquals("[1,2]", order.getJSONArray("quantities").toString())

            val empty = sendAndRead("invalid_properties", invalid, send)
            assertName(empty, "invalid_properties", screen)
            if (screen) {
                assertEquals(setOf("screen_name"), empty.getJSONObject("properties").keys().asSequence().toSet())
            } else {
                assertFalse("An event must survive even when all its properties are removed", empty.has("properties"))
            }

            val healthy = sendAndRead("healthy_properties", mapOf("ok" to true), send)
            assertName(healthy, "healthy_properties", screen)
            assertTrue(healthy.getJSONObject("properties").getBoolean("ok"))
            assertEquals("Malformed properties reached the host's exception handler", emptyList<Throwable>(), uncaught)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun sendAndRead(
        name: String,
        properties: Map<String, Any>,
        send: (String, Map<String, Any>) -> Unit,
    ): JSONObject {
        send(name, properties)
        val events = mutableListOf<JSONObject>()
        E2ETestUtils.waitForCondition(timeoutMs = 5_000, description = "$name delivered") {
            E2ETestUtils.flushCustomEvents()
            events.addAll(E2ETestUtils.eventsFromBatchRequests(E2ETestUtils.collectAllRequests(mockWebServer)))
            events.isNotEmpty()
        }
        assertEquals("Expected one event for $name", 1, events.size)
        return events.single()
    }

    private fun assertName(event: JSONObject, name: String, screen: Boolean) {
        assertEquals(if (screen) "screen_view" else name, event.getString("event_name"))
        if (screen) assertEquals(name, event.getJSONObject("properties").getString("screen_name"))
    }
}
