package io.grovs.model

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URL
import java.util.Date
import java.util.UUID

class EventPropertiesSanitizerTest {
    @Test
    fun `valid JSON values preserve their serialized representation`() {
        val properties = linkedMapOf<String, Any>(
            "nested" to linkedMapOf(
                "items" to listOf("sku", true, null, 1, 1.25, mapOf("quantity" to 3L)),
                "empty" to emptyMap<String, Any>(),
                "null" to null,
            ),
            "integers" to intArrayOf(1, 2),
            "floats" to floatArrayOf(1.5f, -0.0f),
            "booleans" to booleanArrayOf(true, false),
            "objects" to arrayOf("value", null, listOf(2)),
            "chars" to charArrayOf('a', 'b'),
            "bigInteger" to BigInteger("123456789012345678901234567890"),
            "decimal" to BigDecimal("1234.5678901234567890"),
            "escaped" to "<&'\"=\n🎉",
        )

        val clean = CustomEventRules.sanitizeProperties(properties)

        assertNotNull(clean)
        assertEquals(Gson().toJson(properties), Gson().toJson(clean))
    }

    @Test
    fun `special types are converted inside maps lists and arrays`() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val clean = CustomEventRules.sanitizeProperties(mapOf(
            "nested" to mapOf("values" to listOf(Date(0), arrayOf(URL("https://grovs.io"), uuid))))
        )!!

