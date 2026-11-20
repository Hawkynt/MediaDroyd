package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.MarianTranslator
import com.hawkynt.mediadroyd.player.ModelFolder
import com.hawkynt.mediadroyd.player.TranslationModels
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an English line is translated into German by OPUS-MT (#530)`() {
        val models = System.getProperty("mediadroyd.testModels")?.let(::File) ?: folder.newFolder("models")
        val entry = requireNotNull(TranslationModels.into("de"))
        val dir = File(models, entry.download.folder)
        if (!ModelFolder.ready(dir, entry.download)) {
            assertTrue("not fetched", ModelFolder.fetch(dir, entry.download, {}))
        }
        MarianTranslator(dir).use { translator ->
            val german = requireNotNull(translator.translate("Where did you spend the evening?"))
            println("TRANSLATED: $german")
            assertTrue(german, "Abend" in german && german.endsWith("?"))
            val other = requireNotNull(translator.translate("The house is on fire!"))
            println("TRANSLATED: $other")
            assertTrue(other, "Haus" in other)
        }
    }

    @Test
    fun `Chinese and Japanese lines written as spoken are translated into English by their own OPUS-MT (#546)`() {
        val models = System.getProperty("mediadroyd.testModels")?.let(::File) ?: folder.newFolder("models")
        // Lines as Whisper writes them from a Chinese film's speech, and plain Japanese ones.
        val cases = mapOf(
            "zh" to listOf("你是来报名的吗" to "sign up", "是的" to "Yes", "好 走" to "go"),
            "ja" to listOf("ありがとうございます" to "Thank you", "どこへ行くの?" to "Where", "家が燃えている!" to "fire"),
        )
        for ((language, lines) in cases) {
            val entry = requireNotNull(TranslationModels.from(language))
            val dir = File(models, entry.download.folder)
            if (!ModelFolder.ready(dir, entry.download)) {
                assertTrue("not fetched", ModelFolder.fetch(dir, entry.download, {}))
            }
            MarianTranslator(dir).use { translator ->
                val english = requireNotNull(translator.translateAll(lines.map { it.first }))
                lines.zip(english).forEach { (line, out) ->
                    println("TRANSLATED ${line.first} -> $out")
                    assertTrue("${line.first} -> $out", line.second in out)
                }
            }
        }
    }

    @Test
    fun `a window's lines are translated together as each would be alone, and faster (#530)`() {
        val models = System.getProperty("mediadroyd.testModels")?.let(::File) ?: folder.newFolder("models")
        val entry = requireNotNull(TranslationModels.into("de"))
        val dir = File(models, entry.download.folder)
        if (!ModelFolder.ready(dir, entry.download)) {
            assertTrue("not fetched", ModelFolder.fetch(dir, entry.download, {}))
        }
        val lines = listOf(
            "Where did you spend the evening?", "The house is on fire!", "No.",
            "I told you we would be late again, and now look at us.", "Thank you very much.",
            "He was with the old man who is said to be the river.", "Run!", "What do you want from me?",
        )
        MarianTranslator(dir).use { translator ->
            translator.translateAll(lines.take(2))
            var started = System.nanoTime()
            val alone = lines.map { requireNotNull(translator.translate(it)) }
            val aloneMs = (System.nanoTime() - started) / 1_000_000
            started = System.nanoTime()
            val together = requireNotNull(translator.translateAll(lines))
            val togetherMs = (System.nanoTime() - started) / 1_000_000
            println("TRANSLATED alone in $aloneMs ms, together in $togetherMs ms")
            lines.indices.forEach { println("TRANSLATED ${lines[it]} -> ${together[it]}") }
            // Padded beside longer lines a line may come out a word apart (the quantised network's sums): one at most.
            assertEquals(lines.size, together.size)
            assertTrue("$alone against $together", alone.zip(together).count { (a, b) -> a != b } <= 1)
            assertTrue("together $togetherMs ms against alone $aloneMs ms", togetherMs < aloneMs)
        }
    }
}
