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
        // A centred voice (mid) at 900 Hz and a side sound (L − R) at 2 kHz, two seconds.
        val input = ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val mid = 6000 * sin(2 * PI * 900 * t)
            val side = 3000 * sin(2 * PI * 2000 * t)
            (if (i % 2 == 0) mid + side else mid - side).toInt().toShort()
        }
        // A spoken line of silence from 0.5 s to 1.5 s, so only the ducking shows.
        val track = Dub.Track().apply { add(500, FloatArray(rate) { 0f }, rate) }
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
        assertEquals(0.0, mid(out, during.first, during.last) / mid(input, during.first, during.last), 0.2)
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
        val track = Dub.Track().apply { add(250, voice, rate) }
        val out = input.copyOf()
        Dub.Mixer(rate, channels, Dub.Original.CHANNEL).process(out, 0, frames, 0L, track)
        val inside = (rate * 0.4).toInt()
        // The front left keeps its sound, the centre is removed and carries the voice instead.
        assertEquals(1000, out[inside * channels].toInt())
        val centre = out[inside * channels + 2].toInt()
        val expected = voice[inside - rate / 4]
        assertTrue("$centre against $expected", abs(centre - expected) < 2)
    }

    @Test
    fun `a clip at the voice's own rate is laid onto the track at the output's`() {
        val track = Dub.Track()
        track.add(1000, FloatArray(22_050) { 1000f }, rate = 22_050)
        val out = FloatArray(10)
        assertTrue(track.voiceAt(1000, 48_000, out))
        assertEquals(1000f, out[5], 1f)
        assertTrue(!track.voiceAt(2100, 48_000, out))
        assertTrue(track.voiceAt(1990, 48_000, out))
    }

    // A voice whose length follows the scale only in part, as Piper's does: a fixed 300 ms and 100 ms a letter scaled.
    private class FakeSpeaker : Dub.LineSpeaker {
        override val sampleRate = 1000
        val scales = ArrayList<Float>()
        val livelinesses = ArrayList<Float>()
        override fun speak(text: String, lengthScale: Float, speaker: Int, liveliness: Float): FloatArray {
            livelinesses += liveliness
            scales += lengthScale
            return FloatArray((300 + text.length * 100 * lengthScale).toInt()) { 0.5f }
        }
    }

    @Test
    fun `a line running over is spoken again faster until it fits, its delivery's loudness applied`() {
        val speaker = FakeSpeaker()
        // 10 letters: 1.3 s at its pace; 1.0 s of room before the next line.
        val fitted = requireNotNull(Dub.fit(Dub.Line(0, 900, "abcdefghij"), speaker, nextStartMs = 1000)).pcm
        assertTrue("${fitted.size} ms", fitted.size <= 1000)
        assertTrue(speaker.scales.size >= 2 && speaker.scales.last() < 1f)
        val whisper = requireNotNull(Dub.fit(Dub.Line(0, 900, "ab", delivery = Dub.WHISPER), FakeSpeaker(), null)).pcm
        assertEquals(0.5f * Math.pow(10.0, -8 / 20.0).toFloat(), whisper[0], 1e-4f)
    }

    @Test
    fun `lines are spoken ahead of the position onto the track, each once`() {
        val lines = listOf(Dub.Line(1000, 2000, "one"), Dub.Line(5000, 6000, "two"), Dub.Line(40_000, 41_000, "far"))
        val track = Dub.Track()
        val speaking = Dub.Speaking(lines, FakeSpeaker(), track)
        assertEquals(2, speaking.ahead(0))
        assertEquals(0, speaking.ahead(500))
        val voice = FloatArray(10)
        assertTrue(track.voiceAt(1000, 1000, voice))
        assertEquals(0.5f * 32_767, voice[0], 1f)
        assertEquals(1, speaking.ahead(30_000))
    }

    // Speech verdicts (SubtitleSync.RATE a second) true over [runs] (ms), over [seconds].
    private fun speech(seconds: Int, vararg runs: LongRange) = BooleanArray(seconds * SubtitleSync.RATE) { i ->
        val ms = i * 1000L / SubtitleSync.RATE
        runs.any { ms in it }
    }

    @Test
    fun `a line is moved onto the speech it belongs to, from its first word to its last`() {
        // The cue says 10.0 … 12.5 s; the actor speaks 10.4 … 11.9 s.
        val lines = listOf(Dub.Line(10_000, 12_500, "Where were you?"), Dub.Line(20_000, 21_000, "Here."))
        val aligned = Dub.align(lines, speech(30, 10_400L..11_900L, 20_100L..20_800L))
        assertEquals(10_400.0, aligned[0].startMs.toDouble(), 10.0)
        assertEquals(11_900.0, aligned[0].endMs.toDouble(), 20.0)
        assertEquals(20_100.0, aligned[1].startMs.toDouble(), 10.0)
        // No speech near a cue: it keeps its own times.
        val alone = Dub.align(listOf(Dub.Line(5000, 6000, "Hm.")), speech(30, 20_000L..21_000L))
        assertEquals(5000L, alone[0].startMs)
    }

    @Test
    fun `a line fits into the speech it replaces, not past the next line`() {
        val speaker = FakeSpeaker()
        // 10 letters, 1.3 s at its pace; the original speech it replaces lasted 1.0 s, the next line is far off.
        val fitted = requireNotNull(Dub.fit(Dub.Line(0, 1000, "abcdefghij", spokenMs = 1000), speaker, 9000)).pcm
        assertTrue("${fitted.size} ms", fitted.size <= 1100)
    }

    @Test
    fun `muted, the whole original sound goes quiet under a line`() {
        val frames = rate
        val input = ShortArray(frames * 2) { 4000 }
        val track = Dub.Track().apply { add(200, FloatArray(rate / 2) { 0f }, rate) }
        val out = input.copyOf()
        Dub.Mixer(rate, 2, Dub.Original.MUTE).process(out, 0, frames, 0L, track)
        val inside = (rate * 0.4).toInt()
        assertEquals(0, out[inside * 2].toInt())
        assertEquals(4000, out[(rate * 0.1).toInt() * 2].toInt())
    }

    @Test
    fun `lowered, stereo loses the speech band of its middle and keeps its bass`() {
        val frames = rate * 2
        // A centred 1 kHz voice and a centred 60 Hz bass.
        val input = ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            (5000 * sin(2 * PI * 1000 * t) + 5000 * sin(2 * PI * 60 * t)).toInt().toShort()
        }
        val track = Dub.Track().apply { add(0, FloatArray(frames) { 0f }, rate) }
        val out = input.copyOf()
        Dub.Mixer(rate, 2).process(out, 0, frames, 0L, track)
        // What is left: the bass's level alone, near enough (its phase turned by the low-pass); the voice gone.
        val bassOnly = ShortArray(frames * 2) { i ->
            (5000 * sin(2 * PI * 60 * (i / 2).toDouble() / rate)).toInt().toShort()
        }
        val ratio = rms(out, 0, 2, rate, frames) / rms(bassOnly, 0, 2, rate, frames)
        assertEquals("left over against the bass alone", 1.0, ratio, 0.15)
    }

    @Test
    fun `removing the dialogue channel needs one, stereo falls back to the voice band`() {
        assertTrue(Dub.hasCentre(6) && Dub.hasCentre(8) && Dub.hasCentre(3))
        assertTrue(!Dub.hasCentre(2) && !Dub.hasCentre(1))
        val frames = rate
        val input = ShortArray(frames * 2) { i ->
            (5000 * sin(2 * PI * 60 * (i / 2).toDouble() / rate)).toInt().toShort()
        }
        val track = Dub.Track().apply { add(0, FloatArray(frames) { 0f }, rate) }
        val out = input.copyOf()
        Dub.Mixer(rate, 2, Dub.Original.CHANNEL).process(out, 0, frames, 0L, track)
        // Stereo's bass stays: what "the channel" would have removed is only the voice band here.
        val ratio = rms(out, 0, 2, rate / 2, frames) / rms(input, 0, 2, rate / 2, frames)
        assertEquals(1.0, ratio, 0.1)
    }

    @Test
    fun `a higher line is spoken slower and played faster, as long, its liveliness handed on (#534)`() {
        val speaker = FakeSpeaker()
        val line = Dub.Line(0, 5000, "abcde", delivery = Dub.Delivery(pitch = 12f, liveliness = 1.3f))
        val said = requireNotNull(Dub.fit(line, speaker, null))
        // An octave up: spoken at twice the length scale, played at twice the rate — as long as at its own pitch.
        assertEquals(2f, speaker.scales.first(), 1e-4f)
        assertEquals(2000, said.rate)
        assertEquals((300 + 5 * 100 * 2) / 2.0, said.pcm.size * 1000.0 / said.rate, 1.0)
        assertEquals(1.3f, speaker.livelinesses.first(), 1e-6f)
    }

    @Test
    fun `each line is spoken by a man's or a woman's voice by the voice that spoke it (#532)`() {
        val man = FakeSpeaker()
        val woman = FakeSpeaker()
        val lines = listOf(
            Dub.Line(0, 1000, "low", heardPitchHz = 110.0),
            Dub.Line(2000, 3000, "high", heardPitchHz = 220.0),
            Dub.Line(4000, 5000, "unknown"),
        )
        val byVoice = { line: Dub.Line -> if (Dub.womanSpoke(line) == true) woman else man }
        val speaking = Dub.Speaking({ lines }, byVoice, Dub.Track())
        speaking.ahead(0)
        assertEquals(2, man.scales.size)
        assertEquals(1, woman.scales.size)
    }

    @Test
    fun `a language's man's and woman's voices are found for choosing by the speaker`() {
        val (male, female) = PiperVoices.pair("ger", espeak = true)
        assertEquals("de_DE-thorsten-medium", male?.id)
        assertEquals("de_DE-kerstin-low", female?.id)
        val (enMale, enFemale) = PiperVoices.pair("en", espeak = true)
        assertEquals("en_US-joe-medium", enMale?.id)
        assertEquals(PiperVoices.Sex.FEMALE, enFemale?.sex)
        assertEquals(null to null, PiperVoices.pair("de", espeak = false))
    }

    @Test
    fun `the film's voice steps back for the whole of its line, not only while the shorter voice-over speaks`() {
        val frames = rate * 3
        val input = ShortArray(frames * 2) { 4000 }
        // The voice-over speaks 0.5 … 1.0 s; the actor's line ran 0.5 … 2.5 s.
        val track = Dub.Track().apply {
            add(500, FloatArray(rate / 2) { 0f }, rate)
            duck(500L..2500L)
        }
        val out = input.copyOf()
        Dub.Mixer(rate, 2, Dub.Original.MUTE).process(out, 0, frames, 0L, track)
        assertEquals(0, out[(rate * 2.0).toInt() * 2].toInt())
        assertEquals(4000, out[(rate * 2.8).toInt() * 2].toInt())
    }

    @Test
    fun `suppressing the voice band takes a man's low voice too, the deep bass stays`() {
        val frames = rate * 2
        fun tone(hz: Double) = ShortArray(frames * 2) { i ->
            (5000 * sin(2 * PI * hz * (i / 2).toDouble() / rate)).toInt().toShort()
        }
        val track = Dub.Track().apply { add(0, FloatArray(frames) { 0f }, rate) }
        fun left(hz: Double): Double {
            val input = tone(hz)
            val out = input.copyOf()
            Dub.Mixer(rate, 2).process(out, 0, frames, 0L, track)
            return rms(out, 0, 2, rate, frames) / rms(input, 0, 2, rate, frames)
        }
        assertTrue("a man's 150 Hz: ${left(150.0)}", left(150.0) < 0.6)
        assertTrue("a man's 200 Hz: ${left(200.0)}", left(200.0) < 0.35)
        assertTrue("speech at 1 kHz: ${left(1000.0)}", left(1000.0) < 0.1)
        assertTrue("bass at 40 Hz: ${left(40.0)}", left(40.0) > 0.8)
    }
}
