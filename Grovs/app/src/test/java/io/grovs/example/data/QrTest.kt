package io.grovs.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {
    @Test
    fun encodesALink() {
        val matrix = qrMatrix("https://grovs.link/x7Kp2a", 256)
        assertEquals(256, matrix.width)
        assertTrue((0 until 256).any { x -> (0 until 256).any { y -> matrix[x, y] } })
    }
}
