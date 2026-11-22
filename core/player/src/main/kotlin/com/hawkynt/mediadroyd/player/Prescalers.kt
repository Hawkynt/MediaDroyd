package com.hawkynt.mediadroyd.player

/**
 * NNEDI3 (#443): tritical's neural edge-directed interpolation — the AviSynth / VapourSynth filter QTGMC is built on —
 * each missing line a small network's answer over the 8 × 4 pixels around it, as Bin Jin's mpv prescalers write it in
 * GLSL (vendored verbatim in `Vendored/mpv-prescalers`, LGPL-3.0-or-later): the 32-neuron, 8 × 4 window variant.
 *
 * [nnedi3] doubles the picture's brightness both ways (its four passes, on the LUMA hook): an upscaler, the screen's
 * own scaling doing the rest. [rows] is its first two passes alone — the height doubled, every other line kept — what
 * deinterlacing runs on one field to rebuild the other.
 */
object Prescalers {
    private const val NNEDI3 = "nnedi3-nns32-win8x4.hook"

    /** The vendored shader's text. */
    private val text: String by lazy {
        checkNotNull(Prescalers::class.java.getResourceAsStream("/$NNEDI3")) { "$NNEDI3 is not on the class path" }
            .bufferedReader().use { it.readText() }
    }

    /** NNEDI3 doubling the brightness across and down. */
    val nnedi3: MpvShader by lazy { MpvShader.parse(text, "NNEDI3") }

    /** NNEDI3 doubling the height alone: the shader up to its third pass (`double_x`). */
    val rows: MpvShader by lazy {
        val passes = Regex("(?m)^//!DESC ").findAll(text).map { it.range.first }.toList()
        MpvShader.parse(text.substring(0, passes[2]), "NNEDI3 rows")
    }
}
