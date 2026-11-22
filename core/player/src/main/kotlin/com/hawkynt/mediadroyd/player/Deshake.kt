package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shaky home videos steadied (#449): handheld camcorders and phones shake, and families watch a lot of them. Each
 * frame's camera motion is found from the blocks Motion's search already finds between it and the frame before
 * ([global]) — the most common block vector, the cleaned mean of the blocks' turns about the middle — its path smoothed
 * by a one-sided exponential average ([Smoother]), and the frame moved against what is left, the jitter, its edges
 * hidden by a slight zoom ([ZOOM]) and mirrored where the move reaches past even that.
 *
 * The smoothing — the average over 20 frames, the correction kept absolute and shrunk by a tenth each frame so the
 * picture drifts back to the middle — is converted from FFmpeg's deshake (`libavfilter/vf_deshake.c`, `filter_frame`,
 * `clean_mean`; LGPL-2.1-or-later, © 2010 Georg Martius, Daniel G. Taylor) and held to its own log by `DeshakeTest`.
 * The motion comes from our own search, not deshake's, and turns are measured about the picture's middle (deshake
 * measures and turns about its top-left corner), so its picture is not deshake's to the pixel.
 */
object Deshake {

    /** A frame's motion: [x], [y] in pixels (the camera's, so the picture moved the other way), [angle] in radians. */
    data class Motion(val x: Double = 0.0, val y: Double = 0.0, val angle: Double = 0.0)

    /** How much the picture is zoomed so a correction's edges stay out of sight: 4 %. */
    const val ZOOM = 1.04

    /** deshake's reference frames, which set the average's window (`refcount`, its default). */
    const val REFERENCE_FRAMES = 20

    /**
     * The camera's motion from the earlier frame to the later one, from Motion's search between them ([motion]: half-
     * vectors of 16-pixel blocks at its scale, over a picture of [width] × [height]): the most common vector — the
     * camera moving opposite to what it films — and the cleaned mean of each block's turn about the picture's middle;
     * null where no block was searched.
     */
    fun global(motion: TemporalDenoise.Motion, width: Int, height: Int): Motion? {
        val blocks = motion.gridWidth * motion.gridHeight
        if (blocks == 0) return null
        // The content's move in the picture's pixels: twice the half-vector, at the search's scale — the half-vector
        // points from the later frame back to the earlier (TemporalDenoise.Side), against the move.
        val counts = HashMap<Long, Int>()
        val angles = ArrayList<Double>()
        val step = MotionInterpolation.BLOCK * motion.scale
        val cx = width / 2.0
        val cy = height / 2.0
        for (k in 0 until blocks) {
            val dx = -2 * motion.vectors[k * 2] * motion.scale
            val dy = -2 * motion.vectors[k * 2 + 1] * motion.scale
            val key = (dx.toLong() shl 32) or (dy.toLong() and 0xffffffffL)
            counts[key] = (counts[key] ?: 0) + 1
            // The block's middle, and where its content went, about the picture's middle.
            val x = (k % motion.gridWidth + 0.5) * step - cx
            val y = (k / motion.gridWidth + 0.5) * step - cy
            if (x * x + y * y < MIN_RADIUS * MIN_RADIUS) continue
            angles += turn(atan2(y + dy, x + dx) - atan2(y, x))
        }
        val (key, _) = counts.maxBy { it.value }
        val dx = (key shr 32).toInt()
        val dy = key.toInt()
        val angle = if (angles.isEmpty()) 0.0 else cleanMean(angles.toDoubleArray())
        // The content moved by (dx, dy) and turned by angle: the camera the other way.
        return Motion(-dx.toDouble(), -dy.toDouble(), -angle)
    }

    // An angle brought into -π .. π.
    private fun turn(a: Double): Double = when {
        a > PI -> a - 2 * PI
        a < -PI -> a + 2 * PI
        else -> a
    }

    /** deshake's `clean_mean`: [values] sorted, a fifth cut off each end, the rest averaged. */
    fun cleanMean(values: DoubleArray): Double {
        values.sort()
        val cut = values.size / 5
        var mean = 0.0
        for (i in cut until values.size - cut) mean += values[i]
        return mean / (values.size - cut * 2)
    }

