package com.hawkynt.mediadroyd.player

import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VhsEdgesTest {
    private val w = 360
    private val h = 288

    // A tape's picture: soft shapes; its last [torn] lines dragged sideways more and more, and noisier, as the heads
    // switch.
    private fun frame(torn: Int, seed: Int = 1): Deinterlacer.Plane {
        val random = Random(seed)
        val phase = random.nextDouble() * 6
        return Deinterlacer.Plane(w, h, IntArray(w * h) { i ->
            val y = i / w
            val fromBottom = h - 1 - y
            val drag = if (fromBottom < torn) (torn - fromBottom) * 4 + 2 else 0
            val x = i % w + drag
            val base = 120 + 50 * sin(x / 13.0 + phase) + 30 * sin(y / 17.0 + x / 41.0)
            (base + if (fromBottom < torn) random.nextInt(-12, 13) else 0).toInt().coerceIn(0, 255)
        })
    }

    @Test
    fun `the torn lines at the bottom are counted, a clean picture has none`() {
        for (torn in listOf(0, 4, 9, 14)) {
            assertEquals("torn $torn", torn.toDouble(), VhsEdges.tornLines(frame(torn)).toDouble(), 1.0)
        }
    }

    @Test
    fun `the last lines of an RGBA picture are judged as luma`() {
        val torn = frame(6)
        val rgba = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else torn.pixels[i / 4].toByte() }
        val bottom = VhsEdges.bottomLuma(rgba, w, h)
        assertEquals(VhsEdges.LOOK_LINES + 2, bottom.height)
        assertEquals(6.0, VhsEdges.tornLines(bottom).toDouble(), 1.0)
    }

    @Test
    fun `Auto takes the median of its samples and holds it`() {
        val auto = VhsEdges.Auto()
        repeat(VhsEdges.Auto.SAMPLES) { auto.add(if (it == 2) 40 else 7) }
        assertEquals(7, auto.bottom)
        repeat(VhsEdges.Auto.SAMPLES) { auto.add(1) }
        assertEquals(7, auto.bottom)
        auto.restart()
        assertEquals(0, auto.bottom)
    }

    @Test
    fun `the edges cut into the black bars' crop or the whole picture, never past half of it`() {
        val edges = VhsEdges.Edges(left = 8, top = 2, right = 10, bottom = 6)
        assertEquals(PictureCrop(8, 2, 702, 568), VhsEdges.crop(edges, null, 720, 576))
        // Inside the bars a cut that falls within them changes nothing; the sides still cut.
        assertEquals(PictureCrop(8, 70, 702, 436), VhsEdges.crop(edges, PictureCrop(0, 70, 720, 436), 720, 576))
        assertNull(VhsEdges.crop(VhsEdges.Edges(), null, 720, 576))
        assertEquals(PictureCrop(0, 0, 720, 576 - 6), VhsEdges.crop(VhsEdges.Edges(), null, 720, 576, autoBottom = 6))
        val wild = VhsEdges.crop(VhsEdges.Edges(left = 500, right = 500), null, 720, 576)!!
        assertEquals(360, wild.width)
    }
}
