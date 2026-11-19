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
        val sound = FilmSound({ from, _ -> opened++; SpeechReads.open(clip.path, null, from) }, durationMs)
        val first = requireNotNull(sound.samples(0, 2000))
        assertEquals(2 * FilmSound.RATE, first.size)
        sound.samples(500, 1500)
        assertEquals(1, opened)
        sound.decodeAll { }
        assertEquals(durationMs * SubtitleSync.RATE / 1000.0, sound.speech().size.toDouble(), SubtitleSync.RATE * 1.5)
        assertTrue(SubtitleSync.cues(fixture("clip.de.srt").readText()).isNotEmpty())
        sound.close()
    }

    @Test
    fun `reading ahead for everyone plays the same as reading for itself, a seek inside it too (#535)`() {
        val clip = fixture("clip.mp4")
        fun times(reader: MediaReader, seekMs: Long?): List<String> {
            val out = ArrayList<String>()
            repeat(40) {
                when (val item = reader.read()) {
                    is MediaReader.Picture -> out += "p${item.ptsMs}"
                    is MediaReader.Sound -> out += "s${item.ptsMs}:${item.pcm.size}"
                }
            }
            if (seekMs != null) {
                reader.seek(seekMs)
                repeat(20) {
                    when (val item = reader.read()) {
                        is MediaReader.Picture -> out += "p${item.ptsMs}"
                        is MediaReader.Sound -> out += "s${item.ptsMs}"
                    }
                }
            }
            return out
        }
        val plain = MediaReader(clip.path, null, decoders = emptyList()).use { times(it, 500) }
        val shared = MediaReader(clip.path, null, decoders = emptyList(), readAhead = true).use { times(it, 500) }
        assertEquals(plain, shared)
    }

    @Test
    fun `the analysis taps the film's sound from what playback reads ahead, the same as read from the file (#535)`() {
        val clip = fixture("clip.mp4")
        val own = requireNotNull(SpeechReads.open(clip.path, null, 0)).use { stream ->
            generateSequence { stream.read() }.fold(FloatArray(0)) { all, piece -> all + piece }
        }
        MediaReader(clip.path, null, decoders = emptyList(), readAhead = true).use { playback ->
            SharedReads.put(clip.path, playback)
            try {
                playback.read()
                // Read ahead: the whole short clip is in the packets now.
                Thread.sleep(500)
                var fellBack = false
                val tap = requireNotNull(playback.soundTap(0) { fellBack = true; null })
                val tapped = tap.use { stream ->
                    generateSequence { stream.read() }.fold(FloatArray(0)) { all, piece -> all + piece }
                }
                assertTrue(!fellBack)
                assertEquals(own.size.toDouble(), tapped.size.toDouble(), FilmSound.RATE * 0.05)
                // The same sound: their levels a tenth of a second at a time alike.
                fun level(pcm: FloatArray, at: Int) = (at until at + 1600).sumOf { (pcm[it] * pcm[it]).toDouble() }
                for (at in 0 until minOf(own.size, tapped.size) - 1600 step 1600) {
                    val a = level(own, at)
                    val b = level(tapped, at)
                    assertTrue("at $at: $a against $b", kotlin.math.abs(a - b) <= 0.1 * maxOf(a, b) + 1e-3)
                }
            } finally {
                SharedReads.remove(clip.path, playback)
            }
        }
    }

    @Test
    fun `read to its end and sought back, reading ahead plays the same as reading for itself (#535)`() {
        val clip = fixture("clip.mp4")
        fun run(reader: MediaReader): List<String> {
            generateSequence { reader.read() }.count()
            reader.seek(1_500)
            return List(10) { reader.read() }.map { item ->
                when (item) {
                    is MediaReader.Picture -> "p${item.ptsMs}"
                    is MediaReader.Sound -> "s${item.ptsMs}"
                    else -> "$item"
                }
            }
        }
        val plain = MediaReader(clip.path, null, decoders = emptyList()).use(::run)
        val shared = MediaReader(clip.path, null, decoders = emptyList(), readAhead = true).use(::run)
        assertEquals(plain, shared)
    }
}
