package io.grovs.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryBackoffTest {

    private var now = 0L
    private val backoff = RetryBackoff().apply { elapsedMs = { now } }

    @Test
    fun `before any failure an attempt is allowed and nothing retries on its own`() {
        assertTrue(backoff.canAttempt())
        assertNull(backoff.delayMs())
        assertNull(backoff.lastFailure)
    }

    @Test
    fun `a retryable failure opens a 10s window that doubles per consecutive failure`() {
        backoff.record(RequestFailure.RETRYABLE)
        assertFalse(backoff.canAttempt())
        assertEquals(10_000L, backoff.delayMs())

        now += 10_000
        assertTrue(backoff.canAttempt())
        assertEquals(0L, backoff.delayMs())

        backoff.record(RequestFailure.RETRYABLE)
        assertEquals(20_000L, backoff.delayMs())
        backoff.record(RequestFailure.RETRYABLE)
        assertEquals(40_000L, backoff.delayMs())
    }

    @Test
    fun `the window never grows past 300s`() {
        repeat(12) { backoff.record(RequestFailure.RETRYABLE) }
        assertEquals(300_000L, backoff.delayMs())
    }

    @Test
    fun `offline keeps the window at its base and does not count as a consecutive failure`() {
        backoff.record(RequestFailure.RETRYABLE)
        backoff.record(RequestFailure.RETRYABLE)
        backoff.record(RequestFailure.OFFLINE)
        assertEquals(RequestFailure.OFFLINE, backoff.lastFailure)
        assertEquals(10_000L, backoff.delayMs())

        backoff.record(RequestFailure.RETRYABLE)
        assertEquals("the count carried on from two", 40_000L, backoff.delayMs())
    }

    @Test
    fun `a rejection blocks attempts for the window but never retries on its own`() {
        backoff.record(RequestFailure.REJECTED)
        assertFalse(backoff.canAttempt())
        assertNull(backoff.delayMs())

        now += 10_000
        assertTrue(backoff.canAttempt())
    }

    @Test
    fun `a success resets everything`() {
        backoff.record(RequestFailure.RETRYABLE)
        backoff.record(RequestFailure.RETRYABLE)
        backoff.record(null)
        assertTrue(backoff.canAttempt())
        assertNull(backoff.delayMs())
        assertNull(backoff.lastFailure)

        backoff.record(RequestFailure.RETRYABLE)
        assertEquals("the doubling starts over", 10_000L, backoff.delayMs())
    }
}
