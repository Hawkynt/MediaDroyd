package com.hawkynt.mediadroyd.player

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.math.abs

/** The original voices (#533): the engines and their licences, the references, and the local helper's client. */
class VoiceClonesTest {

    @Test
    fun `every engine names its licence, XTTS-v2 as for non-commercial use only`() {
        assertEquals(3, VoiceClones.Engine.entries.size)
        assertFalse(VoiceClones.Engine.XTTS.commercialUse)
        assertTrue(VoiceClones.Engine.XTTS.licence.contains("non-commercial"))
        assertTrue(VoiceClones.Engine.OPENVOICE.commercialUse && VoiceClones.Engine.GPT_SOVITS.commercialUse)
        assertEquals(VoiceClones.Engine.GPT_SOVITS, VoiceClones.Engine.of("gpt-sovits"))
        assertEquals(VoiceClones.Engine.OPENVOICE, VoiceClones.Engine.of("nonsense"))
    }

    @Test
    fun `a person's reference is their longest clear lines, enough of them and not too much`() {
        val spans = listOf(0L to 900L, 1000L to 4000L, 5000L to 20000L, 21000L to 25000L, 26000L to 28000L)
        val chosen = VoiceClones.reference(spans)
        // Too short (under 1.5 s) and too long (over 12 s) left out; longest first until six seconds are there.
        assertEquals(listOf(21000L to 25000L, 1000L to 4000L), chosen)
        assertNull(VoiceClones.reference(listOf(0L to 3000L)))
    }

    @Test
    fun `a WAV is written and read back, any rate brought to the speaker's`() {
        val samples = FloatArray(1600) { (0.5 * kotlin.math.sin(it / 10.0)).toFloat() }
        val wav = VoiceClones.wav(samples, 16000)
        val (back, rate) = VoiceClones.readWav(wav)!!
        assertEquals(16000, rate)
        assertEquals(samples.size, back.size)
        assertTrue(samples.indices.all { abs(samples[it] - back[it]) < 1e-3f })
        val up = VoiceClones.resample(back, 16000, 24000)
        assertEquals(2400, up.size)
        assertNull(VoiceClones.readWav(ByteArray(10)))
    }

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    // A helper that answers /health and speaks one second of a 24 kHz tone, recording what it was asked.
    private fun helper(cuda: Boolean = true): Pair<String, MutableList<String>> {
        val asked = mutableListOf<String>()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/health") { ex ->
            val body = """{"cuda": $cuda, "engines": {"openvoice": true, "gpt-sovits": false, "xtts": true}}"""
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        s.createContext("/speak") { ex ->
            asked += String(ex.requestBody.readBytes())
            val wav = VoiceClones.wav(FloatArray(24000) { 0.25f }, 24000)
            ex.responseHeaders.add("Content-Type", "audio/wav")
            ex.sendResponseHeaders(200, wav.size.toLong())
            ex.responseBody.use { it.write(wav) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}" to asked
    }

    @Test
    fun `the helper says which engines it has and whether it has a graphics card`() {
        val (address, _) = helper()
        val health = VoiceClones.Client(address).health()!!
        assertTrue(health.cuda)
        assertEquals(setOf(VoiceClones.Engine.OPENVOICE, VoiceClones.Engine.XTTS), health.engines)
        assertNull(VoiceClones.Client("http://127.0.0.1:1").health())
    }

    @Test
    fun `a line is spoken by the helper in the person's voice, at the speaker's rate`() {
        val (address, asked) = helper()
        val speaker = VoiceClones.Speaker(
            VoiceClones.Client(address), VoiceClones.Engine.XTTS, listOf("/tmp/ref-1.wav"), "de", "en",
        )
        val pcm = speaker.speak("Hallo Welt", lengthScale = 0.8f)
        assertNotNull(pcm)
        assertEquals(speaker.sampleRate, VoiceClones.RATE)
        assertEquals(VoiceClones.RATE, pcm!!.size)
        val body = asked.single()
        assertTrue(body, body.contains("\"engine\":\"xtts\"") && body.contains("\"text\":\"Hallo Welt\""))
        assertTrue(body, body.contains("\"language\":\"de\"") && body.contains("/tmp/ref-1.wav"))
        assertTrue(body, body.contains("\"speed\":1.25"))
    }

    @Test
    fun `nothing is ever sent beyond this computer`() {
        assertThrows(IllegalArgumentException::class.java) { VoiceClones.Client("http://192.168.1.5:9890") }
        assertThrows(IllegalArgumentException::class.java) { VoiceClones.Client("https://clone.example.org") }
        VoiceClones.Client("http://localhost:9890")
        VoiceClones.Client("http://[::1]:9890")
    }

    @Test
    fun `a line the imitated voice cannot speak is spoken by the usual voice, at the imitated voice's rate`() {
        val silent = object : Dub.LineSpeaker {
            override val sampleRate = VoiceClones.RATE
            override fun speak(text: String, lengthScale: Float, speaker: Int, liveliness: Float): FloatArray? = null
        }
        val piper = object : Dub.LineSpeaker {
            override val sampleRate = 22050
            override fun speak(text: String, lengthScale: Float, speaker: Int, liveliness: Float) = FloatArray(22050)
        }
        val pcm = VoiceClones.WithFallback(silent, piper).speak("Hallo", 1f)
        assertEquals(VoiceClones.RATE, pcm!!.size)
    }

    @Test
    fun `a person's lines are remembered for their reference`() {
        val casting = VoiceCasting(embed = null)
        val john = casting.speakerOf(Dub.Line(1000, 4000, "Hi.", speaker = "JOHN")) { null }!!
        casting.speakerOf(Dub.Line(5000, 9000, "There.", speaker = "JOHN")) { null }
        assertEquals(listOf(1000L to 4000L, 5000L to 9000L), casting.linesOf(john))
        assertEquals(john, casting.cast(john).person)
    }
}
