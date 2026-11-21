package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.PictureCues
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Intros and credits found by their pictures on the desktop, end to end (#492): generated episodes in raw Y4M (no
 * encoder needed) decoded by FFmpeg (PicturePrints), shrunk to gray and matched (PictureCues, as SkipFinder does).
 */
class PicturePrintsTest {
    private val w = 160
    private val h = 90
    private val fps = 2

    // [seconds] of shots, each its own lit pattern for two seconds, chosen by [seed].
    private fun shots(seconds: Int, seed: Int): List<ByteArray> {
        val random = Random(seed)
        return List(seconds / 2) {
            val a = 40 + random.nextInt(150)
            val b = 40 + random.nextInt(150)
            val cx = random.nextInt(w)
            ByteArray(w * h) { i -> (if ((i % w - cx) * (i % w - cx) < 900) a else b + i % w / 4).toByte() }
        }.flatMap { shot -> List(2 * fps) { shot } }
    }

    // [seconds] of white strokes on black, as rolling credits.
    private fun credits(seconds: Int) = List(seconds * fps) {
        ByteArray(w * h) { i -> if (i / w % 9 < 4 && i % 3 == 0 && i % w in 30..130) 230.toByte() else 16 }
    }

    private companion object {
        // Long enough that the opening looked at (its first quarter) leaves the credits out.
        const val STORY = 600
    }

    // An episode: a cold open of [coldOpen] seconds, the shared intro, its story, the credits.
    private fun episode(dir: File, n: Int, coldOpen: Int): File {
        val frames = shots(coldOpen, 10 + n) + shots(40, 1) + shots(STORY, 20 + n) + credits(60)
        val file = File(dir, "e$n.y4m")
        file.outputStream().buffered().use { out ->
            out.write("YUV4MPEG2 W$w H$h F$fps:1 Ip A1:1 C420jpeg\n".toByteArray())
            val chroma = ByteArray(w / 2 * (h / 2)) { 128.toByte() }
            for (y in frames) {
                out.write("FRAME\n".toByteArray())
                out.write(y)
                out.write(chroma)
                out.write(chroma)
            }
        }
        return file
    }

    @Test
    fun `two episodes' intro and credits are found by their pictures, each at its own time`() {
        val dir = kotlin.io.path.createTempDirectory("episodes").toFile()
        try {
            val first = episode(dir, 1, coldOpen = 12)
            val second = episode(dir, 2, coldOpen = 30)
            fun frames(file: File, closing: Boolean) = requireNotNull(PicturePrints.of(file.path, null, closing))
            val (a, b) = requireNotNull(PictureCues.intro(frames(first, false), frames(second, false)))
            // Within the step and the smoothing, the edges where they are (no margin, #553).
            assertEquals(12_000.0, a.startMs.toDouble(), 2500.0)
            assertEquals(52_000.0, a.endMs.toDouble(), 2500.0)
            assertEquals(30_000.0, b.startMs.toDouble(), 2500.0)
            // The credits from the first frame of text, within a step.
            fun credits(file: File) = requireNotNull(PictureCues.creditsStartMs(frames(file, true))).toDouble()
            assertEquals((12 + 40 + STORY) * 1000.0, credits(first), 1000.0)
            assertEquals((30 + 40 + STORY) * 1000.0, credits(second), 1000.0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the second around an edge of the credits is decoded frame by frame, the edge found to the frame (#557)`() {
        val dir = kotlin.io.path.createTempDirectory("edges").toFile()
        try {
            // 25 frames a second: a scene to 4.36 s, credits to 8.36 s, a scene hidden after them to 12 s.
            val scene = shots(2, 3).first()
            val text = credits(1).first()
            val file = File(dir, "edges.y4m")
            file.outputStream().buffered().use { out ->
                out.write("YUV4MPEG2 W$w H$h F25:1 Ip A1:1 C420jpeg\n".toByteArray())
                val chroma = ByteArray(w / 2 * (h / 2)) { 128.toByte() }
                for (frame in 0 until 300) {
                    out.write("FRAME\n".toByteArray())
                    out.write(if (frame in 109 until 209) text else scene)
                    out.write(chroma)
                    out.write(chroma)
                }
            }
            fun between(fromMs: Long) =
                requireNotNull(PicturePrints.between(file.path, null, fromMs, fromMs + 1000, 40))
            assertEquals(4_360L, PictureCues.creditsStartIn(between(4_000)))
            assertEquals(8_360L, PictureCues.creditsEndIn(between(8_000)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
