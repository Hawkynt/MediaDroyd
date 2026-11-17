package com.hawkynt.mediadroyd.library

import kotlin.random.Random

/**
 * Films in random order, round after round, for playing on and on (#512): each round is all of [items] shuffled, so
 * every one plays once before any plays again, and a round never starts with the one the round before ended with.
 */
class RandomOrder<T>(private val items: List<T>, private val random: Random = Random.Default) {
    private var last: T? = null

    /** The next round: every item once, in a fresh random order. */
    fun nextRound(): List<T> {
        val round = items.shuffled(random).toMutableList()
        if (round.size > 1 && round.first() == last) {
            // Swapped with one at random from the rest, so the end of one round is not heard twice in a row.
            val with = 1 + random.nextInt(round.size - 1)
            round[0] = round[with].also { round[with] = round[0] }
        }
        last = round.lastOrNull()
        return round
    }
}
