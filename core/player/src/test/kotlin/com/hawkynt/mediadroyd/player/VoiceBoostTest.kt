package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceBoostTest {
    private val rate = 48_000

    // [seconds] of interleaved stereo: [mid] Hz in both channels alike (what a voice in the centre is), [side] Hz in
    // the two channels against each other (what the room and the effects at the sides are).
    private fun stereo(mid: Double?, side: Double?, seconds: Double = 1.0): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val m = mid?.let { 6000 * sin(2 * PI * it * t) } ?: 0.0
            val s = side?.let { 6000 * sin(2 * PI * it * t) } ?: 0.0
            (if (i % 2 == 0) m + s else m - s).toInt().toShort()
        }
    }

    // The RMS of the mid (L+R)/2 and of the side (L-R)/2 over the second half (the filters settled).
    private fun midSide(samples: ShortArray): Pair<Double, Double> {
        val frames = samples.size / 2
        var m = 0.0
        var s = 0.0
        for (f in frames / 2 until frames) {
            val l = samples[f * 2].toDouble()
            val r = samples[f * 2 + 1].toDouble()
            m += ((l + r) / 2) * ((l + r) / 2)
            s += ((l - r) / 2) * ((l - r) / 2)
        }
        val n = frames - frames / 2
        return sqrt(m / n) to sqrt(s / n)
    }

    private fun boosted(samples: ShortArray, db: Float, channels: Int = 2) = samples.copyOf().also {
        VoiceBoost(rate, channels).process(it, 0, it.size / channels, db)
    }

    @Test
    fun `a voice in the centre comes up by the boost, the sides stay as they were`() {
        val input = stereo(mid = 1000.0, side = 1000.0)
        val (midIn, sideIn) = midSide(input)
        val (midOut, sideOut) = midSide(boosted(input, 6f))
        assertEquals(2.0, midOut / midIn, 0.15)
        assertEquals(1.0, sideOut / sideIn, 0.02)
    }

    @Test
    fun `bass and hiss in the centre are left alone, only the speech band is raised`() {
        for (hz in listOf(50.0, 12_000.0)) {
            val input = stereo(mid = hz, side = null)
            val ratio = midSide(boosted(input, 6f)).first / midSide(input).first
            assertEquals("at $hz Hz", 1.0, ratio, 0.2)
        }
    }

    @Test
    fun `no boost leaves the sound as it is`() {
        val input = stereo(mid = 1000.0, side = 300.0)
        assertArrayEquals(input, boosted(input, 0f))
    }

    @Test
    fun `in 5 point 1 the centre channel is raised, the others untouched`() {
        // FL FR FC LFE BL BR, each a tone of its own.
        val frames = rate / 2
        val input = ShortArray(frames * 6) { i ->
            (3000 * sin(2 * PI * (300 + 100 * (i % 6)) * (i / 6) / rate)).toInt().toShort()
        }
        val output = boosted(input, 6f, channels = 6)
        for (c in 0 until 6) {
            var inSum = 0.0
            var outSum = 0.0
            for (f in 0 until frames) {
                inSum += abs(input[f * 6 + c].toDouble())
                outSum += abs(output[f * 6 + c].toDouble())
            }
            assertEquals("channel $c", if (c == 2) 2.0 else 1.0, outSum / inSum, 0.02)
        }
    }
}
