package io.grovs.inapp

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class InAppMainTimerTest {

    private val main = shadowOf(Looper.getMainLooper())

    @Test
    fun `work runs on the main looper once the delay has passed`() {
        var ranOn: Looper? = null
        Thread { InAppMainTimer.after(1_000) { ranOn = Looper.myLooper() } }.apply { start(); join() }

        main.idleFor(Duration.ofMillis(999))
        assertEquals(null, ranOn)
        main.idleFor(Duration.ofMillis(1))
        assertSame(Looper.getMainLooper(), ranOn)
    }

    @Test
    fun `cancelled work never runs`() {
        var ran = false
        val timer = InAppMainTimer.after(1_000) { ran = true }
        timer.cancel()
        main.idleFor(Duration.ofSeconds(2))
        assertFalse(ran)
    }

    @Test
    fun `cancelling one timer leaves the others`() {
        var first = false
        var second = false
        val timer = InAppMainTimer.after(500) { first = true }
        InAppMainTimer.after(500) { second = true }
        timer.cancel()
        main.idleFor(Duration.ofSeconds(1))
        assertFalse(first)
        assertTrue(second)
    }
}
