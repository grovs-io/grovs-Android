package io.grovs.handlers

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HandledPushesTest {

    private val prefs by lazy {
        RuntimeEnvironment.getApplication().getSharedPreferences(HandledPushes.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private lateinit var store: HandledPushes

    @Before
    fun setUp() {
        store = HandledPushes { prefs }
    }

    private fun tap(messageId: String?) = Intent().apply {
        putExtra("grovs_campaign_id", "c1")
        messageId?.let { putExtra("google.message_id", it) }
    }

    private fun push(intent: Intent) = GrovsPushPayload.from(intent)!!

    @Test
    fun `a handled push is recognised by the same intent`() {
        val intent = tap(messageId = null)
        store.markHandled(push(intent), intent)
        assertTrue(store.contains(push(intent), intent))
    }

    @Test
    fun `a new intent object with a handled message id is recognised after process death`() {
        val first = tap("m1")
        store.markHandled(push(first), first)

        val afterProcessDeath = HandledPushes { prefs }
        val redelivered = tap("m1")

        assertTrue(afterProcessDeath.contains(push(redelivered), redelivered))
    }

    @Test
    fun `an ignored push is remembered in memory only`() {
        val intent = tap("m2")
        store.ignore(push(intent), intent)

        assertTrue(store.contains(push(intent), intent))
        val other = tap("m2")
        assertFalse(HandledPushes { prefs }.contains(push(other), other))
    }

    @Test
    fun `only the last 50 message ids are kept`() {
        (1..51).forEach { n -> tap("m$n").let { store.markHandled(push(it), it) } }

        val fresh = HandledPushes { prefs }
        val oldest = tap("m1")
        val newest = tap("m51")

        assertFalse(fresh.contains(push(oldest), oldest))
        assertTrue(fresh.contains(push(newest), newest))
    }

    @Test
    fun `without storage the memory still works`() {
        val memoryOnly = HandledPushes { null }
        val intent = tap("m3")
        memoryOnly.markHandled(push(intent), intent)
        assertTrue(memoryOnly.contains(push(tap("m3")), tap("m3")))
    }
}
