package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 5.1 and 7.1 placed around the head as FFmpeg's headphone filter places them over the same responses
 * (resources/headphones/make-expected.sh).
 */
class VirtualSurroundTest {

    private fun samples(name: String): ShortArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/headphones/$name")).readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun worstAgainst(input: String, expected: String, channels: Int): Int {
        val sound = samples(input)
        val oracle = samples(expected)
        val surround = VirtualSurround(48000, channels)
        val frames = sound.size / channels
        var at = 0
        var size = 7
        while (at < frames) {
            val n = minOf(size, frames - at)
            surround.process(sound, at * channels, n)
            at += n
            size = size * 5 % 2003 + 1
        }
        var worst = 0
        for (f in 0 until frames) {
            for (e in 0..1) worst = maxOf(worst, abs(sound[f * channels + e] - oracle[f * 2 + e]))
            for (c in 2 until channels) assertEquals(0, sound[f * channels + c].toInt())
        }
        return worst
    }

    @Test
    fun `5_1 placed as FFmpeg's headphone filter places it`() {
        val worst = worstAgainst("surround51.s16", "surround51-headphone.s16", 6)
        assertTrue("off by $worst", worst <= 1)
    }

    @Test
    fun `7_1 placed as FFmpeg's headphone filter places it`() {
        val worst = worstAgainst("surround71.s16", "surround71-headphone.s16", 8)
        assertTrue("off by $worst", worst <= 1)
    }

    @Test
    fun `a sound from the left surround is louder in the left ear`() {
        val surround = VirtualSurround(44100, 6)
        val sound = ShortArray(4410 * 6) { i ->
            if (i % 6 == 4) (if (i / 6 % 40 < 20) 6000 else -6000).toShort() else 0
        }
        surround.process(sound, 0, 4410)
        val left = (2000 until 4410).sumOf { abs(sound[it * 6].toInt()) }
        val right = (2000 until 4410).sumOf { abs(sound[it * 6 + 1].toInt()) }
        assertTrue("$left / $right", left > 3 * right)
    }

    @Test
    fun `stereo and mono are not placed, and any rate gets responses`() {
        assertEquals(false, VirtualSurround(48000, 2).places)
        assertEquals(VirtualSurround.TAPS, VirtualSurround.responsesFor(96000)[0][0].size)
    }
}
