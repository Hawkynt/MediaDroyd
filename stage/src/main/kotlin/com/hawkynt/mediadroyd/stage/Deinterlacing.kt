package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.player.Deinterlacer
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.Prescalers
import com.hawkynt.mediadroyd.shaders.Shaders

/**
 * The deinterlacers' passes (PictureAdjustment.Deinterlace): the temporal ones on three pictures (glsl/video/
 * temporal.frag: YADIF, BWDIF, Greedy HM, Greedy 2-Frame, TomsMoComp, Old console, Adaptive's weave), MC Smooth's
 * averaging along the motion, NNEDI3 on the field kept, and the spatial ones on one (deinterlace.frag: Auto, Blend,
 * Bob). Which pictures they read is the platform stage's; [topFirst] the field order, which decides the rows a field
 * is.
 */
class Deinterlacing(private val passes: Passes) {

    /**
     * [cur] into [into] with its [second] (or first) field rebuilt by [method] (Deinterlacer.shaderMethod, OLD_CONSOLE,
     * WEAVE) from [prev] and [next] — Old console's field [average]d with the one before; every texel read as it is.
     */
    fun field(
        prev: DrawTarget,
        cur: DrawTarget,
        next: DrawTarget,
        into: DrawTarget,
        second: Boolean,
        method: Int,
        topFirst: Boolean = true,
        average: Boolean = false,
    ): DrawTarget = passes.draw(
        Shaders.VIDEO_TEMPORAL, into, cur.texture, listOf(prev.texture to "uPrev", next.texture to "uNext"),
        nearest = true,
    ) {
        set("uSize", into.width.toFloat(), into.height.toFloat())
        setInt("uSecond", if (second) 1 else 0)
        set("uParity", Deinterlacer.rebuiltParity(second, topFirst).toFloat())
        setInt("uMethod", method)
        setInt("uAverage", if (average) 1 else 0)
    }

    /**
     * MC Smooth (TemporalSmoothing): [base], the shown field's BWDIF picture of [cur], averaged along the motion with
     * the BWDIF pictures of the fields before and after — into [earlier] and [later], from [prev], [next] and [further]
     * (the picture after [next], or [next] where there is none) — into [smoothed], then contra-sharpened, repaired and
     * the camera's rows of [cur] put back into [into].
     */
    fun smoothAlongMotion(
        base: DrawTarget,
        prev: DrawTarget,
        cur: DrawTarget,
        next: DrawTarget,
        further: DrawTarget,
        second: Boolean,
        earlier: DrawTarget,
        later: DrawTarget,
        smoothed: DrawTarget,
        into: DrawTarget,
        topFirst: Boolean = true,
    ): DrawTarget {
        val bwdif = Deinterlacer.shaderMethod(Deinterlacer.Method.BWDIF)
        if (second) {
            field(prev, cur, next, earlier, false, bwdif, topFirst)
            field(cur, next, further, later, false, bwdif, topFirst)
        } else {
            field(prev, prev, cur, earlier, true, bwdif, topFirst)
            field(prev, cur, next, later, true, bwdif, topFirst)
        }
        val w = base.width.toFloat()
        val h = base.height.toFloat()
        passes.draw(Shaders.VIDEO_MC_SMOOTH, smoothed, base.texture,
            listOf(earlier.texture to "uEarlier", later.texture to "uLater"), nearest = true) {
            set("uSize", w, h)
        }
        return passes.draw(Shaders.VIDEO_MC_SHARPEN, into, smoothed.texture,
            listOf(base.texture to "uBase", cur.texture to "uSource"), nearest = true) {
            set("uSize", w, h)
            set("uParity", Deinterlacer.rebuiltParity(second, topFirst).toFloat())
        }
    }

    /** [from] deinterlaced into [into] by a spatial [mode] (Auto, Blend, Bob: deinterlace.frag's uMode). */
    fun spatial(
        from: DrawTarget,
        into: DrawTarget,
        mode: PictureAdjustment.Deinterlace,
        topFirst: Boolean = true,
    ): DrawTarget = passes.draw(Shaders.VIDEO_DEINTERLACE, into, from.texture) {
        set("uTexel", 1f / from.width, 1f / from.height)
        setInt("uMode", mode.ordinal)
        set("uHeight", from.height.toFloat())
        set("uParity", Deinterlacer.rebuiltParity(false, topFirst).toFloat())
    }

    /**
     * [cur] into [into] with one field rebuilt by NNEDI3 (Prescalers.rows, #443): the field kept — the rows of the
     * parity Deinterlacer.rebuiltParity names for [second] and [topFirst] — taken out as a picture of half the height
     * into [field], its height doubled by the network through [hooks] (every other line kept, the ones between its
     * answers), and for a field of odd rows moved down a row into [into] so its lines land where they were. An odd
     * height loses its last row to the field and is stretched back by that row.
     */
    fun nnedi3(
        cur: DrawTarget,
        field: DrawTarget,
        into: DrawTarget,
        hooks: MpvHooks,
        finishing: Finishing,
        second: Boolean,
        topFirst: Boolean = true,
    ): DrawTarget {
        val parity = Deinterlacer.rebuiltParity(second, topFirst)
        passes.draw(Shaders.VIDEO_FIELD, field, cur.texture, nearest = true) {
            set("uSize", cur.width.toFloat(), cur.height.toFloat())
            set("uParity", parity.toFloat())
        }
        val sizes = MpvHooks.Sizes(cur.width, cur.height, cur.width, cur.height)
        val doubled = hooks.run(Prescalers.rows, field, listOf("LUMA"), sizes)
        // The network keeps the field's rows at the even rows from the top: a field of odd rows moves down by one,
        // reading a row higher up (towards v = 1, where the top row is).
        val shift = if (parity == 1) 1f / into.height else 0f
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, shift, 0f, 1f)
        return finishing.copy(doubled, into, matrix)
    }

    /** Builds every program the deinterlacers use. */
    fun build() {
        listOf(
            Shaders.VIDEO_TEMPORAL, Shaders.VIDEO_MC_SMOOTH, Shaders.VIDEO_MC_SHARPEN, Shaders.VIDEO_DEINTERLACE,
            Shaders.VIDEO_FIELD,
        )
            .forEach { passes.program(it) }
    }
}
