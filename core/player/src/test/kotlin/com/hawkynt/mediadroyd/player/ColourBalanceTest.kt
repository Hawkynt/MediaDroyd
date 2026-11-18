package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourBalanceTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // [m] (row-major 3 × 3) times [rgb].
    private fun apply(m: FloatArray, rgb: FloatArray) = FloatArray(3) { r ->
        m[r * 3] * rgb[0] + m[r * 3 + 1] * rgb[1] + m[r * 3 + 2] * rgb[2]
    }

    private fun luma(rgb: FloatArray) = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]

    @Test
    fun `the daylight locus gives D65 at 6504 K and the CIE's own points elsewhere`() {
        val (x, y) = ColourBalance.daylight(6504.0)
        assertEquals(0.3127, x, 0.0005)
        assertEquals(0.3290, y, 0.0005)
        // CIE 15: D50 (5003 K) and D75 (7504 K).
        val (x50, y50) = ColourBalance.daylight(5003.0)
        assertEquals(0.3457, x50, 0.0005)
        assertEquals(0.3585, y50, 0.0005)
        val (x75, y75) = ColourBalance.daylight(7504.0)
        assertEquals(0.2990, x75, 0.0005)
        assertEquals(0.3149, y75, 0.0005)
    }

    @Test
    fun `at D65 without tint the white is left alone`() {
        assertArrayEquals(identity, ColourBalance.whiteMatrix(ColourBalance.D65_KELVIN, 0f), 1e-5f)
    }

    @Test
    fun `warmer turns white towards red and yellow, colder towards blue, never past full`() {
        val warm = apply(ColourBalance.whiteMatrix(4000f, 0f), floatArrayOf(1f, 1f, 1f))
        assertTrue(warm.toList().toString(), warm[0] > warm[1] && warm[1] > warm[2])
        assertEquals(1f, warm.max(), 1e-5f)
        val cold = apply(ColourBalance.whiteMatrix(10_000f, 0f), floatArrayOf(1f, 1f, 1f))
        assertTrue(cold.toList().toString(), cold[2] > cold[1] && cold[1] > cold[0])
        assertEquals(1f, cold.max(), 1e-5f)
    }

    @Test
    fun `tint moves white towards magenta or green`() {
        val magenta = apply(ColourBalance.whiteMatrix(ColourBalance.D65_KELVIN, 1f), floatArrayOf(1f, 1f, 1f))
        assertTrue(magenta.toList().toString(), magenta[1] < magenta[0] && magenta[1] < magenta[2])
        val green = apply(ColourBalance.whiteMatrix(ColourBalance.D65_KELVIN, -1f), floatArrayOf(1f, 1f, 1f))
        assertTrue(green.toList().toString(), green[1] > green[0] && green[1] > green[2])
    }

    @Test
    fun `hue turns the colour round the grey axis and keeps every colour's brightness`() {
        assertArrayEquals(identity, ColourBalance.hueMatrix(0f), 1e-6f)
        val grey = floatArrayOf(0.4f, 0.4f, 0.4f)
        assertArrayEquals(grey, apply(ColourBalance.hueMatrix(73f), grey), 1e-5f)
        val red = floatArrayOf(0.8f, 0.2f, 0.1f)
        val turned = apply(ColourBalance.hueMatrix(180f), red)
        assertEquals(luma(red), luma(turned), 1e-5f)
        // Half a turn: red's opposite, more green and blue than red.
        assertTrue(turned.toList().toString(), turned[1] > turned[0] && turned[2] > turned[0])
        val back = apply(ColourBalance.hueMatrix(-90f), apply(ColourBalance.hueMatrix(90f), red))
        assertArrayEquals(red, back, 1e-5f)
    }
}
