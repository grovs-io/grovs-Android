package io.grovs.model

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URL
import java.time.DayOfWeek
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
    fun `non-finite numbers are dropped individually at every depth`() {
        val clean = CustomEventRules.sanitizeProperties(mapOf(
            "top" to Double.NaN,
            "map" to mapOf("value" to Double.NaN, "kept" to 1),
            "list" to listOf("before", Float.POSITIVE_INFINITY, "after"),
            "array" to arrayOf(mapOf("value" to Double.NEGATIVE_INFINITY)),
            "doubles" to doubleArrayOf(1.0, Double.NaN),
            "good" to "kept",
        ))

        assertEquals(mapOf(
            "map" to mapOf("kept" to 1),
            "list" to listOf("before", "after"),
            "array" to listOf(emptyMap<String, Any>()),
            "doubles" to listOf(1.0),
            "good" to "kept",
        ), clean)
    }

    @Test
    fun `large numbers that become infinity after storage are dropped individually`() {
        assertEquals(mapOf(
            "integer" to emptyList<Any>(),
            "decimal" to mapOf("kept" to 1),
            "good" to 1,
        ), CustomEventRules.sanitizeProperties(mapOf(
            "top" to BigDecimal("1E+1000"),
            "integer" to listOf(BigInteger.TEN.pow(1000)),
            "decimal" to mapOf("amount" to BigDecimal("1E+1000"), "kept" to 1),
            "good" to 1,
        )))
    }

    @Test
    fun `unsupported values are dropped individually and a map with a non-string key is dropped whole`() {
        val unsupported = object {
            override fun toString(): String = error("Must not stringify arbitrary objects")
        }
        assertEquals(mapOf(
            "objects" to listOf("kept"),
            "nested" to mapOf("kept" to 2),
            "good" to 7,
        ), CustomEventRules.sanitizeProperties(mapOf(
            "object" to unsupported,
            "enum" to DayOfWeek.MONDAY,
            "set" to setOf("a"),
            "objects" to listOf(unsupported, "kept"),
            "keys" to mapOf(123 to "value"),
            "nested" to mapOf("keys" to mapOf("ok" to 1, 2 to "two"), "kept" to 2),
            "good" to 7,
        )))
    }

    @Test
    fun `a reference back to an enclosing collection is dropped from its parent`() {
        val map = mutableMapOf<String, Any>()
        map["self"] = map
        map["kept"] = 1
        val list = mutableListOf<Any>()
        list.add(list)
        list.add("kept")
        val array = arrayOfNulls<Any>(2)
        array[0] = array
        array[1] = "kept"

        assertEquals(mapOf(
            "map" to mapOf("kept" to 1),
            "list" to listOf("kept"),
            "array" to listOf("kept"),
            "good" to true,
        ), CustomEventRules.sanitizeProperties(mapOf(
            "map" to map, "list" to list, "array" to array, "good" to true
        )))
    }

    @Test
    fun `mutual cycles are cut where they close`() {
        val first = mutableMapOf<String, Any>()
        val second = mutableListOf<Any>()
        first["second"] = second
        second.add(first)

        assertEquals(mapOf(
            "first" to mapOf("second" to emptyList<Any>()),
            "second" to listOf(emptyMap<String, Any>()),
            "good" to listOf(1),
        ), CustomEventRules.sanitizeProperties(
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
    fun `sixteen nested containers are accepted and a seventeenth is dropped from its parent`() {
        fun nested(depth: Int, leaf: Any): Any {
            var value = leaf
            repeat(depth) { value = listOf(value) }
            return value
        }
        val valid = nested(16, "leaf")

        assertEquals(mapOf(
            "tooDeep" to nested(15, emptyList<Any>()),
            "valid" to valid,
        ), CustomEventRules.sanitizeProperties(
            mapOf("tooDeep" to nested(17, "leaf"), "valid" to valid)
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
    fun `a collection that fails while being read is dropped from its parent`() {
        val broken = object : AbstractList<Any>() {
            override val size = 1
            override fun get(index: Int): Any = throw IllegalStateException("Collection changed")
        }
        assertEquals(mapOf("wrapper" to listOf(1), "good" to 1), CustomEventRules.sanitizeProperties(
            mapOf("broken" to broken, "wrapper" to listOf(broken, 1), "good" to 1)
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
