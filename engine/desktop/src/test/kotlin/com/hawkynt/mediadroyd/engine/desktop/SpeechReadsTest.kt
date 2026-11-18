package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.SubtitleSync
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechReadsTest {
    private fun fixture(name: String) = File(requireNotNull(javaClass.getResource("/$name")).toURI())

    @Test
    fun `a video's whole sound is judged, a hundred verdicts a second, and its subtitle file's cues read (#450)`() {
        val clip = fixture("clip.mp4")
        val speech = requireNotNull(SpeechReads.of(clip.path, null))
        val durationMs = MediaReader(clip.path, null, decoders = emptyList(), pictures = false).use { it.durationMs }
        assertEquals(durationMs * SubtitleSync.RATE / 1000.0, speech.size.toDouble(), SubtitleSync.RATE / 5.0)
        assertTrue(SubtitleSync.cues(fixture("clip.de.srt").readText()).isNotEmpty())
    }
}