    /**
     * deshake's smoothing, frame by frame: the motion averaged one-sidedly over [frames] (alpha 2 / [frames]), the
     * jitter — the motion less that average — undone and added to the correction so far, which shrinks by a tenth each
     * frame to keep the picture centred.
     */
    class Smoother(frames: Int = REFERENCE_FRAMES) {
        private val alpha = (2.0f / frames).toDouble()
        private var avgX = 0.0
        private var avgY = 0.0
        private var avgAngle = 0.0
        private var last = Motion()

        /** The average so far (deshake's "Avg" columns). */
        val average: Motion get() = Motion(avgX, avgY, avgAngle)

        /** The correction for the next frame, whose motion from the frame before is [found]. */
        fun next(found: Motion): Motion {
            avgX = alpha * found.x + (1.0 - alpha) * avgX
            avgY = alpha * found.y + (1.0 - alpha) * avgY
            avgAngle = alpha * found.angle + (1.0 - alpha) * avgAngle
            val x = (-(found.x - avgX) + last.x) * SHRINK
            val y = (-(found.y - avgY) + last.y) * SHRINK
            val angle = (-(found.angle - avgAngle) + last.angle) * SHRINK
            last = Motion(x, y, angle)
            return last
        }

        /** Forgets the path: a cut, a seek, another video. */
        fun reset() {
            avgX = 0.0
            avgY = 0.0
            avgAngle = 0.0
            last = Motion()
        }
    }

    /**
     * Where each pixel of the steadied picture is read from in a [width] × [height] frame corrected by [correction]:
     * a 3 × 3 matrix, row by row, on pixel coordinates — turned about the middle, moved, and zoomed by [ZOOM] about
     * the middle. deshake's own matrix (`ff_get_matrix`) reads a pixel at (x, y) from x·cos − y·sin + dx, x·sin +
     * y·cos + dy; this one does the same about the middle.
     */
    fun matrix(correction: Motion, width: Int, height: Int, zoom: Double = ZOOM): DoubleArray {
        val c = cos(correction.angle) / zoom
        val s = sin(correction.angle) / zoom
        val cx = width / 2.0
        val cy = height / 2.0
        // p_source = R·(p − centre)/zoom + centre + d
        return doubleArrayOf(
            c, -s, cx - c * cx + s * cy + correction.x,
            s, c, cy - s * cx - c * cy + correction.y,
            0.0, 0.0, 1.0,
        )
    }

    /**
     * [matrix] (see [matrix], on pixels from the top left of a [width] × [height] frame) on the stage's texture
     * coordinates — 0 to 1, the picture's top row at v = 1 — column by column, as GLSL's mat3 takes it.
     */
    fun textureMatrix(matrix: DoubleArray, width: Int, height: Int): FloatArray {
        val w = width.toDouble()
        val h = height.toDouble()
        // x = u·w, y = (1 − v)·h; u' = x'/w, v' = 1 − y'/h.
        val rows = doubleArrayOf(
            matrix[0], -matrix[1] * h / w, (matrix[1] * h + matrix[2]) / w,
            -matrix[3] * w / h, matrix[4], 1 - matrix[4] - matrix[5] / h,
            0.0, 0.0, 1.0,
        )
        return FloatArray(9) { rows[(it % 3) * 3 + it / 3].toFloat() }
    }

    /**
     * [frame] (8-bit grey, [width] × [height], rows from the top) read through [matrix] (see [matrix]): bilinear, and
     * mirrored at the edges as deshake's default edge mode mirrors — the reference the GL pass is held to.
     */
    fun apply(frame: IntArray, width: Int, height: Int, matrix: DoubleArray): IntArray = IntArray(width * height) {
        val x = it % width
        val y = it / width
        val sx = matrix[0] * x + matrix[1] * y + matrix[2]
        val sy = matrix[3] * x + matrix[4] * y + matrix[5]
        val x0 = kotlin.math.floor(sx).toInt()
        val y0 = kotlin.math.floor(sy).toInt()
        val fx = sx - x0
        val fy = sy - y0
        fun at(px: Int, py: Int) = frame[mirror(py, height) * width + mirror(px, width)]
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        (top * (1 - fy) + bottom * fy + 0.5).toInt()
    }

    // A coordinate mirrored back into 0 until size, as deshake's FILL_MIRROR does.
    private fun mirror(v: Int, size: Int): Int {
        var p = v
        if (p < 0) p = -p
        if (p >= size) p = 2 * size - 2 - p
        return p.coerceIn(0, size - 1)
    }

    // deshake's shrink towards the middle.
    private const val SHRINK = 0.9

    // Blocks this close to the middle say little about a turn.
    private const val MIN_RADIUS = 32.0
}
