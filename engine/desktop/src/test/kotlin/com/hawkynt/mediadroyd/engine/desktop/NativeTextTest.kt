package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.NativeText
import com.hawkynt.mediadroyd.player.NeuralModels
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PP-OCRv5 on the desktop library (#493): credits drawn as white text on black, read back by the carried models on the
 * CPU and decoded by the model's own alphabet. Needs the library built (`-PnativeLibrary`), as NativeNeuralTest does.
 */
class NativeTextTest {
    @get:Rule
    val folder = TemporaryFolder()

    // [lines] as rolling credits draw them: centred, white on black, at the size the closing is read at.
    private fun credits(vararg lines: String): ByteArray {
        val image = BufferedImage(CreditText.WIDTH, CreditText.HEIGHT, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
        lines.forEachIndexed { i, line ->
            val x = (image.width - g.fontMetrics.stringWidth(line)) / 2
            g.drawString(line, x, 120 + i * 48)
        }
        g.dispose()
        return (image.raster.dataBuffer as DataBufferByte).data
    }

    @Test
    fun `credits drawn on black read back as their words and count as credits`() {
        val neural = DesktopNeural(folder.root)
        assertTrue("the native library did not load", neural.loaded)
        assertTrue(neural.ready(NeuralModels.PPOCR))
        val handle = NativeText.open(null, neural.folder(NeuralModels.PPOCR).path, false)
        assertTrue("the models did not load", handle != 0L)
        try {
            val grey = credits("Directed by", "Anna Berg", "Executive Producers", "Tom Hale", "Mia Roth")
            val read = NativeText.read(handle, grey, CreditText.WIDTH, CreditText.HEIGHT)
            val lines = CreditText.lines(requireNotNull(read))
            val texts = lines.map { it.text }
            assertTrue("read: $texts", texts.any { it.contains("Directed by") })
            assertTrue("read: $texts", texts.any { it.contains("Anna Berg") })
            assertTrue("read: $texts", CreditText.isCredits(lines))
            val story = CreditText.lines(
                requireNotNull(NativeText.read(handle, credits(), CreditText.WIDTH, CreditText.HEIGHT)),
            )
            assertTrue("read on black: $story", story.isEmpty())
        } finally {
            NativeText.close(handle)
        }
    }

    @Test
    fun `credits over a scene are found by their text, end to end`() {
        val neural = DesktopNeural(folder.root)
        assertTrue("the native library did not load", neural.loaded)
        val w = 640
        val h = 360
        val names = listOf("Directed by", "Anna Berg", "Produced by", "Tom Hale", "Music by", "Mia Roth")
        // A bright scene that drifts; over its last 20 seconds — in its last quarter, where credits may begin (#554) —
        // two credits at a time in white with a dark edge.
        fun frame(second: Int): ByteArray {
            val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            val g = image.createGraphics()
            for (x in 0 until w step 8) {
                g.color = Color(Color.HSBtoRGB(0f, 0f, 0.35f + 0.3f * ((x + second * 13) % w) / w))
                g.fillRect(x, 0, 8, h)
            }
            if (second >= 70) {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
                val pair = (second - 70) / 5 % 3
                for ((row, line) in names.subList(pair * 2, pair * 2 + 2).withIndex()) {
                    val x = (w - g.fontMetrics.stringWidth(line)) / 2
                    val y = 150 + row * 40
                    g.color = Color.BLACK
                    for (d in listOf(-2, 2)) {
                        g.drawString(line, x + d, y)
                        g.drawString(line, x, y + d)
                    }
                    g.color = Color.WHITE
                    g.drawString(line, x, y)
                }
            }
            g.dispose()
            return (image.raster.dataBuffer as DataBufferByte).data
        }
        val file = folder.newFile("episode.y4m")
        file.outputStream().buffered().use { out ->
            out.write("YUV4MPEG2 W$w H$h F1:1 Ip A1:1 C420jpeg\n".toByteArray())
            val chroma = ByteArray(w / 2 * (h / 2)) { 128.toByte() }
            for (second in 0 until 90) {
                out.write("FRAME\n".toByteArray())
                out.write(frame(second))
                out.write(chroma)
                out.write(chroma)
            }
        }
        val start = CreditText.find { at -> CreditReads.read(file.path, null, neural, at) }
        assertEquals(70_000.0, requireNotNull(start).toDouble(), 1000.0)
    }
}
