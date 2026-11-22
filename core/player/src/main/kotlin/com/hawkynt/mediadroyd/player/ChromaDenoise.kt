package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A tape's colour noise removed (#446): VHS records the colour at a fraction of the brightness's bandwidth and with far
 * more noise, so blotches crawl over faces and walls while the brightness stays clean. Only the colour (BT.709's Cb and
 * Cr) is filtered; every pixel keeps its brightness.
 *
 * Each pixel's colour is the weighted mean of the colours around it (5 × 5, a Gaussian of 2 pixels) in the frame shown
 * and, where nothing moves, in the frames before and after it: a joint bilateral filter guided by the brightness
 * (Petschnigg et al., "Digital photography with flash and no-flash image pairs", SIGGRAPH 2004), so colour is averaged
 * only among pixels of like brightness and stays on its own side of an edge; over time a side is taken where its
 * brightness differs from the frame shown's by at most [Params.motion] over the pixel and its neighbours left and right,
 * in part up to twice it, not past that (motion is told as DotCrawl tells it). Where the picture moves the colour is
 * thus smoothed along the picture alone, where it stands still over time as well.
 *
 * Written from the papers and the behaviour of AviSynth's CNR2 and FFmpeg's hqdn3d on the chroma planes (both GPL:
 * their documented behaviour as the spec, no code taken; AGENTS.md rung 2/3). The GL pass
 * (glsl/video/chroma-denoise.frag) is written from [apply], held to it by `ChromaDenoiseTest`.
 */
object ChromaDenoise {

    /**
     * The filter's thresholds in 8-bit steps: [guide] how far a neighbour's brightness may differ (the Gaussian's
     * deviation), [motion] how much brightness change over time counts as motion.
     */
    data class Params(val guide: Float, val motion: Float)

    /** The spatial Gaussian's deviation, in pixels; the taps reach [RADIUS] either way. */
    const val SPATIAL_SIGMA = 2f
    const val RADIUS = 2

    /** The thresholds for the slider's [strength], 0 to 1, or null at 0 (off): guide 2 to 10, motion 2 to 8. */
    fun params(strength: Float): Params? {
        if (strength <= 0f) return null
        val s = strength.coerceAtMost(1f)
        return Params(guide = 2f + 8f * s, motion = 2f + 6f * s)
    }

    /** BT.709's Y'CbCr ([luma], [cb], [cr], 8-bit units, Cb and Cr centred on 0) as R'G'B'. */
    fun rgb(luma: Float, cb: Float, cr: Float): FloatArray {
        val r = luma + 1.5748f * cr
        val b = luma + 1.8556f * cb
        val g = (luma - KR * r - KB * b) / KG
        return floatArrayOf(r, g, b)
    }

    /**
     * The frame [current] (8-bit RGB, rows from the top, [width] × [height]) with its colour denoised against [before]
     * and [after] by [params]; the brightness of every pixel kept.
     */
    fun apply(
        before: ByteArray,
        current: ByteArray,
        after: ByteArray,
        width: Int,
        height: Int,
        params: Params,
    ): ByteArray {
        require(current.size == width * height * 3 && before.size == current.size && after.size == current.size)
        val frames = listOf(before, current, after).map { planes(it, width * height) }
        val (lumaC, _, _) = frames[1]
        val out = ByteArray(current.size)
        val guide2 = 2f * params.guide * params.guide
        val space2 = 2f * SPATIAL_SIGMA * SPATIAL_SIGMA
        for (y in 0 until height) for (x in 0 until width) {
            val here = y * width + x
            val luma = lumaC[here]
            // How much of each side is taken: in full up to the motion threshold, in part up to twice it.
            val share = FloatArray(3) { f ->
                if (f == 1) {
                    1f
                } else {
                    var motion = 0f
                    for (dx in -1..1) {
                        val at = y * width + (x + dx).coerceIn(0, width - 1)
                        motion = maxOf(motion, abs(frames[f].first[at] - lumaC[at]))
                    }
                    (2f - motion / params.motion).coerceIn(0f, 1f)
                }
            }
            var sumB = 0f
            var sumR = 0f
            var weights = 0f
            for (f in 0..2) {
                if (share[f] <= 0f) continue
                val (l, cb, cr) = frames[f]
                for (dy in -RADIUS..RADIUS) for (dx in -RADIUS..RADIUS) {
                    val at = (y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)
                    val d = l[at] - luma
                    val w = share[f] * exp(-(dx * dx + dy * dy) / space2 - d * d / guide2)
                    sumB += w * cb[at]
                    sumR += w * cr[at]
                    weights += w
                }
            }
            val rgb = rgb(luma, sumB / weights, sumR / weights)
            for (c in 0..2) out[here * 3 + c] = rgb[c].roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    // [rgb]'s n pixels as BT.709 Y', Cb, Cr (8-bit units).
    private fun planes(rgb: ByteArray, n: Int): Triple<FloatArray, FloatArray, FloatArray> {
        val l = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        for (i in 0 until n) {
            val r = (rgb[i * 3].toInt() and 0xFF).toFloat()
            val g = (rgb[i * 3 + 1].toInt() and 0xFF).toFloat()
            val b = (rgb[i * 3 + 2].toInt() and 0xFF).toFloat()
            val y = KR * r + KG * g + KB * b
            l[i] = y
            cb[i] = (b - y) / 1.8556f
            cr[i] = (r - y) / 1.5748f
        }
        return Triple(l, cb, cr)
    }

    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f
}
