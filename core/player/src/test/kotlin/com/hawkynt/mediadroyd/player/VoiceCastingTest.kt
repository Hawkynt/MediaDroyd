package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines sorted into the people who speak them, and each person cast a voice of their own. */
class VoiceCastingTest {

    // A voice's fingerprint near direction [axis], a little of [noise] beside it.
    private fun voice(axis: Int, noise: Int = (axis + 1) % 8, amount: Float = 0.3f) =
        SpeakerEmbedder.unit(FloatArray(8) { if (it == axis) 1f else if (it == noise) amount else 0f })

    private fun line(start: Long, text: String = "Line $start", pitch: Double? = null, speaker: String? = null) =
        Dub.Line(start, start + 2000, text, speaker = speaker, heardPitchHz = pitch)

    // Lines whose sound is "heard" as the fingerprint the test gives it.
    private val sounds = HashMap<Long, FloatArray>()
    private val casting = VoiceCasting(embed = { samples -> sounds[samples[0].toLong()] })

    private fun heard(line: Dub.Line, fingerprint: FloatArray): Int? {
        sounds[line.startMs] = fingerprint
        return casting.speakerOf(line) { FloatArray(Fbank.RATE * 2) { line.startMs.toFloat() } }
    }

    @Test
    fun `lines in the same voice are one person, another voice another`() {
        val a = heard(line(0, pitch = 110.0), voice(0))
        val b = heard(line(3000, pitch = 220.0), voice(4))
        val a2 = heard(line(6000, pitch = 115.0), voice(0, amount = 0.4f))
        assertEquals(a, a2)
        assertNotEquals(a, b)
        assertEquals(2, casting.people)
    }

    @Test
    fun `a speaker's name in the subtitles is a person of its own, no sound needed`() {
        val john = casting.speakerOf(line(0, speaker = "JOHN")) { null }
        val mary = casting.speakerOf(line(3000, speaker = "MARY")) { null }
        assertEquals(john, casting.speakerOf(line(6000, speaker = "JOHN")) { null })
        assertNotEquals(john, mary)
    }

    @Test
    fun `a line too short, or no network, says nothing about who speaks`() {
        assertNull(VoiceCasting(embed = null).speakerOf(line(0)) { FloatArray(Fbank.RATE * 2) })
        assertNull(casting.speakerOf(line(0)) { null })
    }

    @Test
    fun `each person keeps a man's or a woman's voice by their usual pitch, not line by line`() {
        val woman = heard(line(0, pitch = 230.0), voice(2))!!
        // A low line from her: still her voice.
        heard(line(3000, pitch = 150.0), voice(2))
        heard(line(6000, pitch = 210.0), voice(2))
        assertTrue(casting.cast(woman).woman)
        val man = heard(line(9000, pitch = 105.0), voice(5))!!
        assertTrue(!casting.cast(man).woman)
    }

    @Test
    fun `of two men the deeper is spoken deeper, and each has a speaker of his own in a voice of many`() {
        val deep = heard(line(0, pitch = 95.0), voice(1))!!
        val high = heard(line(3000, pitch = 140.0), voice(6))!!
        val deepCast = casting.cast(deep, speakers = 904)
        val highCast = casting.cast(high, speakers = 904)
        assertTrue("${deepCast.pitch} / ${highCast.pitch}", deepCast.pitch < highCast.pitch)
        assertTrue(deepCast.pitch >= -VoiceCasting.MAX_SEMITONES && highCast.pitch <= VoiceCasting.MAX_SEMITONES)
        assertNotEquals(deepCast.sid, highCast.sid)
        assertEquals(0, casting.cast(deep, speakers = 1).sid)
    }

    @Test
    fun `a named speaker's pitch is measured from the line's own sound where none was heard`() {
        // 220 Hz: a woman's voice, in the line's sound.
        val tone = FloatArray(Fbank.RATE * 2) { (0.3 * kotlin.math.sin(2 * Math.PI * 220 * it / Fbank.RATE)).toFloat() }
        val mary = VoiceCasting(embed = null).let { c -> c to c.speakerOf(line(0, speaker = "MARY")) { tone }!! }
        assertTrue(mary.first.cast(mary.second).woman)
    }

    @Test
    fun `a line is sorted once, however often the lines are asked for`() {
        val l = line(0, pitch = 120.0)
        val first = heard(l, voice(3))
        sounds.clear()
        assertEquals(first, casting.speakerOf(l) { error("not heard again") })
    }
}
