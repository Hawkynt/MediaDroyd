package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.math.round

/**
 * The colour put back onto the picture (#444): tapes and composite captures often carry the colour a few pixels right
 * of or below the brightness — a red coat with a red edge beside it. The stage moves the colour (Cb and Cr in BT.709's
 * Y'CbCr) against the brightness by an offset across and down in quarter pixels, from the gear or per folder, as
 * DScaler's chroma delay and AviSynth's ChromaShift do (their behaviour as the spec, the code our own).
 *
 * Auto finds the offset itself ([estimate], [Auto]): the colour's edges against the brightness's — for each row the
 * steps of the brightness and of the colour, the colour's slid along until the two line up best (their correlation's
 * peak, refined between pixels by a parabola through it and its neighbours), likewise down the columns.
 */
object ChromaShift {
    /** How far either way the colour can be moved, in pixels. */
    const val MAX_PX = 8f

    /** [pixels] to the nearest quarter pixel, within [MAX_PX]. */
    fun quarter(pixels: Float): Float = (round(pixels * 4) / 4).coerceIn(-MAX_PX, MAX_PX)

    // The least mean colour step (8-bit units) for a picture to say where its colour sits; a grey one says nothing.
    private const val MIN_COLOUR_STEP = 0.4

    /**
     * Where [rgba]'s colour sits against its brightness ([width] × [height], rows top first, [bytesPerChannel] 1 or 2,
     * 16-bit little-endian with its high byte taken): the offset across and down in pixels (positive: right, down),
     * or null where the picture has too little colour, or too few edges, to tell.
     */
    fun estimate(rgba: ByteArray, width: Int, height: Int, bytesPerChannel: Int = 1): Pair<Float, Float>? {
        if (width < 8 || height < 8) return null
        val n = width * height
        val luma = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        val high = bytesPerChannel - 1
        for (i in 0 until n) {
            val at = i * 4 * bytesPerChannel + high
            val r = (rgba[at].toInt() and 0xff).toFloat()
            val g = (rgba[at + bytesPerChannel].toInt() and 0xff).toFloat()
            val b = (rgba[at + 2 * bytesPerChannel].toInt() and 0xff).toFloat()
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            luma[i] = y
            cb[i] = (b - y) / 1.8556f
            cr[i] = (r - y) / 1.5748f
        }
        val across = offset(luma, cb, cr, width, height, 1, width) ?: return null
        val down = offset(luma, cb, cr, height, width, width, 1) ?: return null
        return across to down
    }

    /** How much of the picture's centre [centre] hands to [estimate]: enough edges, little work. */
    const val CENTRE_WIDTH = 480
    const val CENTRE_HEIGHT = 270

    /**
     * The middle of [rgba] ([width] × [height], [bytesPerChannel] 1 or 2), at most [CENTRE_WIDTH] × [CENTRE_HEIGHT] of
     * it at its own pixels — the offset is a matter of pixels, so it is cut out, never scaled — as 8-bit RGBA rows top
     * first, with its size.
     */
    fun centre(rgba: ByteArray, width: Int, height: Int, bytesPerChannel: Int = 1): Triple<ByteArray, Int, Int> {
        val w = minOf(width, CENTRE_WIDTH)
        val h = minOf(height, CENTRE_HEIGHT)
        val left = (width - w) / 2
        val top = (height - h) / 2
        val out = ByteArray(w * h * 4)
        val high = bytesPerChannel - 1
        for (y in 0 until h) {
            for (x in 0 until w * 4) {
                out[y * w * 4 + x] = rgba[(((top + y) * width + left) * 4 + x) * bytesPerChannel + high]
            }
        }
        return Triple(out, w, h)
    }

    // The colour's offset along lines of [length] pixels [step] apart in memory, [lines] of them [lineStep] apart.
    private fun offset(
        luma: FloatArray,
        cb: FloatArray,
        cr: FloatArray,
        length: Int,
        lines: Int,
        step: Int,
        lineStep: Int,
    ): Float? {
        val reach = minOf(MAX_PX.toInt(), length / 4)
        val scores = DoubleArray(2 * reach + 1)
        var colour = 0.0
        var counted = 0
        val ly = FloatArray(length)
        val lc = FloatArray(length)
        for (line in 0 until lines) {
            val base = line * lineStep
            for (x in 1 until length - 1) {
                val a = base + (x - 1) * step
                val b = base + (x + 1) * step
                ly[x] = abs(luma[b] - luma[a])
                lc[x] = abs(cb[b] - cb[a]) + abs(cr[b] - cr[a])
                colour += lc[x]
                counted++
            }
            for (d in -reach..reach) {
                var sum = 0.0
                for (x in 1 + reach until length - 1 - reach) sum += ly[x] * lc[x + d]
                scores[d + reach] += sum
            }
        }
        if (counted == 0 || colour / counted < MIN_COLOUR_STEP) return null
        val best = scores.indices.maxBy { scores[it] }
        if (best == 0 || best == scores.size - 1 || scores[best] <= 0.0) return null
        val left = scores[best - 1]
        val mid = scores[best]
        val right = scores[best + 1]
        val bend = left - 2 * mid + right
        val fine = if (bend < 0) (left - right) / (2 * bend) else 0.0
        return (best - reach + fine).toFloat()
    }

    /**
     * Auto: the offset from [SAMPLES] estimates taken as the video plays — across and down each the median, to the
     * nearest quarter pixel — and held from then on, until [restart] (another video).
     */
    class Auto {
        private val across = ArrayList<Float>()
        private val down = ArrayList<Float>()

        /** The offset found, across and down in pixels; none until decided. */
        var shift: Pair<Float, Float> = 0f to 0f
            private set

        /** Whether the offset is decided. */
        val decided: Boolean get() = across.size >= SAMPLES

        /** Takes one estimate ([estimate]); returns whether the offset changed. */
        fun add(estimate: Pair<Float, Float>): Boolean {
            if (decided) return false
            across += estimate.first
            down += estimate.second
            if (!decided) return false
            val before = shift
            shift = quarter(across.sorted()[SAMPLES / 2]) to quarter(down.sorted()[SAMPLES / 2])
            return shift != before
        }

        /** Forgets the samples: the offset decided afresh. */
        fun restart() {
            across.clear()
            down.clear()
            shift = 0f to 0f
        }

        companion object {
            /** Estimates the offset is decided from, one every [INTERVAL_MS]. */
            const val SAMPLES = 9
            const val INTERVAL_MS = 2000L
        }
    }
}
