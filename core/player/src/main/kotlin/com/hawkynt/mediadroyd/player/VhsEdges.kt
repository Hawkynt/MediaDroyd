package com.hawkynt.mediadroyd.player

import kotlin.math.abs

/**
 * A tape's edges cleaned (#445): VHS captures show the head-switching tear in their last lines — dragged sideways and
 * noisy — and dirty edges a TV hid under its overscan. A crop per edge, in the decoded picture's pixels, cuts them
 * away before every other filter, inside the black bars' crop where there is one ([crop]); Auto finds the torn lines
 * at the bottom itself ([tornLines], [Auto]). DScaler's overscan setting is the behaviour; the tear is found by the
 * line-to-line jump in horizontal position, as the issue describes it (our own code).
 */
object VhsEdges {
    /** Pixels cut from each edge of the decoded picture. */
    data class Edges(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
        val isNone: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0
    }

    /** The most a crop slider reaches, in pixels. */
    const val MAX_PX = 64

    /** How many of the last lines are looked at for the tear, and the most a line may be dragged. */
    const val LOOK_LINES = 32
    private const val MAX_DRAG = 48

    /** A line dragged this far from the one above it is torn. */
    private const val TORN_DRAG = 2

    /**
     * The part of a [width] × [height] picture shown once [edges] and Auto's [autoBottom] lines are cut away: from the
     * black bars' crop [bars] where there is one, else from the whole picture; each side's cut held to a quarter of the
     * picture so something always stays. Null where nothing is cut and there are no bars.
     */
    fun crop(edges: Edges, bars: PictureCrop?, width: Int, height: Int, autoBottom: Int = 0): PictureCrop? {
        val bottom = maxOf(edges.bottom, autoBottom)
        if (edges.isNone && bottom == 0) return bars
        val base = bars ?: PictureCrop(0, 0, width, height)
        val baseRight = base.left + base.width
        val baseBottom = base.top + base.height
        val left = maxOf(base.left, edges.left.coerceIn(0, width / 4))
        val top = maxOf(base.top, edges.top.coerceIn(0, height / 4))
        val right = minOf(baseRight, width - edges.right.coerceIn(0, width / 4))
        val lower = minOf(baseBottom, height - bottom.coerceIn(0, height / 4))
        return PictureCrop(left, top, (right - left).coerceAtLeast(1), (lower - top).coerceAtLeast(1))
    }

    /**
     * How many of [luma]'s last lines are torn (rows from the top): from the bottom up, each line whose best match with
     * the line above it lies [TORN_DRAG] pixels or more to the side, until the first that lines up; at most
     * [LOOK_LINES].
     */
    fun tornLines(luma: Deinterlacer.Plane): Int {
        val w = luma.width
        val h = luma.height
        if (h < LOOK_LINES + 2 || w < 4 * MAX_DRAG) return 0
        var torn = 0
        for (fromBottom in 0 until LOOK_LINES) {
            val y = h - 1 - fromBottom
            if (abs(drag(luma, y, y - 1)) < TORN_DRAG) break
            torn++
        }
        return torn
    }

    // How far line [y] is dragged against line [above]: the shift with the least mean difference.
    private fun drag(luma: Deinterlacer.Plane, y: Int, above: Int): Int {
        val w = luma.width
        var best = 0
        var bestSad = Long.MAX_VALUE
        for (d in -MAX_DRAG..MAX_DRAG) {
            var sad = 0L
            for (x in MAX_DRAG until w - MAX_DRAG) sad += abs(luma.pixels[y * w + x + d] - luma.pixels[above * w + x])
            if (sad < bestSad || (sad == bestSad && abs(d) < abs(best))) {
                bestSad = sad
                best = d
            }
        }
        return best
    }

    /**
     * The last [LOOK_LINES] + 2 lines of [rgba] ([width] × [height], rows top first, [bytesPerChannel] 1 or 2 with the
     * high byte taken) as luma (BT.601's weights, as FFmpeg's gray), for [tornLines].
     */
    fun bottomLuma(rgba: ByteArray, width: Int, height: Int, bytesPerChannel: Int = 1): Deinterlacer.Plane {
        val rows = minOf(height, LOOK_LINES + 2)
        val first = height - rows
        val high = bytesPerChannel - 1
        return Deinterlacer.Plane(width, rows, IntArray(width * rows) { i ->
            val at = ((first + i / width) * width + i % width) * 4 * bytesPerChannel + high
            val r = rgba[at].toInt() and 0xff
            val g = rgba[at + bytesPerChannel].toInt() and 0xff
            val b = rgba[at + 2 * bytesPerChannel].toInt() and 0xff
            (77 * r + 150 * g + 29 * b + 128) shr 8
        })
    }

    /**
     * Auto: the torn lines from [SAMPLES] looks taken as the video plays, their median, held from then on until
     * [restart] (another video).
     */
    class Auto {
        private val looks = ArrayList<Int>()

        /** The lines to cut at the bottom; 0 until decided. */
        var bottom: Int = 0
            private set

        val decided: Boolean get() = looks.size >= SAMPLES

        /** Takes one look ([tornLines]); returns whether the lines to cut changed. */
        fun add(torn: Int): Boolean {
            if (decided) return false
            looks += torn
            if (!decided) return false
            val before = bottom
            bottom = looks.sorted()[SAMPLES / 2]
            return bottom != before
        }

        fun restart() {
            looks.clear()
            bottom = 0
        }

        companion object {
            /** Looks the lines are decided from, one every [INTERVAL_MS]. */
            const val SAMPLES = 9
            const val INTERVAL_MS = 2000L
        }
    }
}
