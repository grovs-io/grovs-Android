package io.grovs.example.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class CallCodeTest {

    @Test
    fun rendersCallWithoutArguments() {
        assertEquals("pauseInAppMessages()", callCode("pauseInAppMessages"))
    }

    @Test
    fun rendersArguments() {
        assertEquals(
            "track(\n  name = \"demo\",\n  tags = [\"qa\", \"android\"],\n  properties = null\n)",
            callCode("track", "name" to "demo", "tags" to listOf("qa", "android"), "properties" to null),
        )
    }

    @Test
    fun rendersNestedMaps() {
        assertEquals(
            "send(\n  data = {\n    level: 3,\n    vip: true\n  }\n)",
            callCode("send", "data" to mapOf("level" to 3, "vip" to true)),
        )
    }

    @Test
    fun rendersEmptyMap() {
        assertEquals("{}", render(emptyMap<String, Any>()))
    }
}
