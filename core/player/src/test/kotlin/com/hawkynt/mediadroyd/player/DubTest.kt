package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubTest {
    @Test
    fun `a cue's words are spoken, its tags, speaker and sound descriptions not`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            <i>JOHN: Where were you?</i>

            2
            00:00:04,000 --> 00:00:05,000
            [door slams]

            3
            00:00:06,000 --> 00:00:08,000
            ♪ la la la ♪

            4
            00:00:09,000 --> 00:00:10,500
            {\an8}Fine. (laughs) Really.
        """.trimIndent()
        val lines = Dub.lines(srt)
        assertEquals(listOf("Where were you?", "Fine. Really."), lines.map { it.text })
        assertEquals("JOHN", lines[0].speaker)
        assertEquals(1000L, lines[0].startMs)
        assertEquals(10_500L, lines[1].endMs)
    }

    @Test
    fun `whispers are spoken quieter and slower, shouts louder and faster`() {
        val whisper = Dub.clean("(whispering) Don't move.")
        assertEquals("Don't move.", whisper.text)
        assertTrue(whisper.delivery.gainDb < 0 && whisper.delivery.rate < 1)
        val shout = Dub.clean("GET OUT OF HERE!")
        assertTrue(shout.delivery.gainDb > 0 && shout.delivery.rate > 1)
        assertEquals("Get out of here!", shout.text)
        val plain = Dub.clean("Hello there.")
        assertEquals(Dub.Delivery(), plain.delivery)
        assertNull(plain.speaker)
    }

    @Test
    fun `a line too long for its time is spoken faster, never past half as fast again nor slower than natural`() {
        // Spoken in 3 s where the cue and the pause after it give 2 s: a length scale of 2/3.
        assertEquals(2f / 3, Dub.lengthScale(spokenMs = 3000, cueStartMs = 0, nextStartMs = 2000, rate = 1f), 1e-4f)
        // Plenty of time: the voice's own pace, or its delivery's.
        assertEquals(1f, Dub.lengthScale(spokenMs = 1000, cueStartMs = 0, nextStartMs = 5000, rate = 1f), 1e-6f)
        assertEquals(1f / 0.9f, Dub.lengthScale(spokenMs = 1000, cueStartMs = 0, nextStartMs = 5000, rate = 0.9f),
            1e-4f)
        // Far too long: at most half as fast again.
        assertEquals(1f / 1.5f, Dub.lengthScale(spokenMs = 9000, cueStartMs = 0, nextStartMs = 1000, rate = 1f), 1e-4f)
    }

    private val rate = 48_000

    private fun rms(samples: ShortArray, channel: Int, channels: Int, from: Int, to: Int): Double {
        var sum = 0.0
        for (f in from until to) sum += samples[f * channels + channel].toDouble().let { it * it }
        return sqrt(sum / (to - from))
    }

    @Test
    fun `under a spoken line stereo's middle steps back, the sides and the rest stay`() {
        val frames = rate * 2
        // A centred voice (mid) at 300 Hz and a side sound (L − R) at 2 kHz, two seconds.
        val input = ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val mid = 6000 * sin(2 * PI * 300 * t)
            val side = 3000 * sin(2 * PI * 2000 * t)
            (if (i % 2 == 0) mid + side else mid - side).toInt().toShort()
        }
        // A spoken line of silence from 0.5 s to 1.5 s, so only the ducking shows.
        val track = Dub.Track(rate).apply { add(500, FloatArray(rate)) }
        val out = input.copyOf()
        Dub.Mixer(rate, 2).process(out, 0, frames, 0L, track)
        fun mid(s: ShortArray, a: Int, b: Int): Double {
            var sum = 0.0
            for (f in a until b) sum += ((s[f * 2] + s[f * 2 + 1]) / 2.0).let { it * it }
            return sqrt(sum / (b - a))
        }
        fun side(s: ShortArray, a: Int, b: Int): Double {
            var sum = 0.0
            for (f in a until b) sum += ((s[f * 2] - s[f * 2 + 1]) / 2.0).let { it * it }
            return sqrt(sum / (b - a))
        }
        val during = (rate * 0.7).toInt() until (rate * 1.3).toInt()
        assertEquals(Dub.DUCK.toDouble(), mid(out, during.first, during.last) / mid(input, during.first, during.last),
            0.02)
        assertEquals(1.0, side(out, during.first, during.last) / side(input, during.first, during.last), 0.01)
        val before = 0 until (rate * 0.4).toInt()
        assertEquals(1.0, mid(out, before.first, before.last) / mid(input, before.first, before.last), 0.01)
    }

    @Test
    fun `a 5_1 file's centre channel steps back and carries the voice`() {
        val channels = 6
        val frames = rate
        val input = ShortArray(frames * channels) { i -> if (i % channels == 2) 8000 else 1000 }
        val voice = FloatArray(rate / 2) { (2000 * sin(2 * PI * 440 * it / rate)).toFloat() }
        val track = Dub.Track(rate).apply { add(250, voice) }
        val out = input.copyOf()
        Dub.Mixer(rate, channels).process(out, 0, frames, 0L, track)
        val inside = (rate * 0.4).toInt()
        // The front left keeps its sound, the centre is ducked and the voice added on it.
        assertEquals(1000, out[inside * channels].toInt())
        val centre = out[inside * channels + 2].toInt()
        val expected = 8000 * Dub.DUCK + voice[inside - rate / 4]
        assertTrue("$centre against $expected", abs(centre - expected) < 2)
    }

    @Test
    fun `a clip at the voice's own rate is laid onto the track at the output's`() {
        val track = Dub.Track(48_000)
        track.add(1000, FloatArray(22_050) { 1000f }, sourceRate = 22_050)
        val out = FloatArray(10)
        assertTrue(track.voiceAt(1000, out))
        assertEquals(1000f, out[5], 1f)
        assertTrue(!track.voiceAt(2100, out))
        assertTrue(track.voiceAt(1990, out))
    }
}
