package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProsodyTest {
    private val rate = 16_000

    // A voice-like sound: [hz] with three harmonics, [amplitude] (0 … 1), [seconds] long; [glide] semitones up over it.
    private fun voice(hz: Double, amplitude: Double, seconds: Double, glide: Double = 0.0): FloatArray {
        val n = (seconds * rate).toInt()
        var phase = 0.0
        return FloatArray(n) { i ->
            val f = hz * Math.pow(2.0, glide * i / n / 12)
            phase += 2 * PI * f / rate
            (amplitude * (sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase)) / 1.75).toFloat()
        }
    }

    @Test
    fun `a line's pitch, loudness, pace and movement are measured from its sound`() {
        val steady = Prosody.measure(voice(150.0, 0.3, 2.0), rate, letters = 30)
        assertEquals(150.0, steady.pitchHz, 3.0)
        assertEquals(15.0, steady.lettersPerSecond, 0.01)
        assertTrue(steady.pitchMovement < 0.5)
        val louder = Prosody.measure(voice(150.0, 0.6, 2.0), rate, letters = 30)
        assertEquals(6.0, louder.levelDb - steady.levelDb, 0.5)
        val gliding = Prosody.measure(voice(150.0, 0.3, 2.0, glide = 7.0), rate, letters = 30)
        assertTrue("${gliding.pitchMovement}", gliding.pitchMovement > 2.0)
        val silence = Prosody.measure(FloatArray(rate), rate, letters = 5)
        assertEquals(0.0, silence.pitchHz, 0.0)
    }

    @Test
    fun `against the film's usual speech a shout is louder, faster and higher, a murmur the other way`() {
        val film = Prosody.Baseline()
        repeat(20) { film.add(Prosody.Features(levelDb = -20.0, pitchHz = 150.0, lettersPerSecond = 14.0, pitchMovement = 2.0)) }
        val shout = film.delivery(Prosody.Features(-12.0, 210.0, 18.0, 5.0))
        assertTrue("$shout", shout.gainDb > 3 && shout.rate > 1.1 && shout.pitch > 1.5 && shout.liveliness > 1.1)
        val murmur = film.delivery(Prosody.Features(-30.0, 125.0, 10.0, 0.5))
        assertTrue("$murmur", murmur.gainDb < -3 && murmur.rate < 0.9 && murmur.pitch < -1 && murmur.liveliness < 1)
        val usual = film.delivery(Prosody.Features(-20.0, 150.0, 14.0, 2.0))
        assertEquals(Dub.Delivery(), usual)
        // Before the film's speech is known, every line its voice's own way.
        assertEquals(Dub.Delivery(), Prosody.Baseline().delivery(Prosody.Features(-5.0, 300.0, 25.0, 9.0)))
    }

    @Test
    fun `a text's own hint goes on top of the tone`() {
        val toned = Dub.Delivery(gainDb = 2f, rate = 1.05f, pitch = 1f, liveliness = 1.1f)
        val combined = Prosody.combine(toned, Dub.WHISPER)
        assertEquals(Dub.WHISPER.gainDb + 2f, combined.gainDb, 1e-6f)
        assertEquals(1f, combined.pitch, 1e-6f)
    }
}
