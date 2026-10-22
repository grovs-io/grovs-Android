package io.grovs.example.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Date

class KeyValueTest {

    @Test
    fun detectsTypes() {
        assertEquals(ValueType.NUMBER, detectType("12"))
        assertEquals(ValueType.NUMBER, detectType("-4.5"))
        assertEquals(ValueType.BOOL, detectType("true"))
        assertEquals(ValueType.BOOL, detectType("FALSE"))
        assertEquals(ValueType.DATE, detectType("2026-10-22"))
        assertEquals(ValueType.TEXT, detectType("2026-13-45"))
        assertEquals(ValueType.TEXT, detectType("hello"))
        assertEquals(ValueType.TEXT, detectType(""))
    }

    @Test
    fun convertsValues() {
        assertEquals(12L, convert("12", ValueType.NUMBER))
        assertEquals(4.5, convert("4.5", ValueType.NUMBER))
        assertEquals(true, convert("True", ValueType.BOOL))
        assertEquals("abc", convert("abc", ValueType.NUMBER))
        assertEquals(Date(1792627200000L), convert("2026-10-22", ValueType.DATE))
    }

    @Test
    fun explicitTypeWinsOverDetection() {
        assertEquals("01234", KeyValue("zip", "01234", ValueType.TEXT).value())
        assertEquals(1234L, KeyValue("zip", "01234").value())
    }

    @Test
    fun cyclesTypes() {
        assertEquals(ValueType.NUMBER, ValueType.TEXT.next())
        assertEquals(ValueType.TEXT, ValueType.DATE.next())
    }

    @Test
    fun mapSkipsBlankKeysAndTrims() {
        val map = listOf(KeyValue(" level ", "3"), KeyValue("", "x"), KeyValue("name", "Ana")).toValueMap()
        assertEquals(mapOf("level" to 3L, "name" to "Ana"), map)
    }

    @Test
    fun summaryListsKeys() {
        assertEquals("a, b", listOf(KeyValue("a", "1"), KeyValue("", "2"), KeyValue("b", "x")).summary())
    }

    @Test
    fun storageRoundTripKeepsOrderAndTypes() {
        val rows = listOf(KeyValue("b", "01", ValueType.TEXT), KeyValue("a", "2"), KeyValue("c", "true"))
        assertEquals(rows, keyValuesFrom(rows.toJsonArray()))
    }

    @Test
    fun parsesPastedJson() {
        val rows = parseKeyValues("""{"level": 3, "vip": true, "name": "Ana"}""").toSet()
        assertEquals(
            setOf(KeyValue("level", "3", ValueType.NUMBER), KeyValue("vip", "true", ValueType.BOOL), KeyValue("name", "Ana")),
            rows,
        )
    }

    @Test
    fun toJsonWritesValues() {
        val json = listOf(KeyValue("level", "3"), KeyValue("on", "2026-10-22")).toJson()
        assertEquals(setOf(KeyValue("level", "3", ValueType.NUMBER), KeyValue("on", "2026-10-22")), parseKeyValues(json).toSet())
    }

    @Test
    fun invalidJsonThrows() {
        assertThrows(JSONException::class.java) { parseKeyValues("{not json") }
    }
}
