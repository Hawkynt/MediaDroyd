package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Bauer's crossfeed against libbs2b itself, run by FFmpeg's bs2b filter (resources/headphones/make-expected.sh). */
class CrossfeedTest {

    private fun samples(name: String): ShortArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/headphones/$name")).readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    @Test
    fun `stereo crossfed as libbs2b's default level crossfeeds it, sample for sample`() {
        val sound = samples("stereo.s16")
        val expected = samples("stereo-bs2b.s16")
        val crossfeed = Crossfeed(44100)
        // In buffers of uneven sizes, as the sound arrives.
        var at = 0
        var size = 1
        while (at < sound.size / 2) {
            val frames = minOf(size, sound.size / 2 - at)
            crossfeed.process(sound, at * 2, frames, 2)
            at += frames
            size = size * 3 % 1021 + 1
        }
        var worst = 0
        for (i in sound.indices) worst = maxOf(worst, abs(sound[i] - expected[i]))
        assertEquals(0, worst)
    }

    @Test
    fun `a sound hard left reaches the right ear, lower and duller`() {
        val crossfeed = Crossfeed(48000)
        val sound = ShortArray(48000 * 2) { i ->
            if (i % 2 == 0) (if (i / 2 % 48 < 24) 8000 else -8000).toShort() else 0
        }
        crossfeed.process(sound, 0, 48000, 2)
        val left = (24000 until 48000).maxOf { abs(sound[it * 2].toInt()) }
        val right = (24000 until 48000).maxOf { abs(sound[it * 2 + 1].toInt()) }
        assertTrue("$left / $right", right in 1 until left / 2)
    }

    @Test
    fun `anything but stereo is left as it is`() {
        val sound = ShortArray(600) { (it * 37).toShort() }
        val before = sound.copyOf()
        Crossfeed(48000).process(sound, 0, 100, 6)
        assertTrue(sound.contentEquals(before))
    }
}
