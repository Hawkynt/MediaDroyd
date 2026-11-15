package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureCuesTest {
    private val w = PictureCues.LOOK_WIDTH
    private val h = PictureCues.LOOK_HEIGHT

    // A frame of the look size filled by [pixel].
    private fun frame(pixel: (x: Int, y: Int) -> Int) =
        ByteArray(w * h) { pixel(it % w, it / w).coerceIn(0, 255).toByte() }

    private val black = frame { _, _ -> 12 }

    // Lines of white letters on black, as rolling credits: one- and two-pixel strokes, words between gaps.
    private fun credits(seed: Int): ByteArray {
        val random = Random(seed)
        val lit = HashSet<Int>()
        for (row in 10 until h - 10 step 9) {
            var x = 20 + random.nextInt(20)
            while (x < w - 30) {
                repeat(3 + random.nextInt(6)) {
                    val stroke = 1 + random.nextInt(2)
                    for (dx in 0 until stroke) for (dy in 0 until 4) lit += (row + dy) * w + x + dx
                    x += stroke + 1
                }
                x += 4 + random.nextInt(4)
            }
        }
        return ByteArray(w * h) { if (it in lit) 230.toByte() else 14 }
    }

    // A filmed scene: a lit gradient with soft shapes.
    private fun scene(seed: Int): ByteArray {
        val random = Random(seed)
        val cx = random.nextInt(w)
        val cy = random.nextInt(h)
        val base = 60 + random.nextInt(80)
        return frame { x, y -> base + x / 2 - y / 3 + if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < 400) 50 else 0 }
    }

    // A dark night scene with a bright moon: dark and bright, but no text.
    private val moon = frame { x, y -> if ((x - 110) * (x - 110) + (y - 30) * (y - 30) < 144) 235 else 22 }

    @Test
    fun `a frame looks black, like text on black, or like anything else`() {
        assertEquals(PictureCues.Look.BLACK, PictureCues.look(black, w, h))
        assertEquals(PictureCues.Look.TEXT, PictureCues.look(credits(1), w, h))
        assertEquals(PictureCues.Look.OTHER, PictureCues.look(scene(2), w, h))
        assertEquals(PictureCues.Look.OTHER, PictureCues.look(moon, w, h))
    }

    private fun closing(vararg parts: Pair<Int, (Int) -> ByteArray>): PictureCues.Frames {
        val frames = parts.flatMap { (seconds, make) -> List(seconds) { make(it) } }
        return PictureCues.Frames(w, h, startMs = 1_000_000, stepMs = 1000, frames = frames)
    }

    @Test
    fun `the credits begin where black and text frames begin and run to the end`() {
        // Given the story, a cut to black, a minute of credits and a black tail
        val frames = closing(300 to { i -> scene(i) }, 1 to { _ -> black }, 60 to { i -> credits(i) }, 5 to { _ -> black })
        // Then the credits begin at the cut to black
        assertEquals(1_000_000L + 300_000, PictureCues.creditsStartMs(frames))
    }

    @Test
    fun `a moment of text or a story to the end is no credits`() {
        assertNull(PictureCues.creditsStartMs(closing(300 to { i -> scene(i) })))
        assertNull(PictureCues.creditsStartMs(closing(290 to { i -> scene(i) }, 8 to { i -> credits(i) })))
        // Credits in the middle with the story going on long after them are not the end credits.
        assertNull(
            PictureCues.creditsStartMs(closing(100 to { i -> scene(i) }, 60 to { i -> credits(i) }, 200 to { i -> scene(i) })),
        )
    }

    // An opening at the hash size and step: shots of a few seconds, each its own random picture; the shared intro the
    // same shots in both episodes, each encode a little noisy.
    private fun shots(seconds: Int, seed: Int, noise: Int): List<ByteArray> {
        val perSecond = (1000 / PictureCues.OPENING_STEP_MS).toInt()
        val random = Random(seed)
        val wobble = Random(seed * 31 + noise)
        val out = ArrayList<ByteArray>()
        while (out.size < seconds * perSecond) {
            val shot = ByteArray(PictureCues.HASH_WIDTH * PictureCues.HASH_HEIGHT) { (40 + random.nextInt(180)).toByte() }
            repeat((2 + random.nextInt(3)) * perSecond) {
                out += ByteArray(shot.size) { i -> ((shot[i].toInt() and 0xff) + wobble.nextInt(-3, 4)).toByte() }
            }
        }
        return out.take(seconds * perSecond)
    }

    private fun opening(frames: List<ByteArray>) = PictureCues.Frames(
        PictureCues.HASH_WIDTH, PictureCues.HASH_HEIGHT, startMs = 0, stepMs = PictureCues.OPENING_STEP_MS, frames,
    )

    @Test
    fun `the intro two openings share in pictures is found in each, wherever a cold open puts it`() {
        val intro1 = shots(40, seed = 7, noise = 1)
        val intro2 = shots(40, seed = 7, noise = 2)
        val first = opening(shots(20, 1, 0) + intro1 + shots(300, 2, 0))
        val second = opening(shots(35, 3, 0) + intro2 + shots(300, 4, 0))
        val (a, b) = requireNotNull(PictureCues.intro(first, second))
        fun near(wantMs: Long, gotMs: Long) = assertTrue("$gotMs, not about $wantMs", abs(gotMs - wantMs) <= 2500)
        near(20_000, a.startMs)
        near(60_000, a.endMs)
        near(35_000, b.startMs)
        near(75_000, b.endMs)
        assertNull(PictureCues.intro(opening(shots(300, 5, 0)), opening(shots(300, 6, 0))))
    }

    @Test
    fun `black and flat frames never match each other, so fades are no intro`() {
        val flat = ByteArray(PictureCues.HASH_WIDTH * PictureCues.HASH_HEIGHT) { 16 }
        val hashes = (0 until 100).map { PictureCues.hash(flat, PictureCues.HASH_WIDTH, PictureCues.HASH_HEIGHT, it) }
        val apart = hashes.zipWithNext { x, y -> Integer.bitCount(x xor y) }
        assertTrue("flat frames hash alike: $apart", apart.average() > 12)
        assertNull(PictureCues.intro(opening(List(1200) { flat }), opening(List(1200) { flat })))
    }

    @Test
    fun `the same picture hashes alike through noise, other pictures far apart`() {
        val (a, b) = shots(2, seed = 9, noise = 1).first() to shots(2, seed = 9, noise = 5).first()
        val other = shots(2, seed = 10, noise = 1).first()
        fun hash(f: ByteArray) = PictureCues.hash(f, PictureCues.HASH_WIDTH, PictureCues.HASH_HEIGHT, 0)
        assertTrue(Integer.bitCount(hash(a) xor hash(b)) <= 4)
        assertNotNull(hash(other))
        assertTrue(Integer.bitCount(hash(a) xor hash(other)) >= 8)
    }

    @Test
    fun `a picture in colour shrinks to gray cells, each the mean brightness of its pixels`() {
        // Given a 4 x 2 picture: its left half white, its right half pure red, green and blue
        val rgba = byteArrayOf(
            -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, -1, 0, -1, 0, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, -1, -1, 0, 0, -1, -1,
        )
        // Then shrunk to 2 x 1 the left cell is white and the right one the mean of red, green, blue and blue
        val gray = PictureCues.gray(rgba, 4, 2, 2, 1)
        assertEquals(255, gray[0].toInt() and 0xff)
        assertEquals((77 + 150 + 29 + 29) / 4, gray[1].toInt() and 0xff)
    }
}
