package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAheadTest {
    private val released = ArrayList<String>()

    // A film's packets: a picture every 40 ms (stream 0, a key frame every second), a sound every 32 ms (stream 1).
    private fun film(untilMs: Long): List<Triple<String, Long, Int>> =
        ((0 until untilMs step 40).map { Triple("v$it", it, 0) } + (0 until untilMs step 32).map { Triple("a$it", it, 1) })
            .sortedWith(compareBy({ it.second }, { it.third }))

    private fun buffer(aheadMs: Long = 60_000, maxBytes: Long = Long.MAX_VALUE, behindMs: Long = 5_000) =
        ReadAhead<String>(aheadMs, maxBytes, behindMs) { released += it }

    private fun ReadAhead<String>.fill(packets: List<Triple<String, Long, Int>>): Int {
        var n = 0
        for ((name, ms, stream) in packets) {
            if (!offer(name, ms, bytes = 1000, stream = stream, key = stream == 1 || ms % 1000 == 0L)) break
            n++
        }
        return n
    }

    private fun ReadAhead.Cursor<String>.take(n: Int) = List(n) { (next() as ReadAhead.Next.Item).item }

    @Test
    fun `it reads ahead a minute of the film past where playback is, and on as playback moves`() {
        val ahead = buffer()
        val packets = film(200_000)
        val first = ahead.fill(packets)
        assertTrue(packets[first].second in 60_000..60_100)
        val playback = ahead.cursor(0, holds = true)
        playback.take(packets.indexOfFirst { it.second >= 10_000 })
        assertTrue(ahead.fill(packets.drop(first)) > 0)
        assertTrue("${ahead.lastMs}", ahead.lastMs in 69_900..70_000)
    }

    @Test
    fun `the sound is tapped from the same packets, playback holds them, the tap does not`() {
        val ahead = buffer()
        ahead.fill(film(30_000))
        val playback = ahead.cursor(0, holds = true)
        val sound = ahead.cursor(5_000, streams = setOf(1))
        val heard = sound.take(3)
        assertEquals(listOf("a5024", "a5056", "a5088"), heard)
        // Playback moves on twenty seconds: what lies more than five behind it is given back, the tap's place too.
        playback.take(film(20_000).size)
        assertTrue("a5024" in released && "v16000" !in released)
        assertEquals(ReadAhead.Next.Lost, sound.next())
    }

    @Test
    fun `at the buffer's end the tap waits, at the film's end it ends`() {
        val ahead = buffer()
        ahead.fill(film(2_000))
        val sound = ahead.cursor(1_900, streams = setOf(1))
        sound.take(3)
        assertEquals(ReadAhead.Next.Wait, sound.next())
        ahead.end()
        assertEquals(ReadAhead.Next.End, sound.next())
    }

    @Test
    fun `memory caps the read ahead before the minute does`() {
        val ahead = buffer(maxBytes = 1000L * 100)
        assertEquals(100, ahead.fill(film(60_000)))
    }

    @Test
    fun `a seek inside what is buffered starts at the key frame before it, outside it reads anew`() {
        val ahead = buffer()
        ahead.fill(film(30_000))
        val playback = ahead.cursor(0, holds = true)
        assertTrue(playback.seek(12_500, keyStream = 0))
        assertEquals("v12000", playback.take(1).single())
        assertFalse(playback.seek(45_000, keyStream = 0))
        ahead.reset()
        assertTrue(released.size == film(30_000).size)
        assertEquals(ReadAhead.Next.Lost, playback.next())
        assertTrue(ahead.cursor(45_000, holds = true).next() == ReadAhead.Next.Wait)
        // Rejoined, playback reads what is read from the new place on.
        playback.rejoin()
        assertEquals(ReadAhead.Next.Wait, playback.next())
        ahead.fill(film(50_000).filter { it.second >= 45_000 })
        assertEquals("v45000", playback.take(1).single())
    }

    @Test
    fun `trailing what another reader buffers, it never refuses and keeps only the last stretch (#535)`() {
        val copies = ReadAhead<String>(aheadMs = 0, maxBytes = 1000L * 1000, behindMs = 0, trailMs = 10_000) {
            released += it
        }
        val sound = film(30_000).filter { it.third == 1 }
        for ((name, ms, stream) in sound) assertTrue(copies.offer(name, ms, 1000, stream, key = true))
        assertTrue("a19968" in released && "a20000" !in released)
        val tap = copies.cursor(25_000)
        assertEquals("a25024", tap.take(1).single())
        // A tap the trail passes loses its place.
        val behind = copies.cursor(20_000)
        for (ms in 30_000L until 40_000 step 32) copies.offer("a$ms", ms, 1000, 1, key = true)
        assertEquals(ReadAhead.Next.Lost, behind.next())
    }
}
