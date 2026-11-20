package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmSoundTest {
    private val rate = FilmSound.RATE

    // A ten-minute film: speech (a 440 Hz tone) in its even seconds' first half, quiet hiss otherwise — the sample at
    // absolute index i known, so every slice can be checked.
    private fun sample(i: Long): Float {
        val ms = i * 1000 / rate
        val speaking = (ms / 1000) % 2 == 0L && ms % 1000 < 500
        return if (speaking) (0.3 * sin(2 * PI * 440 * i / rate)).toFloat() else ((i * 7919 % 13) - 6) / 3000f
    }

    private var opened = 0
    private var decoded = 0L
    private val shared = ArrayList<Boolean>()

    // The platform's stream: chunks of a tenth of a second from where it is opened.
    private fun open(fromMs: Long, near: Boolean): FilmSound.Stream {
        opened++
        shared += near
        return object : FilmSound.Stream {
            var at = fromMs * rate / 1000
            override fun read(): FloatArray? {
                if (at >= 600L * rate) return null
                val chunk = FloatArray(rate / 10) { sample(at + it) }
                at += chunk.size
                decoded += chunk.size
                return chunk
            }
            override fun close() {}
        }
    }

    @Test
    fun `a stretch is decoded once, whoever asks for it`() {
        val sound = FilmSound(::open, durationMs = 600_000)
        val first = requireNotNull(sound.samples(10_000, 40_000))
        assertEquals(30 * rate, first.size)
        assertEquals(sample(10L * rate), first[0], 1e-4f)
        assertEquals(sample(25L * rate + 123), first[15 * rate + 123], 1e-4f)
        val before = decoded
        // The same stretch again, and a part of it: nothing decoded.
        sound.samples(10_000, 40_000)
        sound.samples(20_000, 30_000)
        assertEquals(before, decoded)
    }

    @Test
    fun `a film ending inside a second is not opened again and again at its end (#540)`() {
        var opens = 0
        val endSample = 425_273L * rate / 1000
        val sound = FilmSound({ fromMs, _ ->
            opens++
            object : FilmSound.Stream {
                var at = fromMs * rate / 1000
                override fun read(): FloatArray? {
                    if (at >= endSample) return null
                    val chunk = FloatArray(minOf(rate / 10L, endSample - at).toInt()) { 0.1f }
                    at += chunk.size
                    return chunk
                }
                override fun close() {}
            }
        }, durationMs = 425_273)
        assertEquals(endSample - 420L * rate, requireNotNull(sound.samples(420_000, 480_000)).size.toLong())
        repeat(5) { sound.samples(425_000, 485_000) }
        assertEquals(1, opens)
    }

    @Test
    fun `reading on continues the stream open, a seek opens one where it lands`() {
        val sound = FilmSound(::open, durationMs = 600_000)
        sound.samples(0, 30_000)
        sound.samples(30_000, 60_000)
        assertEquals(1, opened)
        sound.samples(300_000, 330_000)
        assertEquals(2, opened)
    }

    @Test
    fun `the speech is found in what is decoded, nothing where it is not yet`() {
        val sound = FilmSound(::open, durationMs = 600_000)
        sound.samples(0, 20_000)
        val speech = sound.speech()
        fun at(ms: Int) = speech[ms / 10]
        assertTrue(at(200) && at(2200) && at(4300))
        assertTrue(!at(700) && !at(1500) && !at(3800))
        // Beyond what is decoded: no speech known.
        assertTrue(!at(100_200))
        sound.decodeAll { }
        assertTrue(sound.speech()[100_200 / 10])
    }

    @Test
    fun `the whole film for Sync is read on a stream of its own, never the one shared with playback`() {
        val sound = FilmSound(::open, durationMs = 600_000)
        sound.samples(0, 10_000)
        sound.decodeAll { }
        assertEquals(listOf(true, false), shared)
    }

    @Test
    fun `a read waiting for playback to move on holds up neither the speech found nor what is decoded already`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val sound = FilmSound({ from, near ->
            val inner = open(from, near)
            object : FilmSound.Stream {
                override fun read(): FloatArray? {
                    // Past 20 s the shared stream waits, as at the edge of the minute playback reads ahead.
                    if (near && decoded >= 20L * rate) gate.await()
                    return inner.read()
                }
                override fun close() = inner.close()
            }
        }, durationMs = 600_000)
        sound.samples(0, 10_000)
        val waiting = Thread { sound.samples(10_000, 40_000) }.apply {
            isDaemon = true
            start()
        }
        Thread.sleep(200)
        val started = System.nanoTime()
        sound.speech()
        assertEquals(10 * rate, sound.samples(0, 10_000)!!.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
        gate.countDown()
        waiting.join(5_000)
    }
}
