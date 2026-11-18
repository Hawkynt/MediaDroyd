package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FastCoresTest {
    @Test
    fun `a phone's big cores are counted, its little ones left out`() {
        // A Snapdragon 865: four at 1.8 GHz, three at 2.42, one at 2.84.
        val clocks = listOf(1_804_800L, 1_804_800, 1_804_800, 1_804_800, 2_419_200, 2_419_200, 2_419_200, 2_841_600)
        assertEquals(4, FastCores.count(8) { clocks[it] })
    }

    @Test
    fun `alike cores count half, unreadable ones all but one`() {
        assertEquals(8, FastCores.count(16) { 4_500_000L })
        assertEquals(7, FastCores.count(8) { null })
        assertEquals(1, FastCores.count(1) { null })
    }
}
