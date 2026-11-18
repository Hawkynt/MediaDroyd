package com.hawkynt.mediadroyd.player

/**
 * A film's sound brought to what the analysis hears (#535, FilmSound.RATE, one channel), piece after piece: its
 * channels mixed as FFmpeg's resampler mixes to mono by default ([weights]), what lies above 7 kHz held back (four
 * sections, so a cymbal does not fold down into the speech), then sampled at FilmSound.RATE between its own samples —
 * for sound decoded where FFmpeg's resampler is not at hand (Media3's FFmpeg decoder on Android).
 */
class Mono16k {
    private var rate = 0
    private var filters = emptyList<Biquad>()

    // Where the next output sample lies, in input samples from the first one kept, and that kept one.
    private var phase = 0.0
    private var held = 0f

    /** [frames] frames of [samples] (16-bit, [channels] interleaved, at [inputRate]) from [offset], as mono floats. */
    fun feed(samples: ShortArray, offset: Int, frames: Int, channels: Int, inputRate: Int): FloatArray {
        val weights = weights(channels)
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += samples[offset + f * channels + c] * weights[c]
            sum / FULL
        }
        return resample(mono, inputRate)
    }

    private fun resample(mono: FloatArray, inputRate: Int): FloatArray {
        if (inputRate != rate) {
            rate = inputRate
            filters = if (inputRate > FilmSound.RATE) {
                List(SECTIONS) { Biquad.lowPass(inputRate, CUTOFF_HZ) }
            } else {
                emptyList()
            }
            phase = 0.0
        }
        for (i in mono.indices) mono[i] = filters.fold(mono[i]) { x, f -> f.next(x) }
        val step = inputRate.toDouble() / FilmSound.RATE
        val out = ArrayList<Float>((mono.size / step).toInt() + 2)
        // Sample k of this piece is index k; index -1 is the one held from the piece before.
        fun at(i: Int) = if (i < 0) held else mono[i]
        while (phase < mono.size - 1) {
            val i = kotlin.math.floor(phase).toInt()
            val t = (phase - i).toFloat()
            out += at(i) * (1 - t) + at(i + 1) * t
            phase += step
        }
        if (mono.isNotEmpty()) {
            held = mono.last()
            phase -= mono.size
        }
        return out.toFloatArray()
    }

    companion object {
        private const val FULL = 32_768f
        private const val CUTOFF_HZ = 7_000.0
        private const val SECTIONS = 4

        /**
         * Each of [channels]' share of the mono mix, in WAVE's order (FL FR FC LFE BL BR SL SR): FFmpeg's resampler's
         * defaults (center_mix_level and surround_mix_level -3 dB, LFE left out, normalised to one sum for integer
         * output) — so its sound matches FFmpeg's own mix to mono (FfmpegDemuxer.sound).
         */
        fun weights(channels: Int): FloatArray {
            val raw = when (channels) {
                1 -> floatArrayOf(1f)
                2 -> floatArrayOf(SIDE, SIDE)
                else -> FloatArray(channels) { c ->
                    when (c) {
                        0, 1 -> SIDE
                        2 -> 1f
                        3 -> 0f
                        else -> SIDE * SIDE
                    }
                }
            }
            val sum = raw.sum()
            return FloatArray(channels) { raw[it] / sum }
        }

        private const val SIDE = 0.70710677f
    }
}
