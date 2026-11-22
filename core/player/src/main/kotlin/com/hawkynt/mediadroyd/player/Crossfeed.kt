package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Crossfeed for stereo on headphones (#453): on loudspeakers each ear hears both speakers, the far one later, lower and
 * duller through the head; on headphones each ear hears one channel alone, and a stereo mix sounds hard left and
 * right, inside the head. Bauer's stereophonic-to-binaural filter (Benjamin Bauer, "Stereophonic Earphones and
 * Binaural Loudspeakers", JAES 9(2), 1961) puts the far speaker back: each channel low-passed and fed into the other,
 * the direct channel's highs raised to keep the tone, the two together levelled.
 *
 * Converted from libbs2b 3.1.0 (Boris Mikhaylov, MIT, see `THIRD-PARTY-NOTICE.bs2b.txt`): `init()` and
 * `cross_feed_d()` of `src/bs2b.c` and `bs2b_cross_feed_s16()`'s clipping and truncation, at its default level — a
 * 700 Hz cut, 4.5 dB of feed. Held to libbs2b itself, sample for sample, by `CrossfeedTest`.
 */
class Crossfeed(sampleRate: Int, cutHz: Int = DEFAULT_CUT_HZ, feedDb: Double = DEFAULT_FEED_DB) {
    private val a0Lo: Double
    private val b1Lo: Double
    private val a0Hi: Double
    private val a1Hi: Double
    private val b1Hi: Double
    private val gain: Double

    init {
        val rate = if (sampleRate in MIN_RATE..MAX_RATE) sampleRate else DEFAULT_RATE
        val gbLo = feedDb * -5.0 / 6.0 - 3.0
        val gbHi = feedDb / 6.0 - 3.0
        val gLo = 10.0.pow(gbLo / 20.0)
        val gHi = 1.0 - 10.0.pow(gbHi / 20.0)
        val cutHi = cutHz * 2.0.pow((gbLo - 20.0 * log10(gHi)) / 12.0)
        var x = exp(-2.0 * PI * cutHz / rate)
        b1Lo = x
        a0Lo = gLo * (1.0 - x)
        x = exp(-2.0 * PI * cutHi / rate)
        b1Hi = x
        a0Hi = 1.0 - gHi * (1.0 - x)
        a1Hi = -x
        gain = 1.0 / (1.0 - gHi + gLo)
    }

    // The filters' state per channel: the low-pass's and high-boost's last outputs, the last input.
    private val lo = DoubleArray(2)
    private val hi = DoubleArray(2)
    private val asIs = DoubleArray(2)

    /**
     * Crossfeeds [frames] frames of interleaved 16-bit samples from [offset], in place, where they have [channels] 2;
     * any other layout is left as it is.
     */
    fun process(samples: ShortArray, offset: Int, frames: Int, channels: Int) {
        if (channels != 2) return
        for (f in 0 until frames) {
            val at = offset + f * 2
            val l = samples[at].toDouble()
            val r = samples[at + 1].toDouble()
            lo[0] = a0Lo * l + b1Lo * lo[0]
            lo[1] = a0Lo * r + b1Lo * lo[1]
            hi[0] = a0Hi * l + a1Hi * asIs[0] + b1Hi * hi[0]
            hi[1] = a0Hi * r + a1Hi * asIs[1] + b1Hi * hi[1]
            asIs[0] = l
            asIs[1] = r
            samples[at] = clip((hi[0] + lo[1]) * gain)
            samples[at + 1] = clip((hi[1] + lo[0]) * gain)
        }
    }

    /** Forgets the sound so far: the next buffer starts from silence. */
    fun clear() {
        lo.fill(0.0)
        hi.fill(0.0)
        asIs.fill(0.0)
    }

    // libbs2b's s16 output: clipped to the range, then truncated towards zero as C's cast does.
    private fun clip(x: Double): Short = x.coerceIn(-32768.0, 32767.0).toInt().toShort()

    companion object {
        /** libbs2b's default level (BS2B_DEFAULT_CLEVEL): 700 Hz, 45 tenths of a dB. */
        const val DEFAULT_CUT_HZ = 700
        const val DEFAULT_FEED_DB = 4.5

        // libbs2b's sample-rate range and the rate it falls back to outside it.
        private const val MIN_RATE = 2000
        private const val MAX_RATE = 384000
        private const val DEFAULT_RATE = 44100
    }
}
