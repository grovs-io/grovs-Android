package io.grovs.handlers

import io.grovs.model.BatchEventsResponse
import io.grovs.service.HttpStatusException
import io.grovs.utils.LSResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** [deliverInHalves] against a scripted backend: what is sent, what leaves the queue, what stays. */
class BatchDeliveryTest {
    private val events = (1..50).map { "e$it" }
    private val requests = mutableListOf<List<String>>()
    private val retired = mutableListOf<String>()

    private fun accepted(part: List<String>): LSResult<BatchEventsResponse> =
        LSResult.Success(BatchEventsResponse(accepted = part.size, rejected = 0))

    private fun refused(code: Int = 400): LSResult<BatchEventsResponse> =
        LSResult.Error(HttpStatusException(code, "refused ($code)"))

    private suspend fun deliver(
        admit: Boolean = true,
        canContinue: () -> Boolean = { true },
        respond: (List<String>) -> LSResult<BatchEventsResponse>,
    ): Boolean = deliverInHalves(
        label = "Test",
        events = events,
        describe = { it },
        send = { part -> requests += part; respond(part) },
        retire = { part -> if (admit) retired += part; admit },
        canContinue = canContinue,
    )

    @Test
    fun `an accepted batch is retired in one request`() = runTest {
        assertTrue(deliver { accepted(it) })

        assertEquals(1, requests.size)
        assertEquals(events, retired)
    }

    @Test
    fun `one refused event is isolated and dropped while every other event is delivered`() = runTest {
        assertTrue(deliver { if ("e17" in it) refused() else accepted(it) })

        assertEquals(events.toSet(), retired.toSet())
        assertEquals("nothing is retired twice", events.size, retired.size)
        assertEquals("the refused event is dropped after its batch-mates were delivered", "e17", retired.last())
        assertTrue("isolation costs two requests per halving, sent ${requests.size}", requests.size <= 13)
    }

    @Test
    fun `two refused events in different halves are both isolated`() = runTest {
        assertTrue(deliver { if ("e3" in it || "e40" in it) refused(413) else accepted(it) })

        assertEquals(events.toSet(), retired.toSet())
        assertEquals(listOf("e3", "e40"), retired.takeLast(2))
    }

    @Test
    fun `a batch that halving fixes drops nothing`() = runTest {
        assertTrue(deliver { if (it.size > 25) refused(413) else accepted(it) })

        assertEquals(events, retired)
        assertEquals(3, requests.size)
    }

    @Test
    fun `a backend that refuses every request drops nothing`() = runTest {
        assertFalse(deliver { refused() })

        assertTrue(retired.isEmpty())
    }

    @Test
    fun `a status other than 400 or 413 keeps the batch whole for the next flush`() = runTest {
        assertFalse(deliver { LSResult.Error(HttpStatusException(403, "forbidden")) })

        assertEquals(1, requests.size)
        assertTrue(retired.isEmpty())
    }

    @Test
    fun `a transport failure part-way keeps everything not yet delivered`() = runTest {
        val firstHalf = events.take(25)
        assertFalse(deliver { part ->
            when {
                part.size == events.size -> refused()
                part == firstHalf -> accepted(part)
                else -> LSResult.Error(IOException("connection reset"))
            }
        })

        assertEquals(firstHalf, retired)
    }

    @Test
    fun `withdrawn consent stops the split before its next request`() = runTest {
        assertFalse(deliver(canContinue = { false }) { if ("e17" in it) refused() else accepted(it) })

        assertEquals(1, requests.size)
        assertTrue(retired.isEmpty())
    }

    @Test
    fun `nothing is dropped when removals are not admitted`() = runTest {
        assertFalse(deliver(admit = false) { if ("e17" in it) refused() else accepted(it) })

        assertTrue(retired.isEmpty())
    }
}
