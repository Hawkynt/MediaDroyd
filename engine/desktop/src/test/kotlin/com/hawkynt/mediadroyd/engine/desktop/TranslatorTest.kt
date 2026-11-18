package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.MarianTranslator
import com.hawkynt.mediadroyd.player.ModelFolder
import com.hawkynt.mediadroyd.player.TranslationModels
import java.io.File
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
        if (!ModelFolder.ready(dir, entry.download)) assertTrue("not fetched", ModelFolder.fetch(dir, entry.download, {}))
        MarianTranslator(dir).use { translator ->
            val german = requireNotNull(translator.translate("Where did you spend the evening?"))
            println("TRANSLATED: $german")
            assertTrue(german, "Abend" in german && german.endsWith("?"))
            val other = requireNotNull(translator.translate("The house is on fire!"))
            println("TRANSLATED: $other")
            assertTrue(other, "Haus" in other)
        }
    }
}
