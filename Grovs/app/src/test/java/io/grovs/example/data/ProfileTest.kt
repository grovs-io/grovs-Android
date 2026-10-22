package io.grovs.example.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTest {

    private val base = Profile("p1", "Demo", "key_1", useTestEnvironment = false, baseUrl = "https://sdk.grovs.link")

    @Test
    fun noChangesMeansNoRestart() {
        assertEquals(emptyList<String>(), restartChanges(base, base.copy(name = "Renamed")))
    }

    @Test
    fun listsChangedSettings() {
        val changed = base.copy(useTestEnvironment = true, clipboardDomains = listOf("a.com"), startEnabled = false)
        assertEquals(listOf("Environment", "Clipboard domains", "Start with SDK enabled"), restartChanges(base, changed))
    }

    @Test
    fun anotherProfileIsOneChange() {
        assertEquals(listOf("Project"), restartChanges(base, base.copy(id = "p2", apiKey = "key_2")))
    }

    @Test
    fun jsonRoundTrip() {
        val profiles = listOf(base, base.copy(id = "p2", baseUrl = null, clipboardDomains = listOf("links.example.com")))
        assertEquals(profiles, decodeProfiles(encodeProfiles(profiles)))
    }

    @Test
    fun defaultsAreValid() {
        assertEquals(DEFAULT_PROFILES.size, DEFAULT_PROFILES.map { it.id }.toSet().size)
    }
}
