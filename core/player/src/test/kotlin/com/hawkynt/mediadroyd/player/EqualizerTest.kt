package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerTest {
    private val rate = 48_000

    // [seconds] of [hz] in every one of [channels] channels, interleaved, at [amplitude].
    private fun tone(hz: Double, channels: Int = 2, amplitude: Double = 4000.0, at: Int = rate): ShortArray =
        ShortArray(at * channels) { i -> (amplitude * sin(2 * PI * hz * (i / channels) / rate)).toInt().toShort() }

    // The RMS of [channel] over the second half (the filters settled).
    private fun rms(samples: ShortArray, channels: Int = 2, channel: Int = 0): Double {
        val frames = samples.size / channels
        var sum = 0.0
        for (f in frames / 2 until frames) sum += samples[f * channels + channel].toDouble().let { it * it }
        return sqrt(sum / (frames - frames / 2))
    }

    private fun equalized(input: ShortArray, preampDb: Float, bands: List<Float>, channels: Int = 2, r: Int = rate) =
        input.copyOf().also { Equalizer(r, channels).process(it, 0, it.size / channels, preampDb, bands) }

    private fun band(index: Int, db: Float) = List(Equalizer.HZ.size) { if (it == index) db else 0f }

    @Test
    fun `flat leaves the sound exactly as it was`() {
        val input = tone(1000.0)
        assertArrayEquals(input, equalized(input, 0f, List(10) { 0f }))
        assertArrayEquals(input, equalized(input, 0f, emptyList()))
    }

    @Test
    fun `a band raises its own frequency by its gain and leaves the far ones`() {
        val at1k = Equalizer.HZ.indexOf(1000.0)
        val input = tone(1000.0)
        assertEquals(2.0, rms(equalized(input, 0f, band(at1k, 6f))) / rms(input), 0.05)
        assertEquals(0.5, rms(equalized(input, 0f, band(at1k, -6f))) / rms(input), 0.03)
        val low = tone(60.0)
        assertEquals(1.0, rms(equalized(low, 0f, band(at1k, 6f))) / rms(low), 0.03)
    }

    @Test
    fun `the preamp lowers or raises everything`() {
        val input = tone(440.0)
        assertEquals(0.5, rms(equalized(input, -6f, emptyList())) / rms(input), 0.01)
    }

    @Test
    fun `every channel is filtered on its own`() {
        val input = ShortArray(rate * 2) { i ->
            val t = (i / 2).toDouble() / rate
            (4000 * sin(2 * PI * (if (i % 2 == 0) 1000.0 else 60.0) * t)).toInt().toShort()
        }
        val out = equalized(input, 0f, band(Equalizer.HZ.indexOf(1000.0), 6f))
        assertEquals(2.0, rms(out, channel = 0) / rms(input, channel = 0), 0.05)
        assertEquals(1.0, rms(out, channel = 1) / rms(input, channel = 1), 0.03)
    }

    @Test
    fun `loud sound raised bends near full scale instead of wrapping round`() {
        val input = tone(1000.0, amplitude = 20_000.0)
        val out = equalized(input, 0f, band(Equalizer.HZ.indexOf(1000.0), 20f))
        for (i in input.indices) {
            if (abs(input[i].toInt()) > 2000) assertTrue("sign flipped at $i", input[i] * out[i] > 0)
        }
    }

    @Test
    fun `a band at or above half the sample rate is left out, the rest still works`() {
        val r = 22_050
        val input = tone(1000.0, at = r)
        val out = equalized(input, 0f, List(10) { if (Equalizer.HZ[it] >= 12_000) 20f else 0f }, r = r)
        assertEquals(1.0, rms(out) / rms(input), 0.05)
    }

    @Test
    fun `the presets are VLC's own values, its preamp taken against its 12 dB`() {
        assertEquals(listOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0), Equalizer.HZ)
        assertEquals(18, EqualizerPreset.entries.size)
        assertEquals(0f, EqualizerPreset.FLAT.preampDb)
        assertEquals(List(10) { 0f }, EqualizerPreset.FLAT.bandsDb)
        assertEquals(-7f, EqualizerPreset.ROCK.preampDb)
        assertEquals(listOf(8.0f, 4.8f, -5.6f, -8.0f, -3.2f, 4.0f, 8.8f, 11.2f, 11.2f, 11.2f), EqualizerPreset.ROCK.bandsDb)
        assertEquals("Full bass and treble", EqualizerPreset.FULL_BASS_TREBLE.label)
        assertEquals(EqualizerPreset.TECHNO, EqualizerPreset.matching(-7f, EqualizerPreset.TECHNO.bandsDb))
        assertEquals(null, EqualizerPreset.matching(0f, band(3, 2f)))
    }
}
