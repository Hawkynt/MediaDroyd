package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.FilmSound
import com.hawkynt.mediadroyd.player.SubtitleSync
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechReadsTest {
    private fun fixture(name: String) = File(requireNotNull(javaClass.getResource("/$name")).toURI())

    @Test
    fun `a video's sound is read once from where it is asked, for every analysis (#535)`() {
        val clip = fixture("clip.mp4")
        val durationMs = MediaReader(clip.path, null, decoders = emptyList(), pictures = false).use { it.durationMs }
        var opened = 0
        val sound = FilmSound({ from -> opened++; SpeechReads.open(clip.path, null, from) }, durationMs)
        val first = requireNotNull(sound.samples(0, 2000))
        assertEquals(2 * FilmSound.RATE, first.size)
        sound.samples(500, 1500)
        assertEquals(1, opened)
        sound.decodeAll { }
        assertEquals(durationMs * SubtitleSync.RATE / 1000.0, sound.speech().size.toDouble(), SubtitleSync.RATE * 1.5)
        assertTrue(SubtitleSync.cues(fixture("clip.de.srt").readText()).isNotEmpty())
        sound.close()
    }
}
