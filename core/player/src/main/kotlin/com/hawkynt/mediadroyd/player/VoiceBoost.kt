package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Clearer voices (#452): films mixed for cinemas bury the dialogue on TV speakers and phones, and this raises it.
 * Dialogue sits in the centre — a film's centre channel, which a downmix puts into left and right alike — so in stereo
 * (and mono) the mid, (L + R) / 2, is band-passed to the speech band ([LOW_HZ]..[HIGH_HZ]) and added back to both
 * channels with the boost: the voices come up, the music and effects spread to the sides and the bass and hiss stay
 * where they were. Where a file's channels arrive unmixed (5.1, 7.1: front left, front right, centre, …), the centre
 * channel itself is raised. Near full scale a soft knee takes over instead of clipping.
 *
 * The band-pass is a second-order high-pass and low-pass from Robert Bristow-Johnson's Audio EQ Cookbook; the idea of
 * raising the centre against the sides is the common mid/side one, the issue's spec.
 */
class VoiceBoost(sampleRate: Int, channels: Int) {
    private val channels = channels.coerceAtLeast(1)
    private val highPass = Biquad.highPass(sampleRate, LOW_HZ)
    private val lowPass = Biquad.lowPass(sampleRate, HIGH_HZ)

    /** Raises the voices in [frames] frames of interleaved 16-bit samples from [offset] by [boostDb]; 0 leaves them. */
    fun process(samples: ShortArray, offset: Int, frames: Int, boostDb: Float) {
        if (frames <= 0) return
        val factor = LoudnessCurve.factorFor(boostDb.coerceAtLeast(0f))
        if (factor == 1f) {
            // Kept running on the mid, so a boost switched on later starts from settled filters.
            if (channels <= 2) for (f in 0 until frames) band(mid(samples, offset + f * channels))
            return
        }
        if (channels == 3 || channels >= 5) {
            for (f in 0 until frames) {
                val at = offset + f * channels + CENTRE
                samples[at] = knee(samples[at] * factor)
            }
            return
        }
        val added = factor - 1f
        for (f in 0 until frames) {
            val at = offset + f * channels
            val voice = band(mid(samples, at)) * added
            for (c in 0 until minOf(channels, 2)) samples[at + c] = knee(samples[at + c] + voice)
        }
    }

    private fun mid(samples: ShortArray, at: Int): Float =
        if (channels == 1) samples[at].toFloat() else (samples[at] + samples[at + 1]) / 2f

    private fun band(x: Float): Float = lowPass.next(highPass.next(x))

    // [x] as a sample: as it is up to [KNEE], then bending softly towards full scale.
    private fun knee(x: Float): Short {
        val magnitude = abs(x)
        val room = FULL - KNEE
        val shaped = if (magnitude <= KNEE) magnitude else KNEE + room * tanh((magnitude - KNEE) / room)
        return (shaped * sign(x)).toInt().toShort()
    }

    // One second-order section, transposed direct form II.
    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f

        fun next(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private const val Q = 0.7071f

            fun highPass(rate: Int, hz: Double): Biquad {
                val w = 2 * PI * hz / rate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad(
                    ((1 + c) / 2 / a0).toFloat(), (-(1 + c) / a0).toFloat(), ((1 + c) / 2 / a0).toFloat(),
                    (-2 * c / a0).toFloat(), ((1 - alpha) / a0).toFloat(),
                )
            }

            fun lowPass(rate: Int, hz: Double): Biquad {
                val w = 2 * PI * hz / rate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad(
                    ((1 - c) / 2 / a0).toFloat(), ((1 - c) / a0).toFloat(), ((1 - c) / 2 / a0).toFloat(),
                    (-2 * c / a0).toFloat(), ((1 - alpha) / a0).toFloat(),
                )
            }
        }
    }

    companion object {
        /** The speech band raised: its fundamentals' floor to the consonants' clarity. */
        const val LOW_HZ = 200.0
        const val HIGH_HZ = 4000.0

        /** The most the voices can be raised by, in dB. */
        const val MAX_DB = 12f

        // The centre channel in the WAVE order 5.1 and 7.1 arrive in: front left, front right, centre, …
        private const val CENTRE = 2
        private const val FULL = 32767f
        private const val KNEE = 24000f
    }
}
