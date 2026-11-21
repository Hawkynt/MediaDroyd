package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditTextTest {
    // The ids the recogniser gives for [text]: 0 is CTC's blank, 1.. the alphabet, then the space.
    private fun ids(text: String) = text.map { c ->
        if (c == ' ') CreditText.ALPHABET.size + 1 else CreditText.ALPHABET.indexOf(c.toString()) + 1
    }.toIntArray()

    private fun line(text: String, y: Int = 100) = CreditText.Line(10, y, 300, 20, text)

    @Test
    fun `the recogniser's ids read as the model's own alphabet, the space after it`() {
        assertEquals(836, CreditText.ALPHABET.size)
        assertEquals("Directed by Jöhn Smith", CreditText.decode(ids("Directed by Jöhn Smith")))
    }

    @Test
    fun `a frame reads like credits by their words, or by a roll of names`() {
        assertTrue(CreditText.isCredits(listOf(line("Directed by"), line("Kim Lee", 130))))
        assertTrue(CreditText.isCredits(listOf(line("Regie"))))
        assertTrue(CreditText.isCredits(listOf(line("Executive Producers"))))
        val names = listOf("Anna Berg", "Tom Hale", "Mia Roth", "Sam Kerr", "Ole Brun")
            .mapIndexed { i, n -> line(n, i * 30) }
        assertTrue(CreditText.isCredits(names))
    }

    @Test
    fun `a subtitle, a sign or a title in the story is no credits`() {
        assertFalse(CreditText.isCredits(emptyList()))
        assertFalse(CreditText.isCredits(listOf(line("I told you we would be late again!", 600))))
        assertFalse(CreditText.isCredits(listOf(line("POLICE"), line("Main Street", 200))))
        assertFalse(CreditText.isCredits(listOf(line("Previously on"))))
    }

    @Test
    fun `the credits begin at the first frame of a run of credit frames that reaches the end`() {
        val reads = listOf(600_000L to false, 605_000L to false, 610_000L to true, 615_000L to true, 620_000L to false,
            625_000L to true, 630_000L to true)
        assertEquals(610_000L, CreditText.creditsStartMs(reads, stepMs = 5000))
        assertNull(CreditText.creditsStartMs(reads.map { it.first to false }, stepMs = 5000))
        // Text with minutes of the story after it is a scene's text, not the end credits.
        val early = listOf(600_000L to true, 605_000L to true, 610_000L to true) +
            (1..50).map { 610_000L + it * 5000 to false }
        assertNull(CreditText.creditsStartMs(early, stepMs = 5000))
    }

    @Test
    fun `credits a scene is hidden after keep their end (#513)`() {
        val reads = (0..9).map { 600_000L + it * 5000 to (it in 2..6) } // credits 610..630 s, a scene 635..645 s
        assertEquals(
            listOf(com.hawkynt.mediadroyd.nfo.TimeRange(610_000, 635_000)),
            CreditText.creditsSpans(reads, stepMs = 5000),
        )
    }

    @Test
    fun `credits over the picture around a hidden scene are narrowed to the second at each edge (#557)`() {
        // Credits 2 401..2 443 s, a scene 2 443..2 462 s, credits again from 2 462 s to the end at 2 480 s.
        val credit = listOf(CreditText.Line(100, 100, 300, 30, "Directed by"))
        fun shows(time: Long) = time in 2_401_000 until 2_443_000 || time >= 2_462_000
        val spans = CreditText.findSpans { at ->
            at(2_480_000).map { time -> time to if (shows(time)) credit else emptyList() }
        }
        assertEquals(
            listOf(
                com.hawkynt.mediadroyd.nfo.TimeRange(2_401_000, 2_443_000),
                com.hawkynt.mediadroyd.nfo.TimeRange(2_462_000, 2_480_000),
            ),
            spans,
        )
    }

    @Test
    fun `a short scene inside credits read by their text splits them, a single missed read does not (#559)`() {
        val credit = listOf(CreditText.Line(100, 100, 300, 30, "Directed by"))
        fun spans(shows: (Long) -> Boolean) = CreditText.findSpans { at ->
            at(2_480_000).map { time -> time to if (shows(time)) credit else emptyList() }
        }
        // A six-second scene at 2 431..2 437 s: only the read at 2 435 s falls into it.
        assertEquals(
            listOf(
                com.hawkynt.mediadroyd.nfo.TimeRange(2_401_000, 2_431_000),
                com.hawkynt.mediadroyd.nfo.TimeRange(2_437_000, 2_480_000),
            ),
            spans { it >= 2_401_000 && it !in 2_431_000 until 2_437_000 },
        )
        // The reader missing the credits at 2 435 s alone: one stretch still.
        assertEquals(
            listOf(com.hawkynt.mediadroyd.nfo.TimeRange(2_401_000, 2_480_000)),
            spans { it >= 2_401_000 && it != 2_435_000L },
        )
    }

    @Test
    fun `the native reader's flat result reads as lines, each box with its text`() {
        val flat = intArrayOf(10, 20, 300, 40) + intArrayOf(3) + ids("Cut") + intArrayOf(5, 80, 120, 30, 0)
        assertEquals(
            listOf(CreditText.Line(10, 20, 300, 40, "Cut"), CreditText.Line(5, 80, 120, 30, "")),
            CreditText.lines(flat),
        )
    }

    @Test
    fun `a credit phrase read with a slip still counts, a short word inside another does not`() {
        assertTrue(CreditText.isCredits(listOf(line("Producediby"), line("Tom Hale", 130))))
        assertTrue(CreditText.isCredits(listOf(line("PiMüsicbyy"))))
        assertTrue(CreditText.isCredits(listOf(line("Directed.by"))))
        assertFalse(CreditText.isCredits(listOf(line("Broadcast news"))))
        assertFalse(CreditText.isCredits(listOf(line("Kameraden"))))
    }
}
