package com.hawkynt.mediadroyd.player

import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCutsTest {
    private val w = 64
    private val h = 36

    // A picture of a scene ([scene]: its own brightness and waves), shifted [step] pixels across as the camera pans.
    private fun pan(scene: Int, step: Int): Deinterlacer.Plane {
        val base = 50 + scene * 60
        return Deinterlacer.Plane(w, h, IntArray(w * h) { i ->
            val x = i % w + step
            val y = i / w
            (base + 30 * sin(x / 6.0 + scene) + 20 * sin(y / 5.0 * scene)).toInt()
        })
    }

    // A noisy picture: every pixel its own, as fast motion or grain leaves a frame against the last.
    private fun scene(scene: Int, step: Int): Deinterlacer.Plane {
        val random = Random(scene)
        val texture = IntArray((w + 64) * h) { 40 + random.nextInt(170) }
        return Deinterlacer.Plane(w, h, IntArray(w * h) { i -> texture[(i / w) * (w + 64) + i % w + step] })
    }

    @Test
    fun `a cut between two scenes is told, a pan within one is not`() {
        val cuts = SceneCuts()
        val frames = listOf(pan(1, 0), pan(1, 1), pan(1, 2), pan(2, 0), pan(2, 1))
        val verdicts = (1 until frames.size).map { i -> cuts.isCut(i - 1L, i.toLong()) { frames[it.toInt()] } }
        assertEquals(listOf(false, false, true, false), verdicts.map { it })
    }

    @Test
    fun `steady fast motion is no cut once the pair before is known, as scdet scores it`() {
        // Each frame as far from the last as the one before was: the difference jumps no more.
        val cuts = SceneCuts()
        val frames = (0..4).map { scene(it % 2 + 7, 0) }
        assertTrue(cuts.isCut(0, 1) { frames[it.toInt()] })
        assertFalse(cuts.isCut(1, 2) { frames[it.toInt()] })
        assertFalse(cuts.isCut(2, 3) { frames[it.toInt()] })
    }

    @Test
    fun `a pair with no pair before it is a cut only where it differs by far more than motion does`() {
        // A pan of several pixels over fine detail: some 15 % apart, no cut without a jump to measure.
        val fine = { s: Int -> Deinterlacer.Plane(w, h, IntArray(w * h) { i -> if ((i % w + s) / 2 % 2 == 0) 60 else 120 }) }
        assertFalse(SceneCuts().isCut(0, 1) { fine(it.toInt()) })
        assertTrue(SceneCuts().isCut(0, 1) { if (it == 0L) pan(1, 0) else pan(3, 0) })
    }

    @Test
    fun `a pair asked again keeps its verdict, and a missing picture is no cut`() {
        val cuts = SceneCuts()
        val a = scene(1, 0)
        val b = scene(2, 0)
        var made = 0
        val plane = { id: Long -> made++; if (id == 0L) a else b }
        assertTrue(cuts.isCut(0, 1, plane))
        assertTrue(cuts.isCut(0, 1, plane))
        assertEquals(2, made)
        assertFalse(cuts.isCut(5, 6) { null })
    }

    @Test
    fun `an RGBA picture is brought down to a small luma plane`() {
        val width = 640
        val height = 360
        val rgba = ByteArray(width * height * 4) { i -> if (i % 4 == 1) 200.toByte() else 0 }
        val plane = SceneCuts.luma(rgba, width, height)
        assertEquals(SceneCuts.COLUMNS, plane.width)
        assertEquals(36, plane.height)
        assertEquals((150 * 200 + 128) shr 8, plane.pixels[0])
        val deep = ByteArray(width * height * 8) { i -> if (i % 8 == 3) 200.toByte() else 0 }
        assertEquals(plane.pixels.toList(), SceneCuts.luma(deep, width, height, bytesPerChannel = 2).pixels.toList())
    }
}
