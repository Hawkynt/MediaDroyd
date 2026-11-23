package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Kaldi's filterbank as kaldi-native-fbank computes it for sherpa-onnx (resources/speakers/make-expected.py). */
class FbankTest {

    private fun floats(name: String): FloatArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/speakers/$name")).readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    @Test
    fun `the filterbank agrees with kaldi-native-fbank frame for frame`() {
        val frames = Fbank.compute(floats("voice.f32"))
        val expected = floats("voice-fbank.f32")
        assertEquals(expected.size / Fbank.BINS, frames.size)
        var worst = 0f
        for (f in frames.indices) for (b in 0 until Fbank.BINS) {
            worst = maxOf(worst, abs(frames[f][b] - expected[f * Fbank.BINS + b]))
        }
        assertTrue("off by $worst", worst < 1e-3f)
    }

    @Test
    fun `the mean over the frames is taken out of every bin`() {
        val frames = Fbank.compute(floats("voice.f32"))
        Fbank.subtractMean(frames)
        for (b in 0 until Fbank.BINS) assertEquals(0.0, frames.sumOf { it[b].toDouble() } / frames.size, 1e-4)
    }

    @Test
    fun `too short a sound has no frames`() {
        assertEquals(0, Fbank.compute(FloatArray(0)).size)
        assertEquals(1, Fbank.compute(FloatArray(100)).size)
    }
}
