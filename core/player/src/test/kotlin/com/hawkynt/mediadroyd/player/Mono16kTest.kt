package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mono16kTest {
    private fun rms(x: FloatArray, from: Int = x.size / 4) =
        sqrt((from until x.size).sumOf { (x[it] * x[it]).toDouble() } / (x.size - from))

    // [seconds] of a [hz] tone at [rate] in every one of [channels], as 16-bit interleaved, in pieces of [piece].
    private fun feed(mono: Mono16k, hz: Double, rate: Int, channels: Int, seconds: Double): FloatArray {
        val piece = 4096
        val frames = (rate * seconds).toInt()
        val all = ShortArray(frames * channels) { i ->
            (8000 * sin(2 * PI * hz * (i / channels) / rate)).toInt().toShort()
        }
        var out = FloatArray(0)
        for (at in 0 until all.size step piece * channels) {
            out += mono.feed(all, at, minOf(piece, frames - at / channels), channels, rate)
        }
        return out
    }

    @Test
    fun `48 and 44_1 kHz come out at 16 kHz, a voice's tone kept at its level`() {
        for (rate in listOf(48_000, 44_100, 32_000, 16_000)) {
            val out = feed(Mono16k(), 1000.0, rate, 2, 2.0)
            assertEquals("length at $rate", 32_000.0, out.size.toDouble(), 3.0)
            assertEquals("level at $rate", 8000 / 32768.0 / sqrt(2.0), rms(out), 0.01)
        }
    }

    @Test
    fun `what lies above what Whisper hears is held back rather than folded down into it`() {
        val out = feed(Mono16k(), 11_000.0, 48_000, 2, 1.0)
        assertTrue("aliased: ${rms(out)}", rms(out) < 0.02)
    }

    @Test
    fun `channels are mixed as FFmpeg mixes to mono by default, the bass channel left out`() {
        assertEquals(listOf(1f), Mono16k.weights(1).toList())
        assertEquals(listOf(0.5f, 0.5f), Mono16k.weights(2).toList())
        val six = Mono16k.weights(6)
        // FL FR FC LFE BL BR: front sides -3 dB, centre whole, LFE none, surrounds -6 dB, summed to one.
        assertEquals(0f, six[3])
        assertEquals(six[2] * 0.7071f, six[0], 1e-3f)
        assertEquals(six[2] * 0.5f, six[4], 1e-3f)
        assertEquals(1f, six.sum(), 1e-4f)
    }
}
