package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.ModelFolder
import com.hawkynt.mediadroyd.player.PiperVoices
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PiperSpeakerTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a German line is phonemised by espeak-ng and spoken by Piper's voice at its pace, and faster (#531)`() {
        assumeTrue("espeak-ng not built (-Pespeak)", EspeakLibrary.dataDir != null)
        val models = System.getProperty("mediadroyd.testModels")?.let(::File) ?: folder.newFolder("models")
        val entry = requireNotNull(PiperVoices.of("de_DE-thorsten-medium"))
        val dir = File(models, entry.download.folder)
        if (!ModelFolder.ready(dir, entry.download)) assertTrue("not fetched", ModelFolder.fetch(dir, entry.download, {}))
        PiperSpeaker(dir, entry).use { speaker ->
            assertEquals("vˌoː vɑːɾst duː ɡˈɛstɜn ˈɑːbənt?", speaker.phonemes("Wo warst du gestern Abend?"))
            val line = requireNotNull(speaker.speak("Wo warst du gestern Abend?"))
            val seconds = line.size / speaker.voice.sampleRate.toDouble()
            assertTrue("$seconds s", seconds in 1.0..3.0)
            val rms = sqrt(line.sumOf { (it * it).toDouble() } / line.size)
            assertTrue("rms $rms", rms > 0.02)
            // Spoken faster it is shorter, though not in proportion: pauses and the voice's own variation stay.
            val faster = requireNotNull(speaker.speak("Wo warst du gestern Abend?", lengthScale = 0.7f))
            assertTrue("${faster.size} against ${line.size}", faster.size < line.size * 0.92)
        }
    }
}
