package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `.cube` tables read as Adobe's Cube LUT Specification 1.0 says, and looked up as FFmpeg's lut3d and lut1d look them
 * up — their output on the same picture is committed beside the tables (resources/cube/make-expected.sh).
 */
class CubeLutTest {

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/cube/$name")).readBytes()

    private fun worstAgainst(lut: CubeLut, expected: String, interpolation: CubeLut.Interpolation): Int {
        val picture = resource("picture.rgb")
        val oracle = resource(expected)
        var worst = 0
        for (i in 0 until picture.size / 3) {
            val (r, g, b) = (0..2).map { (picture[i * 3 + it].toInt() and 255) / 255f }
            val out = lut.apply(r, g, b, interpolation)
            for (c in 0..2) {
                val mine = (out[c] * 255f).roundToInt().coerceIn(0, 255)
                worst = maxOf(worst, abs(mine - (oracle[i * 3 + c].toInt() and 255)))
            }
        }
        return worst
    }

    private val look = requireNotNull(CubeLut.parse(String(resource("look.cube"))))

    @Test
    fun `a 3D table's keywords and entries are read`() {
        assertEquals("Test look", look.title)
        assertEquals(5, look.size)
        assertTrue(look.threeD)
        assertEquals(5 * 5 * 5 * 3, look.table.size)
    }

    @Test
    fun `read by tetrahedra as FFmpeg's lut3d reads it`() {
        val worst = worstAgainst(look, "look-tetrahedral.rgb", CubeLut.Interpolation.TETRAHEDRAL)
        assertTrue("off by $worst", worst <= 1)
    }

    @Test
    fun `read trilinearly as FFmpeg's lut3d reads it`() {
        val worst = worstAgainst(look, "look-trilinear.rgb", CubeLut.Interpolation.TRILINEAR)
        assertTrue("off by $worst", worst <= 1)
    }

    @Test
    fun `a 1D table on its own domain, each channel on its own, as FFmpeg's lut1d reads it`() {
        val curve = requireNotNull(CubeLut.parse(String(resource("curve.cube"))))
        assertEquals(false, curve.threeD)
        assertArrayEquals(floatArrayOf(2f, 2f, 2f), curve.domainMax, 0f)
        val worst = worstAgainst(curve, "curve-linear.rgb", CubeLut.Interpolation.TETRAHEDRAL)
        assertTrue("off by $worst", worst <= 1)
    }

    @Test
    fun `an entry lands exactly on its value, and inputs beyond the domain are clamped`() {
        val identity = requireNotNull(CubeLut.parse(identity(3)))
        assertArrayEquals(floatArrayOf(0.5f, 0f, 1f), identity.apply(0.5f, 0f, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.3f, 0.6f, 0.9f), identity.apply(0.3f, 0.6f, 0.9f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), identity.apply(1.5f, -0.2f, 3f), 1e-6f)
    }

    @Test
    fun `a file that is no table is refused`() {
        assertNull(CubeLut.parse(""))
        assertNull(CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n"))
        assertNull(CubeLut.parse("LUT_3D_SIZE 1\n0 0 0\n"))
        assertNull(CubeLut.parse("LUT_3D_SIZE 2\nLUT_1D_SIZE 2\n" + "0 0 0\n".repeat(8)))
        assertNull(CubeLut.parse("LUT_1D_SIZE 2\n0 0\n1 1 1\n"))
        assertNull(CubeLut.parse("LUT_1D_SIZE 2\nDOMAIN_MIN 1 1 1\nDOMAIN_MAX 0 0 0\n0 0 0\n1 1 1\n"))
        // An unknown keyword is skipped, comments and blank lines are nothing.
        assertNotNull(CubeLut.parse("# c\nLUT_1D_SIZE 2\nVENDOR_THING 7\n\n0 0 0 # dark\n1 1 1\n"))
    }

    @Test
    fun `the GL pass's texture holds a 3D table's blue slices side by side, a 1D table as one row`() {
        val lut = requireNotNull(CubeLut.parse(identity(5)))
        val t = lut.texture()
        assertEquals(3, lut.tilesAcross)
        assertEquals(15, t.width)
        assertEquals(10, t.height)
        // Entry r = 1, g = 2, b = 4: slice 4 is the second row's second tile.
        val at = ((1 * 5 + 2) * t.width + 1 * 5 + 1) * 4
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 1f, 1f), t.rgba.copyOfRange(at, at + 4), 1e-6f)
        val curve = requireNotNull(CubeLut.parse(String(resource("curve.cube")))).texture()
        assertEquals(9, curve.width)
        assertEquals(1, curve.height)
    }

    @Test
    fun `the library takes the cube files that read, by name, labelled without the extension`() {
        CubeLuts.load(mapOf("Warm.cube" to identity(2), "broken.CUBE" to "nonsense", "crt.glsl" to "//!HOOK MAIN"))
        assertEquals(listOf("Warm.cube"), CubeLuts.choices)
        assertEquals("Warm", CubeLuts.label("Warm.cube"))
        CubeLuts.load(emptyMap())
    }

    @Test
    fun `the look runs first, then the screen's calibration, each only where the library holds it`() {
        CubeLuts.load(mapOf("look.cube" to identity(2), "screen.cube" to identity(3)))
        val both = CubeLuts.chosen(
            PictureAdjustment(lut = "look.cube"), ScreenWarp.of(ScreenSetup(lut = "screen.cube")),
        )
        assertEquals(listOf(2, 3), both.map { it.size })
        assertEquals(emptyList<CubeLut>(), CubeLuts.chosen(PictureAdjustment(lut = "gone.cube"), ScreenWarp.None))
        CubeLuts.load(emptyMap())
    }

    private fun identity(n: Int) = buildString {
        append("LUT_3D_SIZE $n\n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            append("${r / (n - 1f)} ${g / (n - 1f)} ${b / (n - 1f)}\n")
        }
    }
}
