package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The speaker network run as sherpa-onnx runs it, against its embedding made by onnxruntime from kaldi-native-fbank's
 * features (resources/speakers/make-embedding.py). Needs the network's file (SpeakerEmbedder.DOWNLOAD), named by
 * MEDIADROYD_SPEAKER_MODEL; without it, as without a display for the desktop stage's tests, it is not run.
 */
class SpeakerEmbedderTest {

    private fun floats(name: String): FloatArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/speakers/$name")).readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private val model = System.getenv("MEDIADROYD_SPEAKER_MODEL")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun `the embedding agrees with sherpa-onnx's way of computing it`() {
        assumeTrue("MEDIADROYD_SPEAKER_MODEL names no file", model != null)
        SpeakerEmbedder(model!!).use { embedder ->
            val mine = embedder.raw(floats("voice.f32"))!!
            val expected = floats("voice-embedding.f32")
            assertEquals(expected.size, mine.size)
            val worst = mine.indices.maxOf { abs(mine[it] - expected[it]) }
            assertTrue("off by $worst", worst < 1e-2f)
            assertNull(embedder.raw(FloatArray(8000)))
        }
    }

    @Test
    fun `an embedding is scaled to unit length for comparing`() {
        val v = SpeakerEmbedder.unit(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
    }
}
