package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MarianTokenizerTest {
    // A tiny Unigram vocabulary: </s> 0, <unk> 1, then pieces with their log-probabilities.
    private val tokenizer = MarianTokenizer(
        listOf(
            "</s>" to 0.0, "<unk>" to 0.0, "▁the" to -2.0, "▁house" to -6.0, "▁ho" to -5.0, "use" to -5.0,
            "▁" to -3.0, "h" to -8.0, "o" to -8.0, "u" to -8.0, "s" to -8.0, "e" to -8.0, "▁is" to -3.0, "." to -2.5,
            "<pad>" to 0.0,
        ),
        unk = 1,
    )

    @Test
    fun `text becomes the most likely pieces, word by word, ended by the end of sentence`() {
        // "▁house" (-6) beats "▁ho" + "use" (-10); the full stop is a piece of its own.
        assertEquals(listOf(2, 3, 12, 13, 0), tokenizer.encode("the house is."))
    }

    @Test
    fun `a letter no piece covers is unknown`() {
        assertEquals(listOf(2, 6, 1, 0), tokenizer.encode("the ü"))
    }

    @Test
    fun `pieces become text again, the word marks as spaces, specials dropped`() {
        assertEquals("the house is.", tokenizer.decode(listOf(2, 3, 12, 13, 0)))
    }
}
