package io.grovs.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class InAppContractFilesTest {
    /** The shared files are copies; an edit made here would silently fork the contract. */
    @Test
    fun sharedFilesAreExactlyWhatTheBackendCopied() {
        val manifest = InAppShared.json("manifest.json")
        assertEquals(
            listOf("README.md", "cases.json", "cases.schema.json", "definition.schema.json"),
            manifest.keySet().sorted(),
        )
        for (name in manifest.keySet()) {
            val hash = MessageDigest.getInstance("SHA-256").digest(InAppShared.bytes(name))
                .joinToString("") { "%02x".format(it) }
            assertEquals(name, manifest.get(name).asString, hash)
        }
    }

    @Test
    fun bridgeCanBeInlinedInAScriptTag() {
        assertFalse(InAppBridge.SCRIPT.contains("</script", ignoreCase = true))
        assertFalse(InAppBridge.SCRIPT.contains("<!--"))
        assertTrue(InAppBridge.SCRIPT.toByteArray(Charsets.UTF_8).size < 60_000)
    }

    @Test
    fun bridgeIsTheWholeScriptWithItsDollarSigns() {
        assertTrue(InAppBridge.SCRIPT.startsWith("(function () {\n"))
        assertTrue(InAppBridge.SCRIPT.endsWith("})();\n"))
        assertTrue("dollar signs survive the generator", InAppBridge.SCRIPT.contains("\$/"))
        assertFalse(InAppBridge.SCRIPT.contains("\${'"))
    }

    @Test
    fun reservedEventsAreTheTwentyTwoSharedNames() {
        assertEquals(22, InAppBridge.RESERVED_EVENTS.size)
        for (name in listOf("in_app_viewed", "in_app_answered", "push_open", "screen_view", "custom", "journey_path")) {
            assertTrue(name, name in InAppBridge.RESERVED_EVENTS)
        }
    }
}
