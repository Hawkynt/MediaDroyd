package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.nfo.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class IntroCreditsTest {
    private val step = NativeFingerprint.ITEM_SECONDS
    private fun items(seconds: Double) = (seconds / step).toInt()

    // A print of [seconds] of sound nobody else has.
    private fun noise(seconds: Double, seed: Int) = Random(seed).let { r -> IntArray(items(seconds)) { r.nextInt() } }

    // [print] as a re-encode leaves it: a bit flipped in one item of ten.
    private fun reencoded(print: IntArray, seed: Int) = Random(seed).let { r ->
        IntArray(print.size) { i -> if (r.nextInt(10) == 0) print[i] xor (1 shl r.nextInt(32)) else print[i] }
    }

    // Within three items (under 0.4 s): Chromaprint's smoothing blurs a segment's edges by a couple of items.
    private fun near(wantSeconds: Double, gotMs: Long) =
        assertEquals("at $gotMs ms", wantSeconds * 1000, gotMs.toDouble(), 3 * step * 1000)

    private val intro = noise(55.0, 1)

    @Test
    fun `the intro both openings share is found where it lies in each, after a cold open of its own length`() {
        val first = noise(90.0, 2) + intro + noise(400.0, 3)
        val second = noise(20.0, 4) + reencoded(intro, 5) + noise(500.0, 6)
        val found = requireNotNull(IntroCredits.intro(first, second))
        // Random items have no lookahead: each edge moved IntroCredits.LOOKAHEAD_MS later, and no margin (#553).
        near(91.0, found.first.startMs)
        near(146.0, found.first.endMs)
        near(21.0, found.second.startMs)
        near(76.0, found.second.endMs)
    }

    @Test
    fun `too short or too long a shared stretch is no intro, nor is nothing shared`() {
        val jingle = noise(6.0, 7)
        assertNull(IntroCredits.intro(noise(30.0, 8) + jingle + noise(300.0, 9), jingle + noise(300.0, 10)))
        val recap = noise(240.0, 11)
        assertNull(IntroCredits.intro(recap + noise(200.0, 12), recap + noise(200.0, 13)))
        assertNull(IntroCredits.intro(noise(300.0, 14), noise(300.0, 15)))
    }

    @Test
    fun `the credits start where the closing music both share begins, in each episode's own time`() {
        val credits = noise(70.0, 16)
        // The closings analysed: their last eight minutes, starting 1000 s and 1200 s into the episodes.
        val first = noise(400.0, 17) + credits + noise(10.0, 18)
        val second = noise(380.0, 19) + reencoded(credits, 20) + noise(30.0, 21)
        val found = requireNotNull(IntroCredits.credits(first, 1_000_000, second, 1_200_000))
        near(1401.0, found.first)
        near(1581.0, found.second)
        assertNull(IntroCredits.credits(noise(480.0, 22), 0, noise(480.0, 23), 0))
    }

    @Test
    fun `the credits end where the shared closing music ends, a scene of each episode's own after it`() {
        val credits = noise(70.0, 16)
        val first = noise(400.0, 17) + credits + noise(10.0, 18)
        val second = noise(380.0, 19) + reencoded(credits, 20) + noise(30.0, 21)
        val (a, b) = requireNotNull(IntroCredits.creditsSpans(first, 1_000_000, second, 1_200_000))
        near(1401.0, a.startMs)
        near(1470.0, a.endMs)
        near(1581.0, b.startMs)
        near(1650.0, b.endMs)
    }

    @Test
    fun `credits split by a scene of each episode's own are two stretches, each in its own time (#559)`() {
        val before = noise(40.0, 40)
        val after = noise(45.0, 41)
        // Closings from 1 000 s and 1 200 s: their story, credits, a scene of their own (30 s, 20 s), credits again.
        val first = noise(300.0, 42) + before + noise(30.0, 43) + after + noise(10.0, 44)
        val second = noise(320.0, 45) + reencoded(before, 46) + noise(20.0, 47) + reencoded(after, 48) +
            noise(30.0, 49)
        val stretches = IntroCredits.creditsStretches(first, 1_000_000, second, 1_200_000)
        assertEquals(2, stretches.size)
        near(1301.0, stretches[0].first.startMs)
        near(1340.0, stretches[0].first.endMs)
        near(1371.0, stretches[1].first.startMs)
        near(1521.0, stretches[0].second.startMs)
        near(1581.0, stretches[1].second.startMs)
        // Nothing shared: none; one stretch only: as creditsSpans finds it.
        assertTrue(IntroCredits.creditsStretches(noise(480.0, 50), 0, noise(480.0, 51), 0).isEmpty())
        val one = noise(400.0, 17) + before + noise(10.0, 18)
        val other = noise(380.0, 19) + before + noise(30.0, 21)
        assertEquals(1, IntroCredits.creditsStretches(one, 0, other, 0).size)
    }

    @Test
    fun `an episode's intro is the longest any of its neighbours shares with it`() {
        val own = noise(30.0, 24) + intro + noise(300.0, 25)
        val neighbours = listOf(noise(400.0, 26), noise(10.0, 27) + reencoded(intro, 28) + noise(300.0, 29))
        val found: TimeRange? = IntroCredits.introAmong(own, neighbours)
        near(31.0, found!!.startMs)
        assertEquals(true, abs(found.endMs - 86_000) < 1000)
    }

    @Test
    fun `the intro is looked for in an episode's first quarter, at most ten minutes, credits kept out`() {
        assertEquals(10 * 60_000L, IntroCredits.openingMs(45 * 60_000L))
        assertEquals(5 * 60_000L + 30_000L, IntroCredits.openingMs(22 * 60_000L))
        assertEquals(IntroCredits.OPENING_MS, IntroCredits.openingMs(0))
    }

    // [seconds] of steady sound — rain, hum, room tone, noise — whose items all resemble one another: a common pattern
    // with a few bits of its own each.
    private fun steady(seconds: Double, seed: Int) = Random(seed).let { r ->
        IntArray(items(seconds)) { 0x5a3c96e1 xor List(3) { 1 shl r.nextInt(32) }.reduce(Int::xor) }
    }

    @Test
    fun `steady sound that resembles itself wherever it is lined up is no intro and no credits`() {
        val first = noise(20.0, 30) + steady(100.0, 31) + noise(400.0, 32)
        val second = noise(35.0, 33) + steady(100.0, 34) + noise(400.0, 35)
        assertNull(IntroCredits.intro(first, second))
        assertNull(IntroCredits.credits(noise(200.0, 36) + steady(60.0, 37), 0, noise(220.0, 38) + steady(60.0, 39), 0))
    }
}
