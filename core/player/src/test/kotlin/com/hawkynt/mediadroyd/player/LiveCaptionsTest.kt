package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptionsTest {
    // A film whose speech is a line every 4 s, 2 s long, "line N" — heard as Whisper hears a window: the segments it
    // overlaps, in times from the window's start, the last one cut off at the window's end.
    private val asked = ArrayList<LongRange>()

    private fun hear(fromMs: Long, toMs: Long): LiveCaptions.Heard {
        asked += fromMs..toMs
        val segments = (0 until 1000).map { n -> n * 4000L to n * 4000L + 2000 }
            .filter { (start, end) -> end > fromMs && start < toMs }
            .map { (start, end) ->
                LiveCaptions.Segment(maxOf(start, fromMs) - fromMs, minOf(end, toMs) - fromMs, " line ${start / 4000}")
            }
        return LiveCaptions.Heard("en", segments)
    }

    @Test
    fun `the speech ahead of the position becomes lines, none cut at a window's end, each once`() {
        val captions = LiveCaptions(::hear, durationMs = 600_000)
        captions.ahead(0)
        val lines = captions.lines()
        assertTrue(lines.size >= LiveCaptions.AHEAD_MS / 4000 - 1)
        assertEquals("line 0", lines[0].text)
        assertEquals(4000L, lines[1].startMs)
        assertEquals(2000L, lines[0].endMs - lines[0].startMs)
        // Every line whole and once.
        assertEquals(lines.map { it.text }.distinct(), lines.map { it.text })
        assertTrue(lines.all { it.endMs - it.startMs == 2000L })
        assertEquals("en", captions.language)
        // Nothing more is heard while the position stays within what is heard.
        val before = asked.size
        captions.ahead(10_000)
        assertEquals(before, asked.size)
    }

    @Test
    fun `a model caught in a loop is heard once, not line after line (#541)`() {
        val loop = "the only one who's the only one who's the only one who's the only one who's the only one who's"
        val window = listOf(
            LiveCaptions.Segment(0, 2000, " No, no, no!"),
            LiveCaptions.Segment(2000, 4000, " No, no, no, no!"),
            LiveCaptions.Segment(4000, 6000, " No, no, no!"),
            LiveCaptions.Segment(6000, 9000, " It's the last time I've seen you, but I have no idea what you're doing."),
            LiveCaptions.Segment(9000, 12_000, " It's the last time I've seen you, but I still have no idea what you're doing."),
            LiveCaptions.Segment(12_000, 15_000, " $loop"),
            LiveCaptions.Segment(15_000, 17_000, " Let's go."),
            LiveCaptions.Segment(17_000, 19_000, " 我看你是不是被你撐掉了嗎?"),
            LiveCaptions.Segment(19_000, 21_000, " 我看你是不是被你撐掉了嗎?"),
        )
        val captions = LiveCaptions({ _, _ -> LiveCaptions.Heard("en", window) }, durationMs = 21_000)
        captions.ahead(0)
        val once = listOf(
            "No, no, no!", "It's the last time I've seen you, but I have no idea what you're doing.", "Let's go.",
            "我看你是不是被你撐掉了嗎?",
        )
        assertEquals(once, captions.lines().map { it.text })
        // The same before translating, so a loop of hundreds of words is not translated first.
        assertEquals(once, Loops.drop(window).map { it.text.trim() })
    }

    @Test
    fun `a seek beyond what is heard starts hearing there`() {
        val captions = LiveCaptions(::hear, durationMs = 600_000)
        captions.ahead(0)
        assertEquals(false, captions.heardAt(300_000))
        captions.ahead(300_000)
        assertEquals(true, captions.heardAt(300_000))
        val line = captions.lineAt(300_500)
        assertEquals("line 75", line?.text)
        assertEquals(null, captions.lineAt(302_500))
    }

    @Test
    fun `made subtitles reach any language through English`() {
        assertEquals(CaptionRoute(false, null), CaptionRoute.of("", "ja"))
        assertEquals(CaptionRoute(true, null), CaptionRoute.of("en", "ja"))
        assertEquals(CaptionRoute(false, null), CaptionRoute.of("en", "en"))
        // Japanese speech into German: Whisper's English, then OPUS-MT into German.
        assertEquals(CaptionRoute(true, "de"), CaptionRoute.of("de", "ja"))
        // English speech into German: Whisper writes English, OPUS-MT translates it.
        assertEquals(CaptionRoute(false, "de"), CaptionRoute.of("de", "en"))
        // German speech into German: written as spoken.
        assertEquals(CaptionRoute(false, null), CaptionRoute.of("de", "de"))
        // Not known yet: English first, to be safe.
        assertEquals(CaptionRoute(true, "de"), CaptionRoute.of("de", null))
    }
}
