package io.grovs.inapp

import org.junit.Assert.assertTrue
import org.junit.Test

class InAppSharedCasesTest {
    @Test
    fun everyCaseTheBackendPublished() {
        val cases = InAppShared.cases()
        assertTrue("expected at least 71 cases, got ${cases.size}", cases.size >= 71)
        val failures = cases.mapNotNull { case -> InAppShared.run(case)?.let { "${case.name}: $it" } }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
