package io.grovs.example.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportLogTest {

    @Test
    fun writesHeaderAndEntriesOldestLast() {
        val entries = listOf(
            LogEntry(2, 20, LogType.EVENT, LogState.ERROR, "track x", "failed", "track()"),
            LogEntry(1, 10, LogType.SDK, LogState.OK, "SDK configured", "", ""),
        )
        val text = exportLog(entries, "Device: Pixel") { "t$it" }
        assertEquals(
            "Device: Pixel\n\nt20 EVENT ERROR track x\nfailed\ntrack()\n\nt10 SDK OK SDK configured",
            text,
        )
    }
}
