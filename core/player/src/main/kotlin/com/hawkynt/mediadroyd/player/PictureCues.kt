package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.nfo.TimeRange

/**
 * Intros and credits found by their pictures where their sound does not give them away (#492): a theme whose music
 * changes from episode to episode while its pictures do not, a silent title card, credits over music of their own.
 *
 * The intro: frames sampled every [OPENING_STEP_MS] from both openings, each reduced to [HASH_WIDTH] x [HASH_HEIGHT]
 * and hashed by the brightness steps between neighbouring cells (a difference hash: 32 bits, the same picture through
 * another encode a bit or two apart, two other pictures about sixteen), matched by [PrintMatcher] as the sound is.
 * The credits: frames sampled every [CLOSING_STEP_MS] from the closing at [LOOK_WIDTH] x [LOOK_HEIGHT], each taken as
 * black, as text on black, or as anything else ([look]); the credits begin where a stretch of black and text frames
 * begins that runs to the end. Pure: the platforms hand over the gray frames (PlayerPlatform.pictures).
 */
object PictureCues {
    /** Gray frames of a stretch of a video: [frames] of [width] x [height] bytes, one every [stepMs] from [startMs]. */
    class Frames(val width: Int, val height: Int, val startMs: Long, val stepMs: Long, val frames: List<ByteArray>)

    /** How often the opening is sampled for its intro. */
    const val OPENING_STEP_MS = 250L

    /** The size an opening's frames are hashed at: nine columns for the eight steps of each of four rows. */
    const val HASH_WIDTH = 9
    const val HASH_HEIGHT = 4

    /** How often the closing is sampled for its credits. */
    const val CLOSING_STEP_MS = 1000L

    /** The size a closing's frames are looked at: enough for a line of credits to show as strokes. */
    const val LOOK_WIDTH = 160
    const val LOOK_HEIGHT = 90

    /** What a closing frame shows. */
    enum class Look { BLACK, TEXT, OTHER }

    /**
     * How alike two openings' frames must be on average to be one intro: five of 32 bits. The same picture through
     * two encodes is a bit or two apart; two filmed pictures about sixteen, but plain scenes (an even background, a
     * block or two) only ten or twelve — the sound's threshold of ten would let those run on past an intro's end.
     */
    const val MATCH_THRESHOLD = 5.0

    // Below this a pixel is dark, from this on bright (full-range gray).
    private const val DARK = 48
    private const val BRIGHT = 110

    // A step this large between neighbours is a stroke's edge.
    private const val EDGE = 60

    // A step between neighbouring cells this large is clear; a frame with fewer clear steps than this says too little
    // to be told from others alike — black, a fade, a plain card, an even background with a block or two.
    private const val STEP = 4
    private const val CLEAR_STEPS = 8

    // How long the story may go on after the credits (a sting, a logo) and how many frames a credit may miss.
    private const val TAIL_MS = 30_000L
    private const val GAP_FRAMES = 3
    private const val MIN_TEXT_MS = 10_000L

    /**
     * The 32-bit hash of [frame] ([width] x [height], reduced to the hash size): bit `row * 8 + column` set where a
     * cell is brighter than the one to its right. A frame with fewer than eight clear steps (black, a fade, a plain
     * scene) hashes to a scramble of [salt] instead, so such frames match nothing; callers give each frame of each
     * video its own salt.
     */
    fun hash(frame: ByteArray, width: Int, height: Int, salt: Int): Int {
        val cells = IntArray(HASH_WIDTH * HASH_HEIGHT)
        for (cy in 0 until HASH_HEIGHT) {
            for (cx in 0 until HASH_WIDTH) {
                val x0 = cx * width / HASH_WIDTH
                val x1 = maxOf(x0 + 1, (cx + 1) * width / HASH_WIDTH)
                val y0 = cy * height / HASH_HEIGHT
                val y1 = maxOf(y0 + 1, (cy + 1) * height / HASH_HEIGHT)
                var sum = 0
                for (y in y0 until y1) for (x in x0 until x1) sum += frame[y * width + x].toInt() and 0xff
                cells[cy * HASH_WIDTH + cx] = sum / ((x1 - x0) * (y1 - y0))
            }
        }
        var hash = 0
        var clear = 0
        for (row in 0 until HASH_HEIGHT) {
            for (column in 0 until HASH_WIDTH - 1) {
                val at = row * HASH_WIDTH + column
                if (cells[at] > cells[at + 1]) hash = hash or (1 shl (row * (HASH_WIDTH - 1) + column))
                if (kotlin.math.abs(cells[at] - cells[at + 1]) >= STEP) clear++
            }
        }
        return if (clear < CLEAR_STEPS) scramble(salt) else hash
    }

