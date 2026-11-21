package com.hawkynt.mediadroyd.library

import com.hawkynt.mediadroyd.nfo.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipRulesTest {

    private val chapters = listOf(
        Chapter(0, 2000, "Chapter 1"),
        Chapter(2000, 5000, "Intro"),
        Chapter(5000, 120000, "Real start"),
        Chapter(120000, -1, "End credits"),
    )

    @Test
    fun introChapterEndIsTheSkipTarget() {
        assertEquals(5000L, SkipRules.introEndMs(chapters, null))
    }

    @Test
    fun nfoSpanIsUsedWhenNoChapterMatches() {
        assertEquals(130_000L, SkipRules.introEndMs(emptyList(), TimeRange(70000, 130000)))
        assertEquals(130_000L, SkipRules.introEndMs(listOf(Chapter(0, 2000, "Chapter 1")), TimeRange(70000, 130000)))
    }

    @Test
    fun chaptersWinOverNfoSpans() {
        assertEquals(5000L, SkipRules.introEndMs(chapters, TimeRange(70000, 130000)))
    }

    @Test
    fun creditsChapterStartIsTheJumpTarget() {
        assertEquals(120_000L, SkipRules.creditsStartMs(chapters, null))
    }

    @Test
    fun nfoCreditsSpanIsUsedWhenNoChapterMatches() {
        assertEquals(1180_000L, SkipRules.creditsStartMs(emptyList(), TimeRange(1180000, 1260000)))
    }

    @Test
    fun germanChapterTitlesMatchToo() {
        val german = listOf(Chapter(0, 4000, "Vorspann"), Chapter(4000, 90000, "Hauptfilm"), Chapter(90000, -1, "Abspann"))
        assertEquals(4000L, SkipRules.introEndMs(german, null))
        assertEquals(90_000L, SkipRules.creditsStartMs(german, null))
    }

    @Test
    fun nothingAttributedMeansNoSkip() {
        assertEquals(null, SkipRules.introEndMs(emptyList(), null))
        assertEquals(null, SkipRules.creditsStartMs(chapters.take(3), null))
    }

    @Test
    fun introSpanComesFromTheChapterOrTheEdl() {
        assertEquals(2000L..5000L, SkipRules.introSpanMs(chapters, null))
        assertEquals(70_000L..130_000L, SkipRules.introSpanMs(emptyList(), TimeRange(70000, 130000)))
        assertEquals(null, SkipRules.introSpanMs(emptyList(), null))
    }

    @Test
    fun playbackInsideTheIntroJumpsToItsEnd() {
        val intro = 70_000L..130_000L
        assertEquals(130_000L, SkipRules.skipTarget(70_500L, intro))
        assertEquals(60_000L, SkipRules.skipTarget(0L, 0L..60_000L))
        assertEquals(null, SkipRules.skipTarget(65_000L, intro))
        // The last second is left alone, or a seek that lands just short would loop.
        assertEquals(null, SkipRules.skipTarget(129_500L, intro))
        assertEquals(null, SkipRules.skipTarget(200_000L, intro))
        assertEquals(null, SkipRules.skipTarget(70_500L, null))
    }

    @Test
    fun aColdOpenPlaysUntilTheIntroBegins() {
        assertEquals(null, SkipRules.skipTarget(0L, 70_000L..130_000L))
    }

    // A film of 2 000 s: credits from 1 800 s to 1 900 s, a scene hidden after them, credits again to the end (#513).
    private val split = listOf(
        Chapter(0, 1_800_000, "Film"),
        Chapter(1_800_000, 1_900_000, "End credits"),
        Chapter(1_900_000, 1_950_000, "Post-credits scene"),
        Chapter(1_950_000, 2_000_000, "Credits (continued)"),
    )

    @Test
    fun creditsChaptersAreStretchesEachWithItsEnd() {
        assertEquals(listOf(1_800_000L..1_900_000L, 1_950_000L..2_000_000L), SkipRules.creditsSpans(split, null))
        // A credits chapter without an end runs to the end of the file.
        assertEquals(listOf(120_000L..Long.MAX_VALUE), SkipRules.creditsSpans(chapters, null))
        val nfo = TimeRange(1_180_000, 1_260_000)
        assertEquals(listOf(1_180_000L..1_260_000L), SkipRules.creditsSpans(emptyList(), nfo))
    }

    @Test
    fun creditsBeforeAHiddenSceneAreJumpedOverAndCreditsToTheEndEndTheVideo() {
        val spans = SkipRules.creditsSpans(split, null)
        assertEquals(SkipRules.Credits.Jump(1_900_000), SkipRules.creditsAction(1_800_500, spans, 2_000_000))
        assertEquals(null, SkipRules.creditsAction(1_920_000, spans, 2_000_000))
        assertEquals(SkipRules.Credits.End, SkipRules.creditsAction(1_950_500, spans, 2_000_000))
        assertEquals(null, SkipRules.creditsAction(1_000_000, spans, 2_000_000))
        // Credits that stop a few seconds short of the end leave nothing worth keeping: the video ends.
        assertEquals(SkipRules.Credits.End, SkipRules.creditsAction(1_901_000, listOf(1_900_000L..1_995_000L), 2_000_000))
        // A stretch whose end is unknown runs to the end.
        assertEquals(SkipRules.Credits.End, SkipRules.creditsAction(130_000, listOf(120_000L..Long.MAX_VALUE), 0))
    }

    @Test
    fun theWaitEndsWhereTheNextIntroOrCreditsBeginSoNotAFrameOfThemShows() {
        val intro = 60_000L..120_000L
        val credits = listOf(1_800_000L..1_900_000L, 1_950_000L..2_000_000L)
        // 400 ms before the intro, at normal speed and at 2x: woken just as it begins (#553).
        assertEquals(400L, SkipRules.waitMs(59_600, intro, credits, speed = 1f, tickMs = 500))
        assertEquals(200L, SkipRules.waitMs(59_600, intro, credits, speed = 2f, tickMs = 500))
        // Nothing near: the usual tick; the credits' next stretch counts once the intro is behind.
        assertEquals(500L, SkipRules.waitMs(1_000_000, intro, credits, speed = 1f, tickMs = 500))
        assertEquals(100L, SkipRules.waitMs(1_799_900, intro, credits, speed = 1f, tickMs = 500))
        assertEquals(500L, SkipRules.waitMs(1_000_000, null, emptyList(), speed = 1f, tickMs = 500))
        // At or a hair before an edge: a short wait, never none (the clock is checked again at once).
        assertEquals(SkipRules.MIN_WAIT_MS, SkipRules.waitMs(59_999, intro, credits, speed = 1f, tickMs = 500))
    }

    @Test
    fun anIntroCountsOnlyNearTheStartAndCreditsOnlyNearTheEnd() {
        val episode = 45 * 60_000L
        // The intro where it begins in the opening: the first quarter, at most ten minutes (#554).
        assertEquals(400_000L, SkipRules.skipTarget(310_000, 300_000L..400_000L, episode))
        assertNull(SkipRules.skipTarget(1_210_000, 1_200_000L..1_300_000L, episode))
        assertNull(SkipRules.skipTarget(11 * 60_000L + 5_000, 11 * 60_000L..12 * 60_000L, 3 * 3_600_000L))
        // Credits where they begin in the last quarter and the last twenty minutes.
        assertEquals(SkipRules.Credits.End, SkipRules.creditsAction(2_600_000, listOf(2_590_000L..episode), episode))
        assertNull(SkipRules.creditsAction(1_210_000, listOf(1_200_000L..1_300_000L), episode))
        val film = 3 * 3_600_000L
        assertNull(SkipRules.creditsAction(film - 25 * 60_000L, listOf(film - 30 * 60_000L..film), film))
        assertEquals(SkipRules.Credits.End, SkipRules.creditsAction(film - 60_000, listOf(film - 900_000..film), film))
        // A length not known yet: what is known counts.
        assertEquals(400_000L, SkipRules.skipTarget(310_000, 300_000L..400_000L, 0))
        // Nor is it waited for where it is out of place.
        assertEquals(500L, SkipRules.waitMs(1_199_900, 1_200_000L..1_300_000L, emptyList(), 1f, 500, episode))
    }
}
