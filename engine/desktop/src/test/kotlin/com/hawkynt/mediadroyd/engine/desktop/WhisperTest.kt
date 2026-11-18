package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.ModelFolder
import com.hawkynt.mediadroyd.player.NativeWhisper
import com.hawkynt.mediadroyd.player.PiperVoices
import com.hawkynt.mediadroyd.player.WhisperModels
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val models get() = System.getProperty("mediadroyd.testModels")?.let(::File) ?: folder.newFolder("models")

    private fun fetched(download: com.hawkynt.mediadroyd.player.NeuralModels.Download, root: File): File {
        val dir = File(root, download.folder)
        if (!ModelFolder.ready(dir, download)) assertTrue("not fetched", ModelFolder.fetch(dir, download, {}))
        return dir
    }

    @Test
    fun `a German line spoken by Piper is heard back by Whisper, and translated into English (#529, #531)`() {
        assumeTrue("native library not loaded", NativeNetworks.loaded)
        assumeTrue("espeak-ng not built (-Pespeak)", EspeakLibrary.dataDir != null)
        val root = models
        val thorsten = requireNotNull(PiperVoices.of("de_DE-thorsten-medium"))
        val spoken = EspeakLibrary.speaker(fetched(thorsten.download, root), thorsten).use { speaker ->
            val line = requireNotNull(speaker.speak("Wo warst du gestern Abend?", 1f))
            // 22 050 Hz to Whisper's 16 000, linearly.
            FloatArray((line.size.toLong() * NativeWhisper.SAMPLE_RATE / speaker.sampleRate).toInt()) { i ->
                val at = i.toDouble() * speaker.sampleRate / NativeWhisper.SAMPLE_RATE
                val a = at.toInt().coerceAtMost(line.size - 1)
                val b = (a + 1).coerceAtMost(line.size - 1)
                (line[a] * (1 - (at - a)) + line[b] * (at - a)).toFloat()
            }
        }
        val model = WhisperModels.BASE
        val handle = NativeWhisper.open(File(fetched(model.download, root), model.file).path)
        assertTrue("model did not load", handle != 0L)
        try {
            val heard = requireNotNull(NativeWhisper.transcribe(handle, spoken, "auto", false, 4)).lines()
            assertEquals("de", heard.first())
            val text = heard.drop(1).joinToString(" ") { it.substringAfterLast('\t') }.lowercase()
            assertTrue(text, "gestern" in text && "abend" in text)
            val english = requireNotNull(NativeWhisper.transcribe(handle, spoken, "de", true, 4)).lines()
                .drop(1).joinToString(" ") { it.substringAfterLast('\t') }.lowercase()
            assertTrue(english, listOf("yesterday", "last night", "evening").any { it in english })
        } finally {
            NativeWhisper.close(handle)
        }
    }
}