        assertEquals(mapOf("nested" to mapOf("values" to listOf(
            "1970-01-01T00:00:00.000Z", listOf("https://grovs.io", uuid.toString())
        ))), clean)
    }

    @Test
    fun `non-finite nested numbers drop their top-level property without shifting lists`() {
        val invalid = listOf<Any>(
            mapOf("value" to Double.NaN),
            listOf("before", Float.POSITIVE_INFINITY, "after"),
            arrayOf(mapOf("value" to Double.NEGATIVE_INFINITY)),
            doubleArrayOf(1.0, Double.NaN),
        )
        for (value in invalid) {
            assertEquals(mapOf("good" to "kept"), CustomEventRules.sanitizeProperties(
                mapOf("bad" to value, "good" to "kept")
            ))
        }
    }

    @Test
    fun `large numbers that become infinity after storage are discarded per property`() {
        assertEquals(mapOf("good" to 1), CustomEventRules.sanitizeProperties(mapOf(
            "integer" to listOf(BigInteger.TEN.pow(1000)),
            "decimal" to mapOf("amount" to BigDecimal("1E+1000")),
            "good" to 1,
        )))
    }

    @Test
    fun `unsupported objects and non-string map keys drop only the containing property`() {
        val unsupported = object {
            override fun toString(): String = error("Must not stringify arbitrary objects")
        }
        assertEquals(mapOf("good" to 7), CustomEventRules.sanitizeProperties(mapOf(
            "object" to listOf(unsupported),
            "keys" to mapOf(123 to "value"),
            "good" to 7,
        )))
    }

    @Test
    fun `self-referencing maps lists and arrays are discarded`() {
        val map = mutableMapOf<String, Any>()
        map["self"] = map
        val list = mutableListOf<Any>()
        list.add(list)
        val array = arrayOfNulls<Any>(1)
        array[0] = array

        assertEquals(mapOf("good" to true), CustomEventRules.sanitizeProperties(mapOf(
            "map" to map, "list" to list, "array" to array, "good" to true
        )))
    }

    @Test
    fun `mutual cycles do not prevent later properties from being sanitized`() {
        val first = mutableMapOf<String, Any>()
        val second = mutableListOf<Any>()
        first["second"] = second
        second.add(first)

        assertEquals(mapOf("good" to listOf(1)), CustomEventRules.sanitizeProperties(
            mapOf("first" to first, "second" to second, "good" to listOf(1))
        ))
    }

    @Test
    fun `shared references are accepted and copied separately`() {
        val shared = mutableListOf<Any>(mapOf("sku" to "abc"))
        val clean = CustomEventRules.sanitizeProperties(mapOf("first" to shared, "second" to shared))!!

        assertEquals(listOf(mapOf("sku" to "abc")), clean["first"])
        assertEquals(clean["first"], clean["second"])
        assertNotSame(shared, clean["first"])
        assertNotSame(clean["first"], clean["second"])
    }

    @Test
    fun `sixteen nested containers are accepted and a seventeenth drops the property`() {
        fun nested(depth: Int): Any {
            var value: Any = "leaf"
            repeat(depth) { value = listOf(value) }
            return value
        }
        val valid = nested(16)

        assertEquals(mapOf("valid" to valid), CustomEventRules.sanitizeProperties(
            mapOf("tooDeep" to nested(17), "valid" to valid)
        ))
    }

    @Test
    fun `caller mutations cannot change the sanitized snapshot`() {
        val date = Date(0)
        val array = intArrayOf(1, 2)
        val child = mutableMapOf<String, Any>("date" to date, "array" to array)
        val list = mutableListOf<Any>(child)
        val input = mutableMapOf<String, Any>("items" to list)
        val clean = CustomEventRules.sanitizeProperties(input)!!
        val originalJson = Gson().toJson(clean)

        date.time = 99_000
        array[0] = 99
        child["cycle"] = child
        list.clear()
        input.clear()

        assertEquals(originalJson, Gson().toJson(clean))
        assertEquals(mapOf("items" to listOf(mapOf(
            "date" to "1970-01-01T00:00:00.000Z", "array" to listOf(1, 2)
        ))), clean)
    }

    @Test
    fun `serialized size accepts exactly 8KB and rejects the next byte`() {
        val exact = mapOf("v" to "x".repeat(8192 - 8)) // {"v":""} occupies eight bytes.
        assertEquals(8192, Gson().toJson(exact).toByteArray(Charsets.UTF_8).size)
        assertEquals(exact, CustomEventRules.sanitizeProperties(exact))
        assertNull(CustomEventRules.sanitizeProperties(mapOf("v" to exact.getValue("v") + "x")))
    }

    @Test
    fun `size limit counts nested UTF-8 content and JSON escapes`() {
        assertNull(CustomEventRules.sanitizeProperties(mapOf("nested" to listOf("🎉".repeat(2050)))))
        assertNull(CustomEventRules.sanitizeProperties(mapOf("nested" to listOf("\"".repeat(4100)))))
        assertNull(CustomEventRules.sanitizeProperties(mapOf("x".repeat(8193) to "value")))
        assertNull(CustomEventRules.sanitizeProperties(mapOf("huge" to BigInteger.ONE.shiftLeft(40_000))))
    }

    @Test
    fun `huge input lists stop traversal before allocating a huge snapshot`() {
        var reads = 0
        val huge = object : AbstractList<Int>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): Int {
                check(++reads <= 10_000) { "Traversal was not bounded" }
                return 0
            }
        }

        assertNull(CustomEventRules.sanitizeProperties(mapOf("huge" to huge)))
        assertTrue("Traversal should stop early, read $reads items", reads in 1 until 10_000)
    }

    @Test
    fun `a failing nested collection drops its property`() {
        val broken = object : AbstractList<Any>() {
            override val size = 1
            override fun get(index: Int): Any = throw IllegalStateException("Collection changed")
        }
        assertEquals(mapOf("good" to 1), CustomEventRules.sanitizeProperties(
            mapOf("broken" to broken, "good" to 1)
        ))
        assertNull(CustomEventRules.sanitizeProperties(mapOf("broken" to broken)))
        assertNull(CustomEventRules.sanitizeProperties(emptyMap()))
    }

    @Test
    fun `failure to iterate the top-level map omits properties`() {
        val broken = object : Map<String, Any> by mapOf("key" to "value") {
            override val entries: Set<Map.Entry<String, Any>>
                get() = throw IllegalStateException("Collection changed")
        }

        assertNull(CustomEventRules.sanitizeProperties(broken))
    }

    @Test
    fun `cancellation from caller-owned values is propagated unchanged`() {
        val cancellation = CancellationException("Cancelled")
        val cancelled = object : AbstractList<Any>() {
            override val size = 1
            override fun get(index: Int): Any = throw cancellation
        }

        try {
            CustomEventRules.sanitizeProperties(mapOf("cancelled" to cancelled))
            fail("Cancellation must not be swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
