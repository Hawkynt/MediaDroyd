package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Subtitles moved onto the speech by themselves (#450): a downloaded subtitle file often runs early or late, or drifts
 * (timed for another frame rate). The film's speech is found in its sound ([Speech]: 100 verdicts a second), the cues
 * are read from the file ([cues]) and laid over it, and the offset — and the frame-rate ratio — that lines them up
 * best is taken ([fit]): each signal +1 where it speaks and −1 where not, their cross-correlation by FFT within
 * [MAX_OFFSET_MS], for the cues as they are and stretched by each of [RATIOS].
 *
 * Sourcing: rung 1, ffsubsync's FFTAligner and MaxScoreAligner (MIT, Stephen Macke) converted — its sample rate,
 * maximum offset and frame-rate ratios copied. Its speech detection (WebRTC's VAD, or auditok, GPL) is not taken:
 * [Speech] is our own energy test in the speech band.
 */
object SubtitleSync {
    /** Verdicts a second: ffsubsync's SAMPLE_RATE. */
    const val RATE = 100

    /** How far the cues may be moved either way: ffsubsync's DEFAULT_MAX_OFFSET_SECONDS. */
    const val MAX_OFFSET_MS = 60_000L

    /** The frame-rate ratios tried besides none, each both ways: ffsubsync's FRAMERATE_RATIOS. */
    val RATIOS = listOf(24.0 / 23.976, 25.0 / 23.976, 25.0 / 24.0)

    /** The cues mapped onto the speech: a cue's time t (ms) belongs at t × [ratio] + [offsetMs]. */
    data class Fit(val offsetMs: Long, val ratio: Double) {
        /** Where a cue at [cueMs] belongs. */
        fun timeOf(cueMs: Long): Long = (cueMs * ratio).roundToLong() + offsetMs
    }

    /**
     * The best [Fit] of [cues] (times in ms) onto [speech] (verdicts at [RATE] from the start); null where either is
     * empty or has no speech in it.
     */
    fun fit(speech: BooleanArray, cues: List<LongRange>): Fit? {
        if (cues.isEmpty() || speech.none { it }) return null
        val length = speech.size
        var size = 1
        while (size < 2 * length) size = size shl 1
        val refRe = DoubleArray(size) { if (it < length) (if (speech[it]) 1.0 else -1.0) else 0.0 }
        val refIm = DoubleArray(size)
        fft(refRe, refIm, false)
        val maxOffset = (MAX_OFFSET_MS * RATE / 1000).toInt()
        var best: Fit? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (ratio in listOf(1.0) + RATIOS + RATIOS.map { 1 / it }) {
            val signal = BooleanArray(length)
            for (cue in cues) {
                val from = (cue.first * ratio * RATE / 1000).toInt().coerceAtLeast(0)
                val to = (cue.last * ratio * RATE / 1000).toInt().coerceAtMost(length - 1)
                for (i in from..to) signal[i] = true
            }
            if (signal.none { it }) continue
            val re = DoubleArray(size) { if (it < length) (if (signal[it]) 1.0 else -1.0) else 0.0 }
            val im = DoubleArray(size)
            fft(re, im, false)
            // ref × conj(sub): the correlation c[k] = Σ ref[t + k] · sub[t].
            for (i in 0 until size) {
                val r = refRe[i] * re[i] + refIm[i] * im[i]
                val m = refIm[i] * re[i] - refRe[i] * im[i]
                re[i] = r
                im[i] = m
            }
            fft(re, im, true)
            for (k in -maxOffset..maxOffset) {
                val score = re[if (k >= 0) k else size + k]
                if (score > bestScore) {
                    bestScore = score
                    best = Fit(k * 1000L / RATE, ratio)
                }
            }
        }
        return best
    }

    // An in-place radix-2 FFT of [re] + i[im] (their size a power of two); [inverse] scales by 1 / size.
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(angle)
            val wi = sin(angle)
            var start = 0
            while (start < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }

    private val TIMED = Regex(
        """(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{3})""",
    )
    private val DIALOGUE = Regex("""^Dialogue:\s*[^,]*,(\d+):(\d{2}):(\d{2})\.(\d{2}),(\d+):(\d{2}):(\d{2})\.(\d{2}),""")

    /** The cues' times in ms of a subtitle file's [text]: SubRip and WebVTT by their arrows, SSA/ASS by its Dialogue. */
    fun cues(text: String): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (line in text.lineSequence()) {
            TIMED.find(line)?.let { m ->
                val g = m.groupValues
                fun ms(h: String, mi: String, s: String, f: String) =
                    ((h.toLongOrNull() ?: 0) * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + f.toLong()
                out += ms(g[1], g[2], g[3], g[4])..ms(g[5], g[6], g[7], g[8])
                return@let
            } ?: DIALOGUE.find(line.trimStart())?.let { m ->
                val g = m.groupValues
                fun ms(h: String, mi: String, s: String, c: String) =
                    (h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + c.toLong() * 10
                out += ms(g[1], g[2], g[3], g[4])..ms(g[5], g[6], g[7], g[8])
            }
        }
        return out
    }

    /**
     * Speech in a stream of sound fed piece by piece ([feed]: interleaved 16-bit, [channels] at [sampleRate]): its
     * energy in the speech band (300 … 3400 Hz, the cookbook's second-order filters) every 1 / [RATE] s, speech where it
     * stands [ABOVE_DB] over the quiet tenth of the film; pauses under [GAP_MS] bridged and stretches under [MIN_MS]
     * dropped. Our own design, a hypothesis checked on films, not a published detector.
     */
    class Speech(private val sampleRate: Int, channels: Int) {
        private val channels = channels.coerceAtLeast(1)
        private val highPass = Biquad.highPass(sampleRate, 300.0)
        private val lowPass = Biquad.lowPass(sampleRate, 3400.0)
        private val window = (sampleRate / RATE).coerceAtLeast(1)
        private var sum = 0.0
        private var count = 0
        private var levels = FloatArray(1 shl 16)
        private var size = 0

        /** Takes [frames] frames of [samples] from [offset]. */
        fun feed(samples: ShortArray, offset: Int, frames: Int) {
            for (f in 0 until frames) {
                var mono = 0f
                for (c in 0 until channels) mono += samples[offset + f * channels + c]
                val x = lowPass.next(highPass.next(mono / channels))
                sum += x * x
                if (++count == window) {
                    if (size == levels.size) levels = levels.copyOf(size * 2)
                    levels[size++] = (10 * log10(sum / count + 1e-9)).toFloat()
                    sum = 0.0
                    count = 0
                }
            }
        }

        /** The verdicts so far, [RATE] a second. */
        fun result(): BooleanArray {
            if (size == 0) return BooleanArray(0)
            val sorted = levels.copyOf(size).sorted()
            val threshold = sorted[size / 10] + ABOVE_DB
            val speech = BooleanArray(size) { levels[it] > threshold }
            val gap = (GAP_MS * RATE / 1000).toInt()
            val min = (MIN_MS * RATE / 1000).toInt()
            var last = -1
            for (i in 0 until size) {
                if (!speech[i]) continue
                if (last >= 0 && i - last - 1 in 1..gap) for (k in last + 1 until i) speech[k] = true
                last = i
            }
            var start = 0
            while (start < size) {
                if (!speech[start]) {
                    start++
                    continue
                }
                var end = start
                while (end < size && speech[end]) end++
                if (end - start < min) for (k in start until end) speech[k] = false
                start = end
            }
            return speech
        }
    }

    // How far over the quiet tenth speech stands, the pause bridged and the shortest speech kept.
    private const val ABOVE_DB = 15f
    private const val GAP_MS = 250L
    private const val MIN_MS = 200L
}
