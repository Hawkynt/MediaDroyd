package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLookTest {

    @Test
    fun delayMovesInTenthsOfASecondWithinTenSecondsEitherWay() {
        val look = SubtitleLook()
        assertEquals(100, look.nudgeDelay(1).delayMs)
        assertEquals(-300, look.nudgeDelay(-3).delayMs)
        assertEquals(10_000, look.copy(delayMs = 9_950).nudgeDelay(1).delayMs)
        assertEquals(-10_000, look.copy(delayMs = -10_000).nudgeDelay(-1).delayMs)
        // A delay found by Sync beyond the buttons' reach can still be nudged back from there.
        assertEquals(24_900, look.copy(delayMs = 25_000).nudgeDelay(-1).delayMs)
    }

    @Test
    fun driftAddsToTheDelayAsTheFilmGoesOn() {
        val look = SubtitleLook(delayMs = 1000, drift = 25.0 / 24.0)
        // The cue timed at 600 s belongs at 625 s + 1 s: shown there, the delay is 26 s.
        assertEquals(26_000.0, look.delayAt(626_000).toDouble(), 2.0)
        assertEquals(1000, SubtitleLook(delayMs = 1000).delayAt(626_000))
    }

    @Test
    fun sizesScaleTheStandardSubtitleHeight() {
        // Media3's default is 5.33 % of the picture height.
        assertEquals(0.0533f, SubtitleLook(size = SubtitleLook.Size.NORMAL).textSizeFraction, 1e-4f)
        assertEquals(0.0533f * 1.6f, SubtitleLook(size = SubtitleLook.Size.EXTRA_LARGE).textSizeFraction, 1e-4f)
        assertEquals(0.0533f * 0.8f, SubtitleLook(size = SubtitleLook.Size.SMALL).textSizeFraction, 1e-4f)
    }

    @Test
    fun raisedSubtitlesLeaveTheBottomQuarterFree() {
        assertEquals(0.08f, SubtitleLook().bottomPaddingFraction, 1e-6f)
        assertEquals(0.25f, SubtitleLook(raised = true).bottomPaddingFraction, 1e-6f)
    }
}
