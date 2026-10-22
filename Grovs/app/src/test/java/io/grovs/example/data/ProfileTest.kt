package io.grovs.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        DEFAULT_PROFILES.forEach { p ->
            assertNull(profileNameError(p.name))
            assertNull(apiKeyError(p.apiKey))
            assertNull(baseUrlError(p.baseUrl.orEmpty()))
        }
    }

    @Test
    fun validatesSettings() {
        assertNotNull(profileNameError(" "))
        assertNotNull(apiKeyError(""))
        assertNull(baseUrlError(""))
        assertNull(baseUrlError("https://staging.grovs.io"))
        assertNotNull(baseUrlError("staging.grovs.io"))
    }

    @Test
    fun restoresDefaultProfile() {
        val default = DEFAULT_PROFILES.first()
        val broken = default.copy(baseUrl = "staging.grovs.io")
        assertEquals(listOf(default, base), withDefaultProfile(listOf(broken, base)))
        assertEquals(listOf(default, base), withDefaultProfile(listOf(base)))
    }
}
