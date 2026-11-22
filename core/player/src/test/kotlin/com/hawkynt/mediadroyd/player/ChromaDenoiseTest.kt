package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The colour-only denoiser against a simulated tape: a picture whose brightness is clean while its colour carries
 * blotches that change from frame to frame, over a still background and a bar that moves.
 */
class ChromaDenoiseTest {

    private val w = 48
    private val h = 32

    // The clean picture, as Y'CbCr (8-bit units): a skin-coloured left half, a blue right half, a grey bar at [barX].
    private fun ycc(x: Int, barX: Int): Triple<Float, Float, Float> = when {
        x in barX until barX + 6 -> Triple(200f, 0f, 0f)
        x < w / 2 -> Triple(150f, -12f, 20f)
        else -> Triple(90f, 30f, -10f)
    }

    // The picture at [barX] as RGB, its colour carrying noise drawn from [seed] (none where it is null).
    private fun picture(barX: Int, seed: Int?): ByteArray {
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val (luma, cb, cr) = ycc(x, barX)
            // Blotches: the noise drawn per 2 × 2 block, as tape's colour noise is coarser than its pixels.
            val noise = seed?.let { Random(it * 7919 + (y / 2) * 131 + x / 2) }
            val nb = noise?.let { (it.nextFloat() - 0.5f) * 24f } ?: 0f
            val nr = noise?.let { (it.nextFloat() - 0.5f) * 24f } ?: 0f
            val rgb = ChromaDenoise.rgb(luma, cb + nb, cr + nr)
            for (c in 0..2) out[(y * w + x) * 3 + c] = rgb[c].roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun v(frame: ByteArray, i: Int) = (frame[i].toInt() and 0xFF).toFloat()

    private fun luma(frame: ByteArray, x: Int, y: Int): Float {
        val i = (y * w + x) * 3
        return 0.2126f * v(frame, i) + 0.7152f * v(frame, i + 1) + 0.0722f * v(frame, i + 2)
    }

    private fun chroma(frame: ByteArray, x: Int, y: Int): Pair<Float, Float> {
        val i = (y * w + x) * 3
        val luma = luma(frame, x, y)
        return (v(frame, i + 2) - luma) / 1.8556f to (v(frame, i) - luma) / 1.5748f
    }

    // The mean squared error of [frame]'s colour against the clean picture's, over the columns [xs].
    private fun colourError(frame: ByteArray, barX: Int, xs: IntRange): Double {
        var sum = 0.0
        var n = 0
        for (y in 2 until h - 2) for (x in xs) {
            val (_, cb, cr) = ycc(x, barX)
            val (b, r) = chroma(frame, x, y)
            sum += (b - cb) * (b - cb) + (r - cr) * (r - cr)
            n++
        }
        return sum / n
    }

    @Test
    fun `off at zero, stronger thresholds towards one`() {
        assertNull(ChromaDenoise.params(0f))
        val low = ChromaDenoise.params(0.1f)!!
        val high = ChromaDenoise.params(1f)!!
        assertTrue(high.motion > low.motion && high.guide > low.guide)
    }

    @Test
    fun `the brightness is left as it is`() {
        val before = picture(30, seed = 1)
        val shown = picture(30, seed = 2)
        val after = picture(30, seed = 3)
        val out = ChromaDenoise.apply(before, shown, after, w, h, ChromaDenoise.params(1f)!!)
        var worst = 0f
        for (y in 0 until h) for (x in 0 until w) worst = maxOf(worst, abs(luma(out, x, y) - luma(shown, x, y)))
        assertTrue("off by $worst", worst <= 1.5f)
    }

    @Test
    fun `where nothing moves the colour's blotches fade`() {
        val before = picture(30, seed = 1)
        val shown = picture(30, seed = 2)
        val after = picture(30, seed = 3)
        val out = ChromaDenoise.apply(before, shown, after, w, h, ChromaDenoise.params(0.5f)!!)
        val noisy = colourError(shown, 30, 2 until 20)
        val cleaned = colourError(out, 30, 2 until 20)
        assertTrue("$noisy -> $cleaned", cleaned < noisy / 4)
    }

    @Test
    fun `a moving bar takes no colour from where it was or will be`() {
        // The bar moves 6 pixels a frame: before it stood at 4, after it stands at 16, now at 10.
        val before = picture(4, seed = null)
        val shown = picture(10, seed = null)
        val after = picture(16, seed = null)
        val out = ChromaDenoise.apply(before, shown, after, w, h, ChromaDenoise.params(1f)!!)
        for (x in 10 until 16) {
            val (cb, cr) = chroma(out, x, h / 2)
            assertEquals("bar at $x", 0f, cb, 2f)
            assertEquals("bar at $x", 0f, cr, 2f)
        }
        // And where the bar was, the skin shows its own colour.
        val (cb, cr) = chroma(out, 6, h / 2)
        assertEquals(-12f, cb, 2f)
        assertEquals(20f, cr, 2f)
    }

    @Test
    fun `along the picture the colour stays on its own side of an edge in the brightness`() {
        val shown = picture(40, seed = null)
        val out = ChromaDenoise.apply(shown, shown, shown, w, h, ChromaDenoise.params(1f)!!)
        // The pixels either side of the skin/blue edge at the middle keep their own colour.
        val (lb, lr) = chroma(out, w / 2 - 1, h / 2)
        val (rb, rr) = chroma(out, w / 2, h / 2)
        assertEquals(-12f, lb, 2f)
        assertEquals(20f, lr, 2f)
        assertEquals(30f, rb, 2f)
        assertEquals(-10f, rr, 2f)
    }

    @Test
    fun `where the picture moves the colour is still smoothed along the picture`() {
        // Every frame a different picture (a cut each frame): only the spatial part can help.
        val shown = picture(30, seed = 2)
        val dark = ByteArray(w * h * 3) { 20 }
        val out = ChromaDenoise.apply(dark, shown, dark, w, h, ChromaDenoise.params(1f)!!)
        val noisy = colourError(shown, 30, 2 until 20)
        val cleaned = colourError(out, 30, 2 until 20)
        assertTrue("$noisy -> $cleaned", cleaned < noisy / 2)
    }
}
