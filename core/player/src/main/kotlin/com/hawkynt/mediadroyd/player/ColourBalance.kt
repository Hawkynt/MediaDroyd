package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hue, tint and colour temperature (#447), for old tapes and film transfers that lean green or magenta, warm or cold.
 *
 * Temperature and tint move the picture's white: from D65 to the white of the CIE daylight locus at the temperature
 * ([daylight]), tint shifting it across the locus in CIE 1960 UCS (positive towards magenta, negative towards green),
 * the colours following by the Bradford chromatic adaptation — on linear BT.709 light ([whiteMatrix]), scaled so the
 * new white just reaches full scale. Hue turns the colour round the grey axis in BT.709's Y'CbCr, every colour keeping
 * its luma ([hueMatrix]), as VLC's and DScaler's hue does on the coded values.
 *
 * Sourcing: rung 3 — the daylight locus from CIE 15:2004, the Bradford matrix as Lindbloom and the ICC give it, the
 * BT.709 primaries and luma weights from ITU-R BT.709 (all constants).
 */
object ColourBalance {
    /** The temperature that leaves the picture as it is: D65's on the daylight locus. */
    const val D65_KELVIN = 6504f

    /** How far the temperature reaches: the daylight locus's own range is 4000 K up. */
    const val MIN_KELVIN = 4000f
    const val MAX_KELVIN = 10_000f

    /** How far a full tint moves the white across the locus, in CIE 1960 uv: about the most a lamp is off it. */
    const val TINT_UV = 0.02

    /** The CIE daylight locus's chromaticity x, y at [kelvin] (4000 … 25000 K; CIE 15:2004, eq. 3.3–3.4). */
    fun daylight(kelvin: Double): Pair<Double, Double> {
        val t = kelvin.coerceIn(4000.0, 25_000.0)
        val x = if (t <= 7000) {
            -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
        } else {
            -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040
        }
        return x to -3.000 * x * x + 2.870 * x - 0.275
    }

    /**
     * The 3 × 3 matrix (row-major, linear BT.709 RGB) that moves D65 white to the daylight white at [kelvin], shifted by
     * [tint] (−1 … 1) across the locus: identity at [D65_KELVIN] without tint. Scaled so the new white's largest
     * channel is 1.
     */
    fun whiteMatrix(kelvin: Float, tint: Float): FloatArray {
        val source = whiteXyz(daylight(D65_KELVIN.toDouble()), 0.0)
        val target = whiteXyz(daylight(kelvin.toDouble()), tint.toDouble())
        val s = mul(BRADFORD, source)
        val d = mul(BRADFORD, target)
        val scale = doubleArrayOf(d[0] / s[0], 0.0, 0.0, 0.0, d[1] / s[1], 0.0, 0.0, 0.0, d[2] / s[2])
        val adapt = mul(mul(BRADFORD_INVERSE, scale), BRADFORD)
        val m = mul(mul(XYZ_TO_RGB, adapt), RGB_TO_XYZ)
        val white = DoubleArray(3) { r -> m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2] }
        val peak = white.max()
        return FloatArray(9) { (m[it] / peak).toFloat() }
    }

    /** The 3 × 3 matrix (row-major, on BT.709 R'G'B') turning the colour by [degrees] round the grey axis. */
    fun hueMatrix(degrees: Float): FloatArray {
        val a = degrees * PI / 180
        val c = cos(a)
        val s = sin(a)
        // R'G'B' → Y'CbCr (BT.709), the chroma turned, back.
        val toYcc = doubleArrayOf(
            KR, KG, KB,
            -KR / (2 * (1 - KB)), -KG / (2 * (1 - KB)), 0.5,
            0.5, -KG / (2 * (1 - KR)), -KB / (2 * (1 - KR)),
        )
        val turn = doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
        val fromYcc = doubleArrayOf(
            1.0, 0.0, 2 * (1 - KR),
            1.0, -2 * KB * (1 - KB) / KG, -2 * KR * (1 - KR) / KG,
            1.0, 2 * (1 - KB), 0.0,
        )
        val m = mul(mul(fromYcc, turn), toYcc)
        return FloatArray(9) { m[it].toFloat() }
    }

    // The white x, y as XYZ with Y = 1, moved [tint] across the locus in CIE 1960 uv (v down: magenta).
    private fun whiteXyz(xy: Pair<Double, Double>, tint: Double): DoubleArray {
        val (x, y) = xy
        val d = -2 * x + 12 * y + 3
        val u = 4 * x / d
        val v = 6 * y / d - tint * TINT_UV
        val d2 = 2 * u - 8 * v + 4
        val x2 = 3 * u / d2
        val y2 = 2 * v / d2
        return doubleArrayOf(x2 / y2, 1.0, (1 - x2 - y2) / y2)
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray =
        if (b.size == 3) {
            DoubleArray(3) { r -> a[r * 3] * b[0] + a[r * 3 + 1] * b[1] + a[r * 3 + 2] * b[2] }
        } else {
            DoubleArray(9) { i ->
                val r = i / 3
                val c = i % 3
                a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
            }
        }

    // BT.709's luma weights.
    private const val KR = 0.2126
    private const val KB = 0.0722
    private const val KG = 1 - KR - KB

    // Bradford's cone response matrix and its inverse (Lindbloom).
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val BRADFORD_INVERSE = doubleArrayOf(
        0.9869929, -0.1470543, 0.1599627,
        0.4323053, 0.5183603, 0.0492912,
        -0.0085287, 0.0400428, 0.9684867,
    )

    // Linear BT.709 / sRGB to XYZ (D65) and back (Lindbloom, from the BT.709 primaries).
    private val RGB_TO_XYZ = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )
    private val XYZ_TO_RGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252,
    )
}
