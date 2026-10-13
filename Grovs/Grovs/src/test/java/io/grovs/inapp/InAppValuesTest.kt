package io.grovs.inapp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class InAppValuesTest {
    private fun n(written: String): JsonElement = JsonParser.parseString("[$written]").asJsonArray[0]
    private fun s(text: String) = JsonPrimitive(text)

    @Test
    fun noNumberIsWrittenWithAnExponent() {
        assertEquals("1000000000000000000000", InAppValues.text(n("1e21")))
        assertEquals("1500000000000000000000", InAppValues.text(n("1.5e21")))
        assertEquals("0.0000001", InAppValues.text(n("1e-7")))
        assertEquals("0.00000000015", InAppValues.text(n("1.5e-10")))
        assertEquals("-0.000000025", InAppValues.text(n("-2.5e-8")))
        assertEquals("123456789.125", InAppValues.text(n("123456789.125")))
        assertEquals("1234567890123456", InAppValues.text(n("1234567890123456")))
    }

    @Test
    fun wholeNumbersHaveNoDecimalPoint() {
        assertEquals("42", InAppValues.text(n("42.0")))
        assertEquals("42.5", InAppValues.text(n("42.50")))
        assertEquals("0", InAppValues.text(n("-0.0")))
        assertEquals("-2.5", InAppValues.text(n("-2.5")))
        assertEquals("0.30000000000000004", InAppValues.text(n("0.30000000000000004")))
        assertEquals("0.30000000000000004", InAppValues.text(JsonPrimitive(0.1 + 0.2)))
        assertEquals("42", InAppValues.text(JsonPrimitive(42)))
    }

    @Test
    fun infinityAndHugeNumbersHaveNoTextForm() {
        assertNull(InAppValues.text(n("1e400")))
        assertNull(InAppJson.double(n("1e400")))
        assertFalse(InAppValues.isNumber(n("1e400")))
    }

    @Test
    fun wholeAcceptsOnePointZeroAndRefusesTheRest() {
        assertEquals(1, InAppJson.whole(n("1.0")))
        assertEquals(-3, InAppJson.whole(n("-3")))
        assertNull(InAppJson.whole(n("1.5")))
        assertNull(InAppJson.whole(n("true")))
        assertNull(InAppJson.whole(s("1")))
        assertNull(InAppJson.whole(n("9007199254740992")))
        assertNull(InAppJson.whole(n("3000000000")))
    }

    @Test
    fun booleansAreWordsAndNotNumbers() {
        assertEquals("true", InAppValues.text(n("true")))
        assertEquals("false", InAppValues.text(n("false")))
        assertFalse(InAppValues.isNumber(n("true")))
        assertNull(InAppJson.double(n("true")))
        assertTrue(InAppValues.compare(n("true"), "eq", s("TRUE")))
    }

    @Test
    fun listsObjectsAndNullHaveNoTextForm() {
        for (value in listOf(JsonNull.INSTANCE, n("[]"), n("[1]"), n("{}"), n("{\"a\":1}"))) assertNull(InAppValues.text(value))
        assertNull(InAppValues.text(null))
        assertFalse(InAppValues.compare(n("{\"a\":1}"), "is_set", null))
        assertTrue(InAppValues.compare(n("[\"a\"]"), "is_not_set", null))
        assertTrue(InAppValues.compare(s(""), "is_not_set", null))
        assertTrue(InAppValues.compare(s("x"), "is_set", null))
    }

    @Test
    fun equalityIsNumericWhenBothSidesAreNumbersElseCaseless() {
        assertTrue(InAppValues.compare(s("3.0"), "eq", n("3")))
        assertTrue(InAppValues.compare(n("3"), "eq", s("3.00")))
        assertTrue(InAppValues.compare(s("Pro"), "eq", s("pro")))
        assertTrue(InAppValues.compare(s("Pro"), "neq", s("basic")))
        assertTrue(InAppValues.compare(s("Hello World"), "contains", s("WORLD")))
        assertTrue(InAppValues.compare(s("Hello"), "not_contains", s("x")))
        assertTrue(InAppValues.compare(n("31"), "gt", s("30")))
        assertFalse(InAppValues.compare(s("abc"), "gt", n("1")))
        assertTrue(InAppValues.compare(n("2"), "lt", n("2.5")))
    }

    @Test
    fun aRangeMustBeTwoNumbersInOrder() {
        assertFalse(InAppValues.compare(n("3"), "between", n("5")))
        assertFalse(InAppValues.compare(n("3"), "between", n("[1, 2, 3]")))
        assertFalse(InAppValues.compare(n("3"), "between", n("[1, \"many\"]")))
        assertFalse(InAppValues.compare(n("3"), "between", n("[5, 1]")))
        assertTrue(InAppValues.compare(n("3"), "between", n("[1, \"5\"]")))
    }

    @Test
    fun onlyPlainAsciiDecimalsAreNumbers() {
        for (written in listOf("+1", "1e3", "1.", ".5", " 1", "1\n", "٣")) {
            assertFalse(written, InAppValues.compare(s(written), "gt", n("0")))
        }
        assertTrue(InAppValues.isNumber(s("-3.5")))
        assertFalse(InAppValues.isNumber(s("3.")))
        assertFalse(InAppValues.isNumber(s("1e3")))
        assertFalse(InAppValues.compare(s("a"), "like", s("a")))
    }

    @Test
    fun versions() {
        assertTrue(InAppValues.compareVersions("2.1.0-beta", "eq", s("2.1.0")))
        assertTrue(InAppValues.compareVersions("17.4-beta.2", "eq", s("17.4")))
        assertTrue(InAppValues.compareVersions("beta", "eq", s("0")))
        assertTrue(InAppValues.compareVersions("2.10", "gt", s("2.9")))
        assertTrue(InAppValues.compareVersions("2.1", "eq", s("2.1.0.0")))
        assertTrue(InAppValues.compareVersions("2.5", "between", n("[\"2.0\", \"3\"]")))
        assertFalse(InAppValues.compareVersions("3.1", "between", n("[\"2.0\", \"3\"]")))
        assertTrue(InAppValues.compareVersions("99999999999999999999.1", "eq", s("0.1")))
        for (op in listOf("eq", "neq", "gt", "lt")) assertFalse(InAppValues.compareVersions("", op, s("1.0")))
        assertFalse(InAppValues.compareVersions("1.0", "between", s("1.0")))
    }

    @Test
    fun percentEncodingKeepsOnlyUnreservedCharacters() {
        assertEquals("a%21%27%28%29%2A%2B%20%C3%A9~-._Z9", InAppValues.percentEncode("a!'()*+ é~-._Z9"))
        assertEquals("%F0%9F%98%80", InAppValues.percentEncode("😀"))
        assertEquals("%20", InAppValues.percentEncode(" "))
    }

    @Test
    fun hostValuesBecomeJson() {
        val json = InAppJson.fromHost(mapOf("a" to 4, "b" to listOf("x", true), "c" to Double.NaN, "d" to Any()))
        assertEquals(n("{\"a\":4,\"b\":[\"x\",true],\"c\":null,\"d\":null}"), json)
        assertEquals("4", InAppValues.text(json.asJsonObject.get("a")))
        assertEquals(JsonArray().apply { add("x") }, InAppJson.fromHost(arrayOf("x")))
    }

    @Test
    fun underTurkishAndArabicLocalesNothingChanges() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale("tr"), Locale("ar"))) {
                Locale.setDefault(locale)
                assertTrue(InAppValues.compare(s("TITLE"), "eq", s("title")))
                assertTrue(InAppValues.compare(s("IRIS"), "contains", s("iri")))
                assertEquals("42.5", InAppValues.text(n("42.5")))
                assertEquals("1000000000000000000000", InAppValues.text(n("1e21")))
                assertEquals("%C3%A9", InAppValues.percentEncode("é"))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
