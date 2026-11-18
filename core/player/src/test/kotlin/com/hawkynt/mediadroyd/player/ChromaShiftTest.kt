package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChromaShiftTest {
    private val w = 256
    private val h = 96

    // Coloured blocks on grey with their colour drawn [dx], [dy] pixels (fractions blended) off their brightness.
    private fun capture(dx: Double, dy: Double, seed: Int = 1): ByteArray {
        val random = Random(seed)
        val blocks = List(24) { IntArray(4) { i -> if (i % 2 == 0) random.nextInt(w - 40) else random.nextInt(h - 30) } }
        val colours = List(blocks.size) { Triple(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        fun inside(x: Double, y: Double): Int =
            blocks.indices.lastOrNull { b ->
                x >= blocks[b][0] && x < blocks[b][0] + 30 && y >= blocks[b][1] && y < blocks[b][1] + 20
            } ?: -1
        fun rgb(b: Int) = if (b < 0) Triple(128, 128, 128) else colours[b]
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            // Brightness from where the pixel is, colour from where it was shifted from, each over a 4 × 4 subsample.
            var ly = 0.0
            var cb = 0.0
            var cr = 0.0
            for (s in 0 until 16) {
                val sx = x + (s % 4) / 4.0
                val sy = y + (s / 4) / 4.0
                val (r, g, b) = rgb(inside(sx, sy))
                ly += 0.2126 * r + 0.7152 * g + 0.0722 * b
                val (r2, g2, b2) = rgb(inside(sx - dx, sy - dy))
                val y2 = 0.2126 * r2 + 0.7152 * g2 + 0.0722 * b2
                cb += (b2 - y2) / 1.8556
                cr += (r2 - y2) / 1.5748
            }
            ly /= 16; cb /= 16; cr /= 16
            val r = ly + 1.5748 * cr
            val b = ly + 1.8556 * cb
            val g = (ly - 0.2126 * r - 0.0722 * b) / 0.7152
            val at = (y * w + x) * 4
            out[at] = r.toInt().coerceIn(0, 255).toByte()
            out[at + 1] = g.toInt().coerceIn(0, 255).toByte()
            out[at + 2] = b.toInt().coerceIn(0, 255).toByte()
            out[at + 3] = -1
        }
        return out
    }

    @Test
    fun `the colour's offset from the brightness is found to a quarter pixel, both ways`() {
        for ((dx, dy) in listOf(2.0 to 0.0, -1.5 to 0.0, 0.0 to 1.0, 3.25 to -0.75, 0.0 to 0.0)) {
            val found = requireNotNull(ChromaShift.estimate(capture(dx, dy), w, h)) { "nothing at $dx, $dy" }
            assertEquals("across at $dx, $dy", dx, found.first.toDouble(), 0.3)
            assertEquals("down at $dx, $dy", dy, found.second.toDouble(), 0.3)
        }
    }

    @Test
    fun `the centre is cut out at its own pixels, a deep picture's high bytes taken`() {
        val big = ByteArray(1000 * 600 * 4) { i -> ((i / 4) % 1000).toByte() }
        val (centre, cw, ch) = ChromaShift.centre(big, 1000, 600)
        assertEquals(ChromaShift.CENTRE_WIDTH, cw)
        assertEquals(ChromaShift.CENTRE_HEIGHT, ch)
        assertEquals(((1000 - cw) / 2).toByte(), centre[0])
        val deep = ByteArray(4 * 4 * 8) { i -> if (i % 2 == 1) 7 else 0 }
        assertEquals(7.toByte(), ChromaShift.centre(deep, 4, 4, bytesPerChannel = 2).first[0])
    }

    @Test
    fun `a black-and-white picture tells nothing`() {
        val grey = capture(0.0, 0.0).also { for (i in it.indices step 4) { it[i + 1] = it[i]; it[i + 2] = it[i] } }
        assertNull(ChromaShift.estimate(grey, w, h))
    }

    @Test
    fun `Auto settles on what most samples say, in quarter pixels, and holds after its look`() {
        val auto = ChromaShift.Auto()
        repeat(ChromaShift.Auto.SAMPLES) { auto.add(if (it == 3) 5f to 5f else 1.1f to -0.4f) }
        assertEquals(1f to -0.5f, auto.shift)
        repeat(ChromaShift.Auto.SAMPLES) { auto.add(3f to 3f) }
        assertEquals(1f to -0.5f, auto.shift)
        auto.restart()
        assertEquals(0f to 0f, auto.shift)
        assertEquals(true, abs(ChromaShift.quarter(0.37f) - 0.25f) < 1e-6f)
    }
}
