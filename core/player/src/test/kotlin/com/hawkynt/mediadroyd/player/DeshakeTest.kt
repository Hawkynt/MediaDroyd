package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The steadying: the camera's motion from Motion's block vectors, deshake's smoothing against FFmpeg's own log
 * (resources/deshake/make-expected.sh), and the picture read through the correction.
 */
class DeshakeTest {

    @Test
    fun `the smoothing follows FFmpeg's deshake frame by frame`() {
        val rows = requireNotNull(javaClass.getResourceAsStream("/deshake/deshake.log")).bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }
            .map { line -> line.split(',').map { it.trim().toDouble() } }
        assertTrue(rows.size > 50)
        val smoother = Deshake.Smoother()
        for ((i, r) in rows.withIndex()) {
            val found = Deshake.Motion(r[0], r[3], r[6])
            smoother.next(found)
            val avg = smoother.average
            assertEquals("frame $i avg x", r[1], avg.x, 1e-5)
            assertEquals("frame $i avg y", r[4], avg.y, 1e-5)
            assertEquals("frame $i avg angle", r[7], avg.angle, 1e-5)
            // deshake logs the jitter undone, before it joins the correction so far.
            assertEquals("frame $i fin x", r[2], -(found.x - avg.x), 1e-5)
            assertEquals("frame $i fin y", r[5], -(found.y - avg.y), 1e-5)
        }
    }

    @Test
    fun `a still camera needs no correction, a steady pan hardly any`() {
        val still = Deshake.Smoother()
        repeat(30) { assertEquals(Deshake.Motion(), still.next(Deshake.Motion())) }
        val pan = Deshake.Smoother()
        var last = Deshake.Motion()
        repeat(200) { last = pan.next(Deshake.Motion(x = 4.0)) }
        // The average catches up with a steady pan; what is corrected settles instead of growing.
        assertTrue("${last.x}", abs(last.x) < 1.0)
        val shaky = Deshake.Smoother()
        val corrections = (0 until 40).map { shaky.next(Deshake.Motion(x = if (it % 2 == 0) 6.0 else -6.0)).x }
        assertTrue(corrections.drop(10).all { abs(it) > 1.0 })
    }

    private fun plane(shift: Int) = Deinterlacer.Plane(128, 96, IntArray(128 * 96) { i ->
        val x = i % 128 - shift
        val y = i / 128
        ((x * 37 + y * 11) % 97 + (x / 8 + y / 8) % 2 * 120).coerceIn(0, 255)
    })

    @Test
    fun `the camera moves the other way to what it films, found from Motion's own search`() {
        // The picture's content moves 6 pixels right from the earlier frame to the later: the camera panned left.
        val search = MotionInterpolation.Estimator(128, 96)
        search.advance()
        search.estimate(plane(0), plane(6))
        val motion = TemporalDenoise.Motion(search.blocksWide, search.blocksHigh, 1, search.vectors.copyOf())
        val found = Deshake.global(motion, 128, 96)!!
        assertEquals(-6.0, found.x, 0.0)
        assertEquals(0.0, found.y, 0.0)
        assertNull(Deshake.global(TemporalDenoise.Motion(0, 0, 1, IntArray(0)), 128, 96))
    }

    @Test
    fun `a turn is found about the middle from the blocks' vectors`() {
        // Every block's content turned 0.02 rad clockwise-positive about the middle of a 320 × 240 picture.
        val w = 320
        val h = 240
        val gw = w / 16
        val gh = h / 16
        val vectors = IntArray(gw * gh * 2)
        for (k in 0 until gw * gh) {
            val x = (k % gw + 0.5) * 16 - w / 2.0
            val y = (k / gw + 0.5) * 16 - h / 2.0
            // Half-vectors: half the content's move, pointing back against it.
            vectors[k * 2] = -Math.round((-0.02 * y) / 2).toInt()
            vectors[k * 2 + 1] = -Math.round((0.02 * x) / 2).toInt()
        }
        val found = Deshake.global(TemporalDenoise.Motion(gw, gh, 1, vectors), w, h)!!
        assertTrue("${found.angle}", found.angle in -0.03..-0.01)
    }

    @Test
    fun `the picture is read through the correction, zoomed about the middle, mirrored at the edges`() {
        val w = 16
        val h = 8
        val frame = IntArray(w * h) { it % w * 10 }
        val identity = Deshake.matrix(Deshake.Motion(), w, h, zoom = 1.0)
        assertArrayEquals(frame, Deshake.apply(frame, w, h, identity))
        val moved = Deshake.apply(frame, w, h, Deshake.matrix(Deshake.Motion(x = 3.0), w, h, zoom = 1.0))
        assertEquals(30, moved[0])
        // Past the right edge the picture mirrors: column 15 + 3 reads column 12.
        assertEquals(120, moved[15])
        // Zoomed, the middle stays where it was.
        val zoomed = Deshake.matrix(Deshake.Motion(), w, h)
        assertEquals(w / 2.0, zoomed[0] * (w / 2.0) + zoomed[1] * (h / 2.0) + zoomed[2], 1e-9)
    }
}
