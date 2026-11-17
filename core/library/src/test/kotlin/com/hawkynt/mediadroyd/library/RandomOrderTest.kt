package com.hawkynt.mediadroyd.library

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomOrderTest {
    private val films = (1..12).map { "film $it" }

    @Test
    fun `every film plays once before any plays again, round after round`() {
        val order = RandomOrder(films, Random(7))
        repeat(5) {
            val round = order.nextRound()
            assertEquals(films.toSet(), round.toSet())
            assertEquals(films.size, round.size)
        }
    }

    @Test
    fun `a new round never starts with the film that just ended, and the order is not the library's`() {
        val order = RandomOrder(films, Random(3))
        var last = order.nextRound().last()
        repeat(200) {
            val round = order.nextRound()
            assertNotEquals(last, round.first())
            last = round.last()
        }
        assertTrue(RandomOrder(films, Random(11)).nextRound() != films)
    }

    @Test
    fun `one film plays on and on, none plays nothing`() {
        assertEquals(listOf("only"), RandomOrder(listOf("only"), Random(1)).nextRound())
        assertEquals(emptyList<String>(), RandomOrder(emptyList<String>(), Random(1)).nextRound())
    }
}
