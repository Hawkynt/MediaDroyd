package com.hawkynt.mediadroyd.state

import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.NativeFingerprint
import com.hawkynt.mediadroyd.player.PictureCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SkipFinderTest {
    private class Memory(var text: String? = null) : StateFile {
        override fun read(): String? = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private fun items(seconds: Double) = (seconds / NativeFingerprint.ITEM_SECONDS).toInt()
    private fun noise(seconds: Double, seed: Int) = Random(seed).let { r -> IntArray(items(seconds)) { r.nextInt() } }

    private val theme = noise(50.0, 1)
    private val credits = noise(60.0, 2)

    // Episode n: a cold open of 10 * n seconds, the theme, its own story; its closing (from 2000 s) the credits music.
    private fun opening(n: Int) = noise(10.0 * n + 5, 100 + n) + theme + noise(400.0, 200 + n)
    private fun closing(n: Int) = noise(300.0, 300 + n) + credits + noise(20.0, 400 + n)

    private val asked = ArrayList<String>()
    private val episodes = (1..4).map { "/show/e$it.mkv" }
    private fun finder(
        store: StateStore,
        pictures: (String, Boolean) -> PictureCues.Frames? = { _, _ -> null },
    ) = SkipFinder(
        store,
        print = { location, closing ->
            asked += "$location ${if (closing) "closing" else "opening"}"
            val n = episodes.indexOf(location) + 1
            if (closing) IntroCredits.SoundPrint(closing(n), 2_000_000) else IntroCredits.SoundPrint(opening(n), 0)
        },
        pictures = pictures,
    )

    @Test
    fun `the episode playing and the next are looked at, each intro and credits kept in its own time`() {
        val store = StateStore(Memory())
        finder(store).around(episodes, 1)
        // e2 with e3, then e3 with e4: e2's and e3's known before e3 starts.
        for (n in listOf(2, 3, 4)) assertTrue(store.hasLookedForSkips(episodes[n - 1]))
        assertFalse(store.hasLookedForSkips(episodes[0]))
        assertNotNull(store.foundSkipFor(episodes[2])?.intro)
        assertEquals(37_000.0, store.foundSkipFor(episodes[2])!!.introStartMs!!.toDouble(), 500.0)
        assertEquals(2_302_000.0, store.foundSkipFor(episodes[2])!!.creditsStartMs!!.toDouble(), 500.0)
    }

    @Test
    fun `what was looked at already is not looked at again, and the last episode pairs with the one before`() {
        val store = StateStore(Memory())
        finder(store).around(episodes, 3)
        assertTrue(store.hasLookedForSkips(episodes[3]))
        assertTrue(store.hasLookedForSkips(episodes[2]))
        asked.clear()
        finder(store).around(episodes, 3)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a video whose sound cannot be read is not marked as looked at, and a lone video is left alone`() {
        val store = StateStore(Memory())
        SkipFinder(store) { _, _ -> null }.around(episodes, 0)
        assertFalse(store.hasLookedForSkips(episodes[0]))
        finder(store).around(listOf(episodes[0]), 0)
        assertFalse(store.hasLookedForSkips(episodes[0]))
        assertNull(store.foundSkipFor(episodes[0]))
    }

    @Test
    fun `the intro of the episode playing comes first, from the openings alone, its credits after`() {
        val store = StateStore(Memory())
        val finder = finder(store)
        val first = finder.steps(episodes, 1).first()
        assertEquals(SkipFinder.Part.INTRO, first.part)
        finder.keep(first, requireNotNull(finder.find(first)))
        assertTrue(asked.none { it.endsWith("closing") })
        assertNotNull(store.foundSkipFor(episodes[1])?.intro)
        assertFalse(store.hasLookedForCredits(episodes[1]))
        assertEquals(SkipFinder.Part.CREDITS, finder.steps(episodes, 1).first().part)
    }

    @Test
    fun `each opening and closing is decoded once, however many pairs it is in`() {
        finder(StateStore(Memory())).around(episodes, 1)
        assertEquals(asked.size, asked.toSet().size)
    }

    // Openings at the hash size whose pictures share an intro (the same shots from 20 s + 5 s * n for 40 s) while their
    // sound shares nothing; closings whose last minute is black with white strokes.
    private fun shots(seconds: Int, seed: Int): List<ByteArray> {
        val perSecond = (1000 / PictureCues.OPENING_STEP_MS).toInt()
        val random = Random(seed)
        val size = PictureCues.HASH_WIDTH * PictureCues.HASH_HEIGHT
        return List(seconds) { ByteArray(size) { (40 + random.nextInt(180)).toByte() } }
            .flatMap { shot -> List(perSecond) { shot } }
    }

    private fun pictureOpening(n: Int) = PictureCues.Frames(
        PictureCues.HASH_WIDTH, PictureCues.HASH_HEIGHT, 0, PictureCues.OPENING_STEP_MS,
        shots(20 + 5 * n, 500 + n) + shots(40, 7) + shots(200, 600 + n),
    )

    private fun pictureClosing(): PictureCues.Frames {
        val w = PictureCues.LOOK_WIDTH
        val h = PictureCues.LOOK_HEIGHT
        val scene = ByteArray(w * h) { (80 + it % w / 2).toByte() }
        val text = ByteArray(w * h) { if (it / w % 9 < 4 && it % 3 == 0 && it % w in 30..130) 230.toByte() else 14 }
        return PictureCues.Frames(w, h, 2_000_000, PictureCues.CLOSING_STEP_MS, List(400) { scene } + List(80) { text })
    }

    @Test
    fun `where the sound shares nothing the pictures are looked at, and only then`() {
        val store = StateStore(Memory())
        val decoded = ArrayList<String>()
        val finder = SkipFinder(
            store,
            print = { location, closing ->
                IntroCredits.SoundPrint(noise(600.0, location.hashCode() + if (closing) 1 else 0), 0)
            },
            pictures = { location, closing ->
                decoded += location
                if (closing) pictureClosing() else pictureOpening(episodes.indexOf(location) + 1)
            },
        )
        finder.around(episodes, 0)
        val found = requireNotNull(store.foundSkipFor(episodes[0]))
        assertEquals(26_000.0, found.introStartMs!!.toDouble(), 2500.0)
        assertEquals(2_400_000L, found.creditsStartMs)
        assertTrue(decoded.isNotEmpty())
        decoded.clear()
        finder(StateStore(Memory())) { location, _ -> null.also { decoded += location } }.around(episodes, 0)
        assertTrue("pictures decoded though the sound found it: $decoded", decoded.isEmpty())
    }

    @Test
    fun `credits over a scene are read by their text where neither sound nor black frames tell`() {
        val store = StateStore(Memory())
        val w = PictureCues.LOOK_WIDTH
        val h = PictureCues.LOOK_HEIGHT
        val scene = ByteArray(w * h) { (80 + it % w / 2).toByte() }
        val asked = ArrayList<Long>()
        val finder = SkipFinder(
            store,
            pictures = { _, closing ->
                val frames = List(480) { scene }
                if (closing) PictureCues.Frames(w, h, 2_000_000, PictureCues.CLOSING_STEP_MS, frames) else null
            },
            text = { _, at ->
                // Credits over the scene from 2 401 s to the end at 2 480 s.
                at(2_480_000).map { time ->
                    asked += time
                    val credit = CreditText.Line(100, 100, 300, 30, "Directed by")
                    time to if (time >= 2_401_000) listOf(credit) else emptyList()
                }
            },
            print = { location, closing ->
                IntroCredits.SoundPrint(noise(600.0, location.hashCode() + if (closing) 1 else 0), 0)
            },
        )
        val step = finder.steps(episodes, 0).first { it.part == SkipFinder.Part.CREDITS }
        finder.keep(step, requireNotNull(finder.find(step)))
        assertEquals(2_401_000L, store.foundSkipFor(episodes[0])?.creditsStartMs)
        // Every five seconds, then every second just before the first credits read.
        assertTrue(asked.count { it % 5000 == 0L } >= 90 && 2_401_000L in asked)
    }
}
