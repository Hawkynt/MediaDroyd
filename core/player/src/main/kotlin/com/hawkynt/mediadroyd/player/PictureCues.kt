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
    /**
     * Gray frames of a stretch of a video: [frames] of [width] x [height] bytes, one every [stepMs] from [startMs]; for
     * a closing decoded once for both looks (#535, [closing]) also the video's [durationMs] and the text reader's
     * frames ([textAt]).
     */
    class Frames(
        val width: Int,
        val height: Int,
        val startMs: Long,
        val stepMs: Long,
        val frames: List<ByteArray>,
        val durationMs: Long = -1,
        private val text: Map<Long, ByteArray> = emptyMap(),
    ) {
        /** The frame at [atMs] at CreditText's size, gray, where the text reader's frames are kept there; else null. */
        fun textAt(atMs: Long): ByteArray? = text[atMs]?.let(::inflate)

        /** Whether any text reader's frames are kept. */
        val hasText: Boolean get() = text.isNotEmpty()

        /** These frames without the text reader's: some megabytes given back once they are read. */
        fun withoutText() = Frames(width, height, startMs, stepMs, frames, durationMs)
    }

    /**
     * A closing ([startMs] on, of a video [durationMs] long) decoded once for both of its looks (#535): [frames] gray
     * at CreditText's size, one every [CLOSING_STEP_MS], shrunk to the look size here; where [keepText] (the platform
     * can read text), the text reader's own frames — every CreditText.STEP_MS — kept as well, packed.
     */
    fun closing(startMs: Long, durationMs: Long, frames: List<ByteArray>, keepText: Boolean): Frames {
        val w = CreditText.WIDTH
        val h = CreditText.HEIGHT
        val look = frames.map { shrink(it, w, h, LOOK_WIDTH, LOOK_HEIGHT) }
        val every = (CreditText.STEP_MS / CLOSING_STEP_MS).toInt()
        val text = if (!keepText) {
            emptyMap()
        } else {
            frames.withIndex().filter { it.index % every == 0 }
                .associate { (i, frame) -> startMs + i * CLOSING_STEP_MS to deflate(frame) }
        }
        return Frames(LOOK_WIDTH, LOOK_HEIGHT, startMs, CLOSING_STEP_MS, look, durationMs, text)
    }

    /** [gray] ([width] x [height]) shrunk to [toWidth] x [toHeight]: each cell the mean of the pixels it covers. */
    fun shrink(gray: ByteArray, width: Int, height: Int, toWidth: Int, toHeight: Int): ByteArray {
        val out = ByteArray(toWidth * toHeight)
        for (cy in 0 until toHeight) {
            val y0 = cy * height / toHeight
            val y1 = maxOf(y0 + 1, (cy + 1) * height / toHeight)
            for (cx in 0 until toWidth) {
                val x0 = cx * width / toWidth
                val x1 = maxOf(x0 + 1, (cx + 1) * width / toWidth)
                var sum = 0L
                for (y in y0 until y1) for (x in x0 until x1) sum += gray[y * width + x].toInt() and 0xff
                out[cy * toWidth + cx] = (sum / ((x1 - x0) * (y1 - y0))).toInt().toByte()
            }
        }
        return out
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(bytes.size / 4)
            val buffer = ByteArray(1 shl 16)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(packed: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        return try {
            inflater.setInput(packed)
            val out = ByteArray(CreditText.WIDTH * CreditText.HEIGHT)
            var n = 0
            while (n < out.size && !inflater.finished()) n += inflater.inflate(out, n, out.size - n)
            out
        } finally {
            inflater.end()
        }
    }

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
    // The most of the story after credits for them to be the end credits: a scene hidden after them, not the film.
    private const val HIDDEN_MAX_MS = 180_000L
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
     * What [frame] ([width] x [height]) shows: [Look.BLACK] where next to all of it is dark and nothing bright;
     * [Look.TEXT] where it is mostly dark with a little bright in thin strokes — many edges for its bright pixels, as
     * letters have and a moon or a lamp has not; else [Look.OTHER].
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
     * Where the credits begin in [closing] (frames of the look size from a video's end): the first of its credits'
     * stretches ([creditsSpans]); null where there is none.
     */
    fun creditsStartMs(closing: Frames): Long? = creditsSpans(closing).firstOrNull()?.startMs

    /**
     * Where the credits run in [closing] (frames of the look size from a video's end), each stretch from its first
     * frame to the end of its last (#513): a run of black and text frames — a credit missed for up to three frames —
     * that lasts IntroCredits.MIN_CREDITS_MS and holds ten seconds of text, with at most [HIDDEN_MAX_MS] of other
     * frames after it to the end: so credits a scene is hidden after keep their end, credits split around one are two
     * stretches, and text in the story with minutes of story after it is none.
     */
    fun creditsSpans(closing: Frames): List<TimeRange> {
        val looks = closing.frames.map { look(it, closing.width, closing.height) }
        val step = closing.stepMs
        val runs = ArrayList<IntRange>()
        var first = -1
        var last = -1
        var gap = 0
        for (i in looks.indices) {
            if (looks[i] != Look.OTHER) {
                if (first < 0) first = i
                last = i
                gap = 0
            } else if (first >= 0 && ++gap > GAP_FRAMES) {
                runs += first..last
                first = -1
                gap = 0
            }
        }
        if (first >= 0) runs += first..last
        return runs
            .filter { run ->
                run.count() * step >= IntroCredits.MIN_CREDITS_MS &&
                    run.count { looks[it] == Look.TEXT } * step >= MIN_TEXT_MS &&
                    (run.last + 1 until looks.size).count { looks[it] == Look.OTHER } * step <= HIDDEN_MAX_MS
            }
            .map { run -> TimeRange(closing.startMs + run.first * step, closing.startMs + (run.last + 1) * step) }
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