    // A well-spread 32-bit value for [value]: MurmurHash3's finaliser.
    private fun scramble(value: Int): Int {
        var x = value * -0x61c88647 + 0x5bd1e995
        x = x xor (x ushr 16)
        x *= -0x7a143595
        x = x xor (x ushr 13)
        x *= -0x3d4d51cb
        return x xor (x ushr 16)
    }

    /**
     * What [frame] ([width] x [height]) shows: [Look.BLACK] where next to all of it is dark and nothing bright; [Look.TEXT]
     * where it is mostly dark with a little bright in thin strokes — many edges for its bright pixels, as letters have
     * and a moon or a lamp has not; else [Look.OTHER].
     */
    fun look(frame: ByteArray, width: Int, height: Int): Look {
        val total = width * height
        var dark = 0
        var bright = 0
        var edges = 0
        for (y in 0 until height) {
            var before = frame[y * width].toInt() and 0xff
            for (x in 0 until width) {
                val pixel = frame[y * width + x].toInt() and 0xff
                if (pixel < DARK) dark++
                if (pixel >= BRIGHT) bright++
                if (x > 0 && kotlin.math.abs(pixel - before) >= EDGE) edges++
                before = pixel
            }
        }
        return when {
            bright == 0 && dark >= total * 0.98 -> Look.BLACK
            dark >= total * 0.7 && bright >= total * 0.003 && bright <= total * 0.25 && edges >= bright * 0.3 ->
                Look.TEXT
            else -> Look.OTHER
        }
    }

    /**
     * Where the credits begin in [closing] (frames of the look size from a video's end): the first frame of the stretch
     * of black and text frames — a credit missed for up to three frames — that runs to within [TAIL_MS] of the end,
     * lasts IntroCredits.MIN_CREDITS_MS and holds ten seconds of text; null where there is none.
     */
    fun creditsStartMs(closing: Frames): Long? {
        val looks = closing.frames.map { look(it, closing.width, closing.height) }
        val tail = (TAIL_MS / closing.stepMs).toInt()
        val end = looks.indices.reversed().take(tail + 1).firstOrNull { looks[it] != Look.OTHER } ?: return null
        var start = end
        var gap = 0
        for (i in end - 1 downTo 0) {
            if (looks[i] != Look.OTHER) {
                start = i
                gap = 0
            } else if (++gap > GAP_FRAMES) {
                break
            }
        }
        val length = (end - start + 1) * closing.stepMs
        val text = (start..end).count { looks[it] == Look.TEXT } * closing.stepMs
        if (length < IntroCredits.MIN_CREDITS_MS || text < MIN_TEXT_MS) return null
        return closing.startMs + start * closing.stepMs
    }

    /**
     * The intro the openings [own] and [other] (frames of the hash size, both sampled every [OPENING_STEP_MS]) share in
     * pictures: its span in each video, IntroCredits.MARGIN_MS inside at both ends; null where they share none of an
     * intro's length.
     */
    fun intro(own: Frames, other: Frames): Pair<TimeRange, TimeRange>? {
        fun hashes(frames: Frames, side: Int) = IntArray(frames.frames.size) {
            hash(frames.frames[it], frames.width, frames.height, it * 2 + side)
        }
        val timing = IntroCredits.Timing(own.stepMs.toDouble(), 0, MATCH_THRESHOLD)
        val (a, b) = IntroCredits.intro(hashes(own, 0), hashes(other, 1), timing) ?: return null
        return a.shift(own.startMs) to b.shift(other.startMs)
    }

    /**
     * [rgba] ([width] x [height], four bytes a pixel) shrunk to [toWidth] x [toHeight] gray: each cell the mean
     * brightness (BT.601's weights, as FFmpeg's gray) of the pixels it covers — for platforms whose decoder hands over
     * full pictures.
     */
    fun gray(rgba: ByteArray, width: Int, height: Int, toWidth: Int, toHeight: Int): ByteArray {
        val out = ByteArray(toWidth * toHeight)
        for (cy in 0 until toHeight) {
            val y0 = cy * height / toHeight
            val y1 = maxOf(y0 + 1, (cy + 1) * height / toHeight)
            for (cx in 0 until toWidth) {
                val x0 = cx * width / toWidth
                val x1 = maxOf(x0 + 1, (cx + 1) * width / toWidth)
                var sum = 0L
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val at = (y * width + x) * 4
                        sum += (77 * (rgba[at].toInt() and 0xff) + 150 * (rgba[at + 1].toInt() and 0xff) +
                            29 * (rgba[at + 2].toInt() and 0xff) + 128) shr 8
                    }
                }
                out[cy * toWidth + cx] = (sum / ((x1 - x0) * (y1 - y0))).toInt().toByte()
            }
        }
        return out
    }

    private fun TimeRange.shift(byMs: Long) = TimeRange(startMs + byMs, endMs + byMs)
}
