package com.hawkynt.mediadroyd.player

import kotlin.math.abs

/**
 * Scene cuts between the pairs of frames a picture is mixed from (#442): RIFE, IFRNet, CAIN, DAIN, XVFI and FLAVR
 * would morph one shot into the next and a blend would cross-fade them, so across a cut the earlier frame is held
 * until the later is due. Scored as FFmpeg's scdet scores a frame (and minterpolate its scene changes,
 * [MotionInterpolation.SceneChange]): the mean absolute difference of the two frames in percent of full scale, its
 * jump from the pair before, the smaller of the two — a cut where it reaches [THRESHOLD]. Steady fast motion differs
 * a lot from frame to frame, but no more than the pair before, so it is no cut.
 *
 * Verdicts are kept by pair and the planes by frame, so asking again costs nothing; frames are named by ids, the same
 * frame the same id. Sourcing: rung 3 — scdet's formula and default threshold as FFmpeg documents and computes them
 * (constants), on a small luma plane ([luma]) rather than every pixel.
 */
class SceneCuts {
    private val planes = LinkedHashMap<Long, Deinterlacer.Plane?>()
    private val verdicts = LinkedHashMap<Pair<Long, Long>, Boolean>()
    private var lastLaterId: Long? = null
    private var lastMafd = 0.0

    /**
     * Whether the frames [earlierId] and [laterId] are a cut apart, their luma planes made by [plane] (null where a
     * frame cannot be read: no cut). The jump is measured from the pair ending in [earlierId] where that was the last
     * asked, else from nothing — the first pair after a seek, which a cut then rather holds a frame too many.
     */
    fun isCut(earlierId: Long, laterId: Long, plane: (Long) -> Deinterlacer.Plane?): Boolean {
        verdicts[earlierId to laterId]?.let { return it }
        val a = planeOf(earlierId, plane)
        val b = planeOf(laterId, plane)
        if (a == null || b == null || a.width != b.width || a.height != b.height) return false
        val mafd = mafd(a, b)
        val before = if (lastLaterId == earlierId) lastMafd else 0.0
        val cut = minOf(mafd, abs(mafd - before)).coerceIn(0.0, 100.0) >= THRESHOLD
        lastLaterId = laterId
        lastMafd = mafd
        verdicts[earlierId to laterId] = cut
        while (verdicts.size > KEPT) verdicts.remove(verdicts.keys.first())
        return cut
    }

    private fun planeOf(id: Long, plane: (Long) -> Deinterlacer.Plane?): Deinterlacer.Plane? {
        if (id in planes) return planes[id]
        return plane(id).also {
            planes[id] = it
            while (planes.size > KEPT) planes.remove(planes.keys.first())
        }
    }

    companion object {
        /** scdet's default threshold, in percent. */
        const val THRESHOLD = 10.0

        /** How wide the luma plane a frame is judged by is. */
        const val COLUMNS = 64

        // Pairs and planes kept: the few around the picture shown.
        private const val KEPT = 8

        /** The mean absolute difference of [a] and [b] (8-bit) in percent of full scale: scdet's mafd. */
        fun mafd(a: Deinterlacer.Plane, b: Deinterlacer.Plane): Double {
            var sad = 0L
            for (k in a.pixels.indices) sad += abs(a.pixels[k] - b.pixels[k])
            return sad.toDouble() * 100.0 / (a.width * a.height) / (1 shl 8)
        }

        /**
         * [rgba] ([width] × [height], rows top first, [bytesPerChannel] 1 or 2 — 16-bit little-endian, its high
         * byte taken) as a luma plane [COLUMNS] wide, the shape kept: a pixel sampled at each cell's corner, weighed
         * by BT.601 as FFmpeg's gray.
         */
        fun luma(rgba: ByteArray, width: Int, height: Int, bytesPerChannel: Int = 1): Deinterlacer.Plane {
            val columns = minOf(COLUMNS, width).coerceAtLeast(1)
            val rows = (height.toLong() * columns / width).toInt().coerceAtLeast(1)
            val pixels = IntArray(columns * rows)
            val high = bytesPerChannel - 1
            for (y in 0 until rows) {
                val sy = y * height / rows
                for (x in 0 until columns) {
                    val at = ((sy * width + x * width / columns) * 4) * bytesPerChannel + high
                    val r = rgba[at].toInt() and 0xff
                    val g = rgba[at + bytesPerChannel].toInt() and 0xff
                    val b = rgba[at + 2 * bytesPerChannel].toInt() and 0xff
                    pixels[y * columns + x] = (77 * r + 150 * g + 29 * b + 128) shr 8
                }
            }
            return Deinterlacer.Plane(columns, rows, pixels)
        }
    }
}
