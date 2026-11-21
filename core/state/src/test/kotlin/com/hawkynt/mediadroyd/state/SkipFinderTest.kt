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
        assertEquals(36_000.0, store.foundSkipFor(episodes[2])!!.introStartMs!!.toDouble(), 500.0)
        assertEquals(2_301_000.0, store.foundSkipFor(episodes[2])!!.creditsStartMs!!.toDouble(), 500.0)
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
    fun `a find an older finder made is looked at again, one of this finder's not (#559)`() {
        val store = StateStore(Memory())
        // Kept before finds carried the finder's version: an intro a second inside, credits a second late.
        val old = FoundSkip(37_000, 86_000, 2_303_000, creditsLooked = true)
        for (episode in episodes) store.saveFoundSkip(episode, old)
        assertEquals(4, finder(store).steps(episodes, 1).size)
        finder(store).around(episodes, 1)
        assertEquals(36_000.0, store.foundSkipFor(episodes[2])!!.introStartMs!!.toDouble(), 500.0)
        assertEquals(2_301_000.0, store.foundSkipFor(episodes[2])!!.creditsStartMs!!.toDouble(), 500.0)
        assertTrue(finder(store).steps(episodes, 1).isEmpty())
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
        // The openings' pictures not at all; the closings' only to see whether the credits begin earlier (#551).
        finder(StateStore(Memory())) { location, closing ->
            null.also { if (!closing) decoded += location }
        }.around(episodes, 0)
        assertTrue("openings decoded though the sound found the intro: $decoded", decoded.isEmpty())
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

    // A closing (from 2 000 s, a frame a second): a scene, then credits on black from [creditsAt] s into it for 64 s.
    private fun blackCredits(creditsAt: Int): PictureCues.Frames {
        val w = PictureCues.LOOK_WIDTH
        val h = PictureCues.LOOK_HEIGHT
        val scene = ByteArray(w * h) { (80 + it % w / 2).toByte() }
        val text = ByteArray(w * h) { if (it / w % 9 < 4 && it % 3 == 0 && it % w in 30..130) 230.toByte() else 14 }
        val frames = List(creditsAt) { scene } + List(364 - creditsAt) { text } + List(16) { scene }
        return PictureCues.Frames(w, h, 2_000_000, PictureCues.CLOSING_STEP_MS, frames)
    }

    @Test
    fun `credits heard late begin where their black and text frames running into them begin (#551)`() {
        // The shared music from 300 s into the closings; the credits on black from 296 s, over each one's own sound.
        val store = StateStore(Memory())
        finder(store) { _, closing -> if (closing) blackCredits(296) else null }.around(episodes, 1)
        assertEquals(2_296_000L, store.foundSkipFor(episodes[2])?.creditsStartMs)
        // Black and text frames long before the music, or ending before it, are the story's: the sound's start kept.
        for (pictures in listOf(blackCredits(240), null)) {
            val kept = StateStore(Memory())
            finder(kept) { _, closing -> if (closing) pictures else null }.around(episodes, 1)
            assertEquals(2_301_000.0, kept.foundSkipFor(episodes[2])!!.creditsStartMs!!.toDouble(), 500.0)
        }
    }

    @Test
    fun `a movie's credits are found from its own closing alone, its intro never looked for (#555)`() {
        val store = StateStore(Memory())
        val films = listOf("/films/a.mkv", "/films/b.mkv")
        val looked = ArrayList<String>()
        val finder = SkipFinder(
            store,
            pictures = { location, closing ->
                looked += "$location ${if (closing) "closing" else "opening"} pictures"
                if (closing) blackCredits(296) else null
            },
            print = { location, closing ->
                looked += "$location ${if (closing) "closing" else "opening"} sound"
                null
            },
        )
        val steps = finder.steps(films, 0, alone = true)
        assertEquals(listOf(SkipFinder.Part.CREDITS, SkipFinder.Part.CREDITS), steps.map { it.part })
        for (step in steps) finder.find(step)?.let { finder.keep(step, it) }
        assertEquals(2_296_000L, store.foundSkipFor(films[0])?.creditsStartMs)
        assertTrue(store.hasLookedForCredits(films[1]))
        assertTrue("an opening looked at: $looked", looked.none { "opening" in it })
        assertTrue(finder.steps(films, 0, alone = true).isEmpty())
        // One movie alone in its queue too.
        assertEquals(1, SkipFinder(StateStore(Memory())) { _, _ -> null }.steps(films.take(1), 0, alone = true).size)
    }

    // A closing (from 2 000 s, a frame a second) of the stretches given: scene or credits on black, seconds each.
    private fun stretches(vararg parts: Pair<Boolean, Int>): PictureCues.Frames {
        val w = PictureCues.LOOK_WIDTH
        val h = PictureCues.LOOK_HEIGHT
        val scene = ByteArray(w * h) { (80 + it % w / 2).toByte() }
        val text = ByteArray(w * h) { if (it / w % 9 < 4 && it % 3 == 0 && it % w in 30..130) 230.toByte() else 14 }
        val frames = parts.flatMap { (credits, seconds) -> List(seconds) { if (credits) text else scene } }
        return PictureCues.Frames(w, h, 2_000_000, PictureCues.CLOSING_STEP_MS, frames)
    }

    // From [fromMs] to [toMs] frame by frame (every 40 ms): scene, then credits at [edgeMs] — or the other way round.
    private fun around(fromMs: Long, toMs: Long, edgeMs: Long, creditsAfter: Boolean): PictureCues.Frames {
        val w = PictureCues.LOOK_WIDTH
        val h = PictureCues.LOOK_HEIGHT
        val scene = ByteArray(w * h) { (80 + it % w / 2).toByte() }
        val text = ByteArray(w * h) { if (it / w % 9 < 4 && it % 3 == 0 && it % w in 30..130) 230.toByte() else 14 }
        val frames = List(((toMs - fromMs) / 40).toInt()) { i ->
            if ((fromMs + i * 40 >= edgeMs) == creditsAfter) text else scene
        }
        return PictureCues.Frames(w, h, fromMs, 40, frames)
    }

    @Test
    fun `scenes hidden between credits play whole, the credits around them to the frame (#557)`() {
        // A movie: story, credits, a scene, credits, a scene, credits to the end — every edge 400 ms into its second.
        val store = StateStore(Memory())
        val film = "/films/a.mkv"
        val edges = mapOf(
            2_299_000L to (2_299_400L to true), 2_359_000L to (2_359_400L to false),
            2_389_000L to (2_389_400L to true), 2_419_000L to (2_419_400L to false),
            2_449_000L to (2_449_400L to true),
        )
        val finder = SkipFinder(
            store,
            pictures = { _, closing ->
                val parts = arrayOf(false to 300, true to 60, false to 30, true to 30, false to 30, true to 30)
                if (closing) stretches(*parts) else null
            },
            between = { _, fromMs, toMs, _ ->
                edges[fromMs]?.let { (edge, creditsAfter) -> around(fromMs, toMs, edge, creditsAfter) }
            },
            print = { _, _ -> null },
        )
        for (step in finder.steps(listOf(film), 0, alone = true)) finder.keep(step, requireNotNull(finder.find(step)))
        assertEquals(
            listOf(2_299_400L..2_359_400L, 2_389_400L..2_419_400L, 2_449_400L..2_480_000L),
            store.foundSkipFor(film)?.credits,
        )
    }

    @Test
    fun `credits over the picture split by a scene of each episode's own are two stretches by their music (#559)`() {
        val store = StateStore(Memory())
        val more = noise(40.0, 3)
        // Each closing (from 2 000 s): its story, the credits music, a scene of its own of 20 + 5 n s, more music.
        val finder = SkipFinder(store) { location, closing ->
            val n = episodes.indexOf(location) + 1
            if (closing) {
                val items = noise(300.0, 300 + n) + credits + noise(20.0 + 5 * n, 500 + n) + more
                IntroCredits.SoundPrint(items, 2_000_000)
            } else {
                null
            }
        }
        val step = finder.steps(episodes, 1).first { it.part == SkipFinder.Part.CREDITS }
        finder.keep(step, requireNotNull(finder.find(step)))
        val found = requireNotNull(store.foundSkipFor(episodes[1])?.credits)
        assertEquals(2, found.size)
        assertEquals(2_301_000.0, found[0].first.toDouble(), 500.0)
        assertEquals(2_391_000.0, found[1].first.toDouble(), 500.0)
    }

    // A closing at the text reader's size, its text kept (from 2 000 s): a scene, a dark stretch with text that reads
    // as credits where [creditsFirst] (else a title card), a scene, credits to the end.
    private fun readableClosing(creditsFirst: Boolean): PictureCues.Frames {
        val w = CreditText.WIDTH
        val h = CreditText.HEIGHT
        fun text(mark: Byte) = ByteArray(w * h) { i ->
            val x = i % w / 6
            val y = i / w / 6
            if (y % 9 < 4 && x % 3 == 0 && x in 30..130) 230.toByte() else 14
        }.also { it[0] = mark }
        val credits = text(1)
        val card = text(2)
        val scene = ByteArray(w * h) { (80 + it % w / 12).toByte() }
        val frames = sequence {
            repeat(300) { yield(scene) }
            repeat(60) { yield(if (creditsFirst) credits else card) }
            repeat(30) { yield(scene) }
            repeat(30) { yield(credits) }
        }
        return PictureCues.closing(2_000_000, 2_420_000, frames, keepText = true)
    }

    @Test
    fun `a dark stretch with story after it is credits only where its text reads as credits (#561)`() {
        for (creditsFirst in listOf(true, false)) {
            val store = StateStore(Memory())
            val closing = readableClosing(creditsFirst)
            val finder = SkipFinder(
                store,
                pictures = { _, isClosing -> if (isClosing) closing else null },
                recognise = { gray ->
                    val credit = CreditText.Line(100, 100, 300, 30, "Directed by")
                    if (gray[0] == 1.toByte()) listOf(credit) else emptyList()
                },
                print = { _, _ -> null },
            )
            for (step in finder.steps(listOf("/films/a.mkv"), 0, alone = true)) {
                finder.keep(step, requireNotNull(finder.find(step)))
            }
            val want = if (creditsFirst) {
                listOf(2_300_000L..2_360_000L, 2_390_000L..2_420_000L)
            } else {
                listOf(2_390_000L..2_420_000L)
            }
            assertEquals(want, store.foundSkipFor("/films/a.mkv")?.credits)
        }
    }

    @Test
    fun `credits heard by their music are split by the scene their pictures show between them (#557)`() {
        // The shared music from 300 s into the closings (60 s); the pictures: credits, a scene, credits again.
        val store = StateStore(Memory())
        val parts = arrayOf(false to 300, true to 25, false to 20, true to 35)
        finder(store) { _, closing -> if (closing) stretches(*parts) else null }.around(episodes, 1)
        assertEquals(listOf(2_300_000L..2_325_000L, 2_345_000L..2_380_000L), store.foundSkipFor(episodes[2])?.credits)
    }

    @Test
    fun `credits with a scene hidden after them are kept with their end (#513)`() {
        val store = StateStore(Memory())
        val w = PictureCues.LOOK_WIDTH
        val h = PictureCues.LOOK_HEIGHT
        val scene = ByteArray(w * h) { (80 + it % w / 2).toByte() }
        val text = ByteArray(w * h) { if (it / w % 9 < 4 && it % 3 == 0 && it % w in 30..130) 230.toByte() else 14 }
        val finder = SkipFinder(
            store,
            pictures = { _, closing ->
                val frames = List(400) { scene } + List(60) { text } + List(20) { scene }
                if (closing) PictureCues.Frames(w, h, 2_000_000, PictureCues.CLOSING_STEP_MS, frames) else null
            },
            print = { location, closing ->
                IntroCredits.SoundPrint(noise(600.0, location.hashCode() + if (closing) 1 else 0), 0)
            },
        )
        val step = finder.steps(episodes, 0).first { it.part == SkipFinder.Part.CREDITS }
        finder.keep(step, requireNotNull(finder.find(step)))
        val found = requireNotNull(store.foundSkipFor(episodes[0]))
        assertEquals(2_400_000L, found.creditsStartMs)
        assertEquals(2_460_000L, found.creditsEndMs)
        assertEquals(listOf(2_400_000L..2_460_000L), found.credits)
    }

    @Test
    fun `the closing decoded for its pictures is read for its text too, only the narrowing decodes again (#535)`() {
        val store = StateStore(Memory())
        val scene = ByteArray(CreditText.WIDTH * CreditText.HEIGHT) { (80 + it % CreditText.WIDTH / 12).toByte() }
        val titled = scene.copyOf().also { it[0] = 1 }
        val decoded = ArrayList<Long>()
        val finder = SkipFinder(
            store,
            pictures = { _, closing ->
                // Credits over the scene from 2 401 s to the end at 2 480 s; the closing where credits may begin.
                val from = 2_480_000 - IntroCredits.closingMs(2_480_000)
                val count = ((2_480_000 - from) / 1000).toInt()
                val frames = List(count) { if (from + it * 1000L >= 2_401_000) titled else scene }
                if (closing) PictureCues.closing(from, 2_480_000, frames.asSequence(), keepText = true) else null
            },
            text = { _, at -> at(2_480_000).map { time -> decoded += time; time to credits(time) } },
            recognise = { gray -> credits(if (gray[0] == 1.toByte()) 2_401_000 else 0) },
            print = { location, closing ->
                IntroCredits.SoundPrint(noise(600.0, location.hashCode() + if (closing) 1 else 0), 0)
            },
        )
        val step = finder.steps(episodes, 0).first { it.part == SkipFinder.Part.CREDITS }
        finder.keep(step, requireNotNull(finder.find(step)))
        assertEquals(2_401_000L, store.foundSkipFor(episodes[0])?.creditsStartMs)
        // Only the four seconds before the first credits read are decoded again.
        assertEquals(listOf(2_401_000L, 2_402_000L, 2_403_000L, 2_404_000L), decoded.sorted().distinct().take(4))
        assertTrue("decoded again: $decoded", decoded.none { it % 5000 == 0L })
    }

    private fun credits(time: Long) =
        if (time >= 2_401_000) listOf(CreditText.Line(100, 100, 300, 30, "Directed by")) else emptyList()
}
