package com.hawkynt.mediadroyd.player

import kotlin.math.log10

/**
 * A film's sound decoded once for everything that analyses it (#535): mono at [RATE] (what Whisper hears), read by one
 * stream the platform opens ([open]: from a time, chunk by chunk) and kept in seconds-long blocks — at most
 * [KEPT_SECONDS] of them, those furthest from what was asked last given up first. Whisper's windows ([samples]), the
 * speech found in it ([speech]: Sync, the voice-over's placement) and each line's tone all read from here; what is
 * decoded is never decoded again, and reading on continues the stream already open — a seek opens one where it lands.
 * The speech band's levels (SubtitleSync's detector) are worked out as each block fills and kept for the whole film,
 * so the speech found survives the blocks given up. Playback keeps its own decoding: this runs ahead of what plays.
 */
class FilmSound(private val open: (fromMs: Long) -> Stream?, private val durationMs: Long) {
    /** The platform's reader of the film's sound: mono floats at [RATE] from where it was opened, chunk after chunk. */
    interface Stream : AutoCloseable {
        /** The next chunk; null at the end or where nothing more can be read. */
        fun read(): FloatArray?
    }

    private val blocks = LinkedHashMap<Long, ShortArray>()
    private val filled = HashMap<Long, Int>()
    private var levels = FloatArray(levelsFor(durationMs)) { Float.NaN }

    private var stream: Stream? = null
    private var streamAt = -1L
    private var ended = Long.MAX_VALUE

    /**
     * The sound from [fromMs] to [toMs] (mono floats at [RATE]), decoded where it is not yet; shorter where the film
     * ends sooner, null where nothing can be read. Blocks while it decodes.
     */
    @Synchronized
    fun samples(fromMs: Long, toMs: Long): FloatArray? {
        val first = fromMs * RATE / 1000
        val last = minOf(toMs * RATE / 1000, ended)
        if (last <= first) return null
        decode(first, last)
        val end = minOf(last, ended)
        if (end <= first) return null
        return FloatArray((end - first).toInt()) { i ->
            val at = first + i
            (blocks[at / RATE]?.get((at % RATE).toInt()) ?: 0) / FULL
        }.also { keepNear(first / RATE) }
    }

    /** The speech found so far, SubtitleSync.RATE verdicts a second from the film's start; none where not decoded. */
    @Synchronized
    fun speech(): BooleanArray = SubtitleSync.verdicts(levels.copyOf())

    /**
     * Decodes what is not decoded yet across the whole film — for Sync, which needs all of its speech — telling
     * [progress] how far (0 … 1); the blocks given up as it goes, the levels kept. Blocks.
     */
    fun decodeAll(progress: (Float) -> Unit) {
        val total = if (durationMs > 0) durationMs else return
        var ms = 0L
        while (ms < total) {
            val to = minOf(ms + STEP_MS, total)
            val known = synchronized(this) {
                (ms / 10 until to / 10).all { it < levels.size && !levels[it.toInt()].isNaN() }
            }
            if (!known) samples(ms, to) ?: break
            ms = to
            progress(ms.toFloat() / total)
        }
    }

    /** Closes the stream open. */
    @Synchronized
    fun close() {
        stream?.close()
        stream = null
    }

    // Every block from [first] up to [last] (samples) full: read on where the open stream stands there, else open one
    // at the first block still missing.
    private fun decode(first: Long, last: Long) {
        var at = first
        while (at < last && at < ended) {
            val block = at / RATE
            val have = filled[block] ?: 0
            if (have >= RATE) {
                at = (block + 1) * RATE
                continue
            }
            // The open stream stands where this block needs its next sample: read on; else open one at the block.
            val next = block * RATE + have
            if (stream == null || streamAt != next) {
                stream?.close()
                stream = open(block * 1000)
                streamAt = block * RATE
                if (stream == null) {
                    ended = at
                    return
                }
            }
            val chunk = stream!!.read()
            if (chunk == null) {
                ended = streamAt
                stream?.close()
                stream = null
                return
            }
            put(streamAt, chunk)
            streamAt += chunk.size
        }
    }

    // [chunk]'s samples into their blocks from sample [from]; each block that fills gets its levels.
    private fun put(from: Long, chunk: FloatArray) {
        var i = 0
        while (i < chunk.size) {
            val at = from + i
            val block = at / RATE
            val offset = (at % RATE).toInt()
            val data = blocks.getOrPut(block) { ShortArray(RATE) }
            val n = minOf(chunk.size - i, RATE - offset)
            for (k in 0 until n) data[offset + k] = (chunk[i + k] * FULL).toInt().coerceIn(-32768, 32767).toShort()
            val now = maxOf(filled[block] ?: 0, offset + n)
            filled[block] = now
            if (now >= RATE) levelsOf(block, data)
            i += n
        }
    }

    // The speech band's energy every hundredth of a second of a full [block] (SubtitleSync's detector).
    private fun levelsOf(block: Long, data: ShortArray) {
        val highPass = Biquad.highPass(RATE, 300.0)
        val lowPass = Biquad.lowPass(RATE, 3400.0)
        val window = RATE / SubtitleSync.RATE
        val base = (block * SubtitleSync.RATE).toInt()
        if (base + SubtitleSync.RATE > levels.size) levels = levels.copyOf(base + SubtitleSync.RATE).also {
            for (k in levels.size until it.size) it[k] = Float.NaN
        }
        for (w in 0 until SubtitleSync.RATE) {
            var sum = 0.0
            for (k in w * window until (w + 1) * window) {
                val x = lowPass.next(highPass.next(data[k].toFloat()))
                sum += x * x
            }
            levels[base + w] = (10 * log10(sum / window + 1e-9)).toFloat()
        }
    }

    // At most KEPT_SECONDS blocks: those furthest from [near] given up first.
    private fun keepNear(near: Long) {
        if (blocks.size <= KEPT_SECONDS) return
        val far = blocks.keys.sortedByDescending { kotlin.math.abs(it - near) }.take(blocks.size - KEPT_SECONDS)
        for (block in far) {
            blocks.remove(block)
            filled.remove(block)
        }
    }

    private fun levelsFor(ms: Long) = if (ms > 0) (ms / 10 + SubtitleSync.RATE).toInt() else 0

    companion object {
        /** The rate the film's sound is kept at: Whisper's. */
        const val RATE = 16_000

        /** The most seconds of sound kept: some 19 MB. */
        const val KEPT_SECONDS = 600

        private const val STEP_MS = 60_000L
        private const val FULL = 32_768f
    }
}
