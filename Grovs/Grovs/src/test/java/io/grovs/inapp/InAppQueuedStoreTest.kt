package io.grovs.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InAppQueuedStoreTest {

    private val files = FakeInAppStore()
    private val executor = InAppQueuedStore.newExecutor()
    private val store = InAppQueuedStore(files, executor)

    private fun drain() {
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a write is readable at once and reaches the file after the queue drains`() {
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }

        assertTrue(store.write("{\"a\":1}", InAppRecord.STATE))
        assertEquals("{\"a\":1}", store.read(InAppRecord.STATE))
        assertNull("not on disk while the queue is busy", files.records[InAppRecord.STATE])

        gate.countDown()
        drain()
        assertEquals("{\"a\":1}", files.records[InAppRecord.STATE])
    }

    @Test
    fun `writes land in order and the last one wins`() {
        store.write("1", InAppRecord.STATE)
        store.write("2", InAppRecord.STATE)
        store.write("c", InAppRecord.CAMPAIGNS)
        drain()
        assertEquals("2", files.records[InAppRecord.STATE])
        assertEquals("c", files.records[InAppRecord.CAMPAIGNS])
    }

    @Test
    fun `a record never written is read from the file`() {
        files.records[InAppRecord.CAMPAIGNS] = "kept"
        assertEquals("kept", store.read(InAppRecord.CAMPAIGNS))
    }

    @Test
    fun `after shutdown queued writes finish and new ones are refused`() {
        store.write("before", InAppRecord.STATE)
        store.shutdown()
        assertFalse(store.write("after", InAppRecord.STATE))
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("before", files.records[InAppRecord.STATE])
        assertEquals("before", store.read(InAppRecord.STATE))
    }
}
