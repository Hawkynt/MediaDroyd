package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSyncTest {
    // A film's speech: lines of 0.6 … 4 s with pauses of 0.3 … 6 s, over [seconds].
    private fun lines(seconds: Int, seed: Int = 1): List<LongRange> {
        val random = Random(seed)
        val out = ArrayList<LongRange>()
        var at = 2000L
        while (at < seconds * 1000L) {
            val length = 600L + random.nextInt(3400)
            out += at until at + length
            at += length + 300 + random.nextInt(5700)
        }
        return out
    }

    private fun speechOf(lines: List<LongRange>, seconds: Int) = BooleanArray(seconds * SubtitleSync.RATE) { i ->
        val ms = i * 1000L / SubtitleSync.RATE
        lines.any { ms in it }
    }

    @Test
    fun `cues running late are moved onto the speech`() {
        val speech = lines(1200)
        val cues = speech.map { (it.first + 3200)..(it.last + 3200) }
        val fit = requireNotNull(SubtitleSync.fit(speechOf(speech, 1200), cues))
        assertEquals(-3200.0, fit.offsetMs.toDouble(), 20.0)
        assertEquals(1.0, fit.ratio, 1e-9)
    }

    @Test
    fun `cues timed for another frame rate are stretched back and moved`() {
        val speech = lines(1800)
        // Timed for 25 fps where the film plays at 23.976: every time 25 / 23.976 too early, then 1.5 s late.
        val ratio = 23.976 / 25.0
        val cues = speech.map { (it.first * ratio + 1500).toLong()..(it.last * ratio + 1500).toLong() }
        val fit = requireNotNull(SubtitleSync.fit(speechOf(speech, 1800), cues))
        assertEquals(25.0 / 23.976, fit.ratio, 1e-9)
        // The fit maps a cue's time t to t × ratio + offset.
        val mapped = fit.timeOf(cues[100].first)
        assertEquals(speech[100].first.toDouble(), mapped.toDouble(), 60.0)
    }

    @Test
    fun `no speech or no cues gives nothing`() {
        assertNull(SubtitleSync.fit(BooleanArray(1000), lines(10)))
        assertNull(SubtitleSync.fit(speechOf(lines(10), 10), emptyList()))
    }

    @Test
    fun `cue times are read from SubRip, WebVTT and ASS`() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHello\n\n2\n01:02:03,004 --> 01:02:04,000\nThere\n"
        assertEquals(listOf(1500L..3000L, 3723004L..3724000L), SubtitleSync.cues(srt))
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.500 align:start\nHi\n\n00:00:10.000 --> 00:00:11.000\nYo\n"
        assertEquals(listOf(1000L..2500L, 10_000L..11_000L), SubtitleSync.cues(vtt))
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:05.10,0:00:07.25,Default,,0,0,0,,Hi\n"
        assertEquals(listOf(5100L..7250L), SubtitleSync.cues(ass))
    }

    @Test
    fun `speech is told from a quiet room by its energy in the speech band`() {
        val rate = 16_000
        val random = Random(3)
        val talk = listOf(2000L until 4000L, 6500L until 7300L)
        val samples = ShortArray(10 * rate) { i ->
            val ms = i * 1000L / rate
            val hiss = random.nextInt(-60, 61)
            val voice = if (talk.any { ms in it }) (3000 * sin(2 * PI * 440 * i / rate)).toInt() else 0
            (hiss + voice).toShort()
        }
        val detector = SubtitleSync.Speech(rate, 1)
        detector.feed(samples, 0, samples.size)
        val speech = detector.result()
        assertNotNull(speech)
        assertEquals(10 * SubtitleSync.RATE, speech.size)
        fun at(ms: Int) = speech[ms * SubtitleSync.RATE / 1000]
        assertTrue(at(3000) && at(6900))
        assertTrue(!at(1000) && !at(5000) && !at(9000))
    }
}
