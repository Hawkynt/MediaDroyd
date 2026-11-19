package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PiperVoiceTest {
    private val thorsten = PiperVoice.parse(
        requireNotNull(javaClass.getResource("/piper/de_DE-thorsten-medium.onnx.json")).readText(),
    )

    @Test
    fun `a Piper voice's config reads as its rate, its phonemes' ids, its espeak voice and its defaults`() {
        assertEquals(22_050, thorsten.sampleRate)
        assertEquals("de", thorsten.espeakVoice)
        assertEquals(PiperVoice.Phonemes.ESPEAK, thorsten.phonemes)
        assertEquals(0.5f, thorsten.noiseScale)
        assertEquals(0.333f, thorsten.noiseW)
        assertEquals(1, thorsten.speakers)
    }

    @Test
    fun `phonemes become ids as Piper makes them - start, each phoneme followed by the pad, end`() {
        // ^ = 1, _ = 0, $ = 2; a = 14, ˈ = 120, . = 10 (the voice's own map).
        assertArrayEquals(longArrayOf(1, 0, 120, 0, 14, 0, 10, 0, 2), thorsten.ids("ˈa."))
        // A phoneme the voice does not know is left out.
        assertArrayEquals(longArrayOf(1, 0, 14, 0, 2), thorsten.ids("a☃"))
    }

    @Test
    fun `text is split into clauses, each with the punctuation that ends it`() {
        assertEquals(
            listOf("Wo warst du" to ',', "gestern" to '?', "Na" to '.', "gut" to null),
            PiperVoice.clauses("Wo warst du, gestern? Na. gut"),
        )
    }

    @Test
    fun `espeak's clauses become one phoneme string, its language switches dropped`() {
        val joined = PiperVoice.join(listOf("vˈoː" to ',', "(en)hˈɛloʊ(de)" to '?'))
        assertEquals("vˈoː, hˈɛloʊ?", joined)
    }

    @Test
    fun `the catalogue offers a subtitle language's voices first, espeak's voices only where the build has espeak`() {
        assertEquals("de-DE", PiperVoices.forLanguage("ger", espeak = true).first().language)
        assertEquals("en", PiperVoices.forLanguage("eng", espeak = true).first().language.take(2))
        assertEquals(emptyList<PiperVoices.Entry>(), PiperVoices.forLanguage("de", espeak = false))
        val thorsten = requireNotNull(PiperVoices.of("de_DE-thorsten-medium"))
        assertEquals(listOf("de_DE-thorsten-medium.onnx", "de_DE-thorsten-medium.onnx.json"),
            thorsten.download.files.map { it.name })
        val url = thorsten.download.url(thorsten.download.files[0])
        assertEquals(true, url.endsWith("/de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx"))
    }
}
