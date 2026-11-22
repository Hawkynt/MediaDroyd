package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.player.CubeLut
import com.hawkynt.mediadroyd.player.Dither
import com.hawkynt.mediadroyd.player.MotionInterpolation
import com.hawkynt.mediadroyd.player.NeuralInterpolation
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.ScreenWarp
import com.hawkynt.mediadroyd.shaders.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The passes that finish a picture: two pictures mixed (blend.frag: smooth motion's blending, an upscaler's strength, a
 * plain copy) or built along the motion between them (motion.frag: Motion, MotionInterpolation; rife-warp-es3.frag:
 * RIFE's flow), the colour correction
 * with the projector's warp and the panorama (adjust.frag), a `.cube` table's look or calibration (lut-es3.frag), and
 * the dither to 8 bits (dither-es3.frag, dither.frag,
 * libplacebo's blue noise, its table made on first use).
 */
class Finishing(private val passes: Passes) {
    private var noise = 0

    /** [from] into [into] as it is, read through [matrix] (a blend wholly of the one picture). */
    fun copy(from: DrawTarget, into: DrawTarget, matrix: FloatArray = Passes.IDENTITY): DrawTarget =
        passes.draw(Shaders.VIDEO_BLEND, into, from.texture, listOf(from.texture to "uPrevious"), matrix = matrix) {
            set("uWeight", 1f)
        }

    /** [previous] and [current] mixed into [into]: [weight] 0 is [previous], 1 [current]. */
    fun blend(previous: DrawTarget, current: DrawTarget, into: DrawTarget, weight: Float): DrawTarget =
        passes.draw(Shaders.VIDEO_BLEND, into, current.texture, listOf(previous.texture to "uPrevious")) {
            set("uWeight", weight)
        }

    /**
     * The picture [weight] of the way from [previous] to [current], built into [into] along the motion search's
     * vectors ([vectors]: a texture of [gridWidth] × [gridHeight] blocks, searched at 1/[scale] of the size) with the
     * overlapped blocks' weights [obmc] (MotionInterpolation, FFmpeg's minterpolate).
     */
    fun motion(
        previous: DrawTarget,
        current: DrawTarget,
        vectors: Int,
        obmc: Int,
        into: DrawTarget,
        weight: Float,
        gridWidth: Int,
        gridHeight: Int,
        scale: Int,
    ): DrawTarget = passes.draw(
        Shaders.VIDEO_MOTION, into, current.texture,
        listOf(previous.texture to "uPrevious", vectors to "uVectors", obmc to "uObmc"),
    ) {
        set("uAlpha", (weight * MotionInterpolation.ALPHA_MAX).toInt().toFloat())
        set("uSize", into.width.toFloat(), into.height.toFloat())
        set("uGrid", gridWidth.toFloat(), gridHeight.toFloat())
        set("uSearch", (into.width / scale).toFloat(), (into.height / scale).toFloat())
        set("uScale", scale.toFloat())
    }

    /**
     * A network's in-between of [earlier] and [later] at their own size into [into] (rife-warp-es3.frag): its [flow]
     * and [mask] (float textures of [flowWidth] × [flowHeight], NeuralInterpolation.handsFlow; the mask's logit in red,
     * the residual in the rest) grown to the frames' size, each frame warped along its flow, the two blended by the
     * mask and the residual added. GLSL ES 3.00.
     */
    fun warp(
        earlier: DrawTarget,
        later: DrawTarget,
        flow: Int,
        mask: Int,
        into: DrawTarget,
        flowWidth: Int,
        flowHeight: Int,
    ): DrawTarget = passes.draw(
        Shaders.VIDEO_RIFE_WARP_ES3, into, later.texture,
        listOf(earlier.texture to "uPrevious", flow to "uFlow", mask to "uMask"),
    ) {
        val (sx, sy) = NeuralInterpolation.flowScale(
            NeuralInterpolation.Size(into.width, into.height), NeuralInterpolation.Size(flowWidth, flowHeight),
        )
        set("uSize", into.width.toFloat(), into.height.toFloat())
        set("uScale", sx, sy)
    }

    /** [from] colour-corrected as [picture] says into [into], its geometry bent by [warp] (ScreenWarp). */
    fun adjust(from: DrawTarget, into: DrawTarget, picture: PictureAdjustment, warp: ScreenWarp): DrawTarget {
        val u = picture.shaderUniforms()
        return passes.draw(Shaders.VIDEO_ADJUST, into, from.texture) {
            set("uBrightness", u.brightness)
            set("uContrast", u.contrast)
            set("uSaturation", u.saturation)
            set("uInverseGamma", u.inverseGamma)
            set("uWhiteOn", if (u.white != null) 1f else 0f)
            // Row-major as ColourBalance makes them, GL reads columns.
            matrix3("uWhite", columns(u.white ?: u.hue))
            matrix3("uHue", columns(u.hue))
            set("uPanorama", warp.panorama)
            matrix3("uWarp", warp.matrix)
            set("uWarped", 1f)
        }
    }

    // The tables' textures, the most recently used last: a look and a screen's calibration at most at a time.
    private val lutTextures = LinkedHashMap<CubeLut, Int>()

    /**
     * [from] through the `.cube` table [lut] into [into] (CubeLut, by tetrahedra): its texture made on first use and
     * kept while it is one of the last [LUTS] tables used. GLSL ES 3.00.
     */
    fun lut(from: DrawTarget, into: DrawTarget, lut: CubeLut): DrawTarget {
        val texture = lutTextures.remove(lut) ?: run {
            val t = lut.texture()
            val data = ByteBuffer.allocateDirect(t.rgba.size * 4).order(ByteOrder.nativeOrder())
            data.asFloatBuffer().put(t.rgba)
            // The least recently used table's texture refilled where there are enough.
            val reuse = if (lutTextures.size >= LUTS) lutTextures.remove(lutTextures.keys.first()) ?: 0 else 0
            passes.floats(reuse, t.width, t.height, 4, data)
        }
        lutTextures[lut] = texture
        return passes.draw(Shaders.VIDEO_LUT_ES3, into, from.texture, listOf(texture to "uLut")) {
            setInt("uSize", lut.size)
            setInt("uTiles", lut.tilesAcross)
            setInt("uThreeD", if (lut.threeD) 1 else 0)
            set("uDomainMin", lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
            set("uDomainMax", lut.domainMax[0], lut.domainMax[1], lut.domainMax[2])
        }
    }

    private fun columns(m: FloatArray) = FloatArray(9) { m[(it % 3) * 3 + it / 3] }

    /**
     * [from] (in half floats) dithered to 8 bits into [into] with libplacebo's blue noise: GLSL ES 3.00's pass, which
     * reads its texels exactly, where [es3]; else GLSL ES 1.00's. [into]'s top row is where v is 1 (libplacebo counts
     * rows from the top).
     */
    fun dither(from: DrawTarget, into: DrawTarget, es3: Boolean = true): DrawTarget {
        if (noise == 0) noise = passes.table(Dither.SIZE, Dither.SIZE, StageTables.noise())
        return passes.draw(
            if (es3) Shaders.VIDEO_DITHER_ES3 else Shaders.VIDEO_DITHER, into, from.texture, listOf(noise to "uNoise"),
        ) {
            set("uSize", into.width.toFloat(), into.height.toFloat())
            set("uRows", into.height.toFloat(), -1f)
        }
    }

    /** Builds the programs: blending, Motion and the colour correction everywhere. */
    fun build() {
        listOf(Shaders.VIDEO_BLEND, Shaders.VIDEO_MOTION, Shaders.VIDEO_ADJUST).forEach { passes.program(it) }
    }

    private companion object {
        /** How many tables' textures are kept: a look and a calibration. */
        const val LUTS = 2
    }
}
