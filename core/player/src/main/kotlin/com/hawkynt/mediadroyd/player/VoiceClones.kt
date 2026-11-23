package com.hawkynt.mediadroyd.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The original actors' voices for the voice-over (#533): each person the voice-over knows (VoiceCasting) speaks their
 * translated lines in an imitation of their own voice, made from their speech in the film. The household's choice
 * alone — off by default, clearly labelled wherever it plays — for its own copies, on the desktop with a graphics card.
 *
 * The networks are PyTorch's, run by a helper the household installs beside the app (tools/voice-clone): it listens on
 * this computer only, and the [Client] refuses any address that is not this computer's, so neither the film's voices
 * nor the lines ever leave it. Three engines, each under its own licence ([Engine]): OpenVoice V2 (MIT), GPT-SoVITS
 * (MIT) and XTTS-v2 (the Coqui Public Model License: non-commercial use only). Each person's reference is their
 * longest clear lines ([reference]), cut from the film's own sound into the app's folder.
 */
object VoiceClones {
    /** The rate every imitated line is handed to the voice-over at, whatever the engine speaks at. */
    const val RATE = 24000

    /** An engine the helper runs: its [id] there, its [label], its [licence], and whether it may be used to earn. */
    enum class Engine(val id: String, val label: String, val licence: String, val commercialUse: Boolean) {
        OPENVOICE("openvoice", "OpenVoice V2", "MIT", true),
        GPT_SOVITS("gpt-sovits", "GPT-SoVITS", "MIT", true),
        XTTS("xtts", "XTTS-v2", "Coqui Public Model License: non-commercial use only", false),
        ;

        companion object {
            /** The engine [id] names; OpenVoice for one this version does not know. */
            fun of(id: String): Engine = entries.firstOrNull { it.id == id } ?: OPENVOICE
        }
    }

    /** The shortest and longest line a reference takes, and how much speech it wants and takes at most. */
    const val MIN_LINE_MS = 1500L
    const val MAX_LINE_MS = 12_000L
    const val ENOUGH_MS = 6000L
    const val MOST_MS = 15_000L

    /**
     * A person's reference from their lines' [spans] (start to end, ms): the longest of those between [MIN_LINE_MS]
     * and [MAX_LINE_MS] — long enough to hear the voice, short enough to be one sentence — until [ENOUGH_MS] are
     * together, never more than [MOST_MS]; null while there is not enough of them.
     */
    fun reference(spans: List<Pair<Long, Long>>): List<Pair<Long, Long>>? {
        val chosen = ArrayList<Pair<Long, Long>>()
        var total = 0L
        for (span in spans.filter { it.second - it.first in MIN_LINE_MS..MAX_LINE_MS }
            .sortedByDescending { it.second - it.first }) {
            val length = span.second - span.first
            if (total + length > MOST_MS) continue
            chosen += span
            total += length
            if (total >= ENOUGH_MS) return chosen
        }
        return null
    }

    /** [samples] (mono, −1 … 1) at [rate] as a 16-bit PCM WAV file. */
    fun wav(samples: FloatArray, rate: Int): ByteArray {
        val data = samples.size * 2
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data)
        for (s in samples) out.putShort((s.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        return out.array()
    }

    /** A 16-bit PCM WAV's samples, its channels mixed, and its rate; null where it is no such file. */
    fun readWav(bytes: ByteArray): Pair<FloatArray, Int>? = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var at = 12
        var channels = 1
        var rate = 0
        var bits = 16
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4)
            val size = b.getInt(at + 4)
            when (id) {
                "fmt " -> {
                    channels = b.getShort(at + 10).toInt()
                    rate = b.getInt(at + 12)
                    bits = b.getShort(at + 22).toInt()
                }
                "data" -> {
                    if (bits != 16 || rate <= 0 || channels <= 0) return null
                    val frames = minOf(size, bytes.size - at - 8) / 2 / channels
                    val out = FloatArray(frames) { f ->
                        var sum = 0f
                        for (c in 0 until channels) sum += b.getShort(at + 8 + (f * channels + c) * 2) / 32768f
                        sum / channels
                    }
                    return out to rate
                }
            }
            at += 8 + size + (size and 1)
        }
        null
    }.getOrNull()

    /** [samples] at [from] brought to [to], linearly (speech: nothing above a few kHz matters). */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val count = (samples.size.toLong() * to / from).toInt()
        return FloatArray(count) { i ->
            val t = i.toDouble() * from / to
            val k = t.toInt().coerceAtMost(samples.size - 1)
            val next = samples[(k + 1).coerceAtMost(samples.size - 1)]
            (samples[k] + (next - samples[k]) * (t - k)).toFloat()
        }
    }

    /** What the helper has: a graphics card ([cuda]) and the [engines] installed. */
    data class Health(val cuda: Boolean, val engines: Set<Engine>)

    /**
     * The helper at [address] — on this computer only: an address naming any other host is refused, so nothing the
     * voice-over hands it can leave the machine.
     */
    class Client(private val address: String) {
        init {
            val uri = URI(address)
            require(uri.scheme == "http") { "the helper is reached over plain http on this computer: $address" }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
            require(host != null && (host == "localhost" || InetAddress.getByName(host).isLoopbackAddress)) {
                "the helper must run on this computer: $address"
            }
        }

        private val base = address.trimEnd('/')

        /** What the helper has; null where it does not answer. */
        fun health(): Health? = runCatching {
            val json = Json.parseToJsonElement(String(request("/health", null))).jsonObject
            val engines = json["engines"]?.jsonObject.orEmpty()
                .filter { (_, on) -> on.jsonPrimitive.boolean }.keys
                .mapNotNull { id -> Engine.entries.firstOrNull { it.id == id } }.toSet()
            Health(json["cuda"]?.jsonPrimitive?.boolean == true, engines)
        }.getOrNull()

        /**
         * [text] in [language] spoken by [engine] in the voice of the [references] (WAV files on this computer, speech
         * in [referenceLanguage]) at [speed]: the helper's WAV.
         */
        fun speak(
            engine: Engine,
            text: String,
            language: String,
            references: List<String>,
            referenceLanguage: String,
            speed: Float,
        ): ByteArray {
            val body: JsonObject = buildJsonObject {
                put("engine", engine.id)
                put("text", text)
                put("language", language)
                putJsonArray("references") { references.forEach { add(it) } }
                put("reference_language", referenceLanguage)
                put("speed", speed)
            }
            return request("/speak", body.toString().toByteArray())
        }

        private fun request(path: String, body: ByteArray?): ByteArray {
            val connection = URI("$base$path").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_MS
            connection.readTimeout = SPEAK_MS
            try {
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                check(code == HttpURLConnection.HTTP_OK) { "the helper answered $code" }
                return connection.inputStream.use { input ->
                    ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * A person's imitated voice as the voice-over speaks with it (Dub.LineSpeaker): [engine] through [client], from
     * their [references], speaking [language], their own speech being in [referenceLanguage]. The length scale becomes
     * the engines' speed; the voice's speaker and liveliness are Piper's and mean nothing here.
     */
    class Speaker(
        private val client: Client,
        private val engine: Engine,
        private val references: List<String>,
        private val language: String,
        private val referenceLanguage: String,
    ) : Dub.LineSpeaker {
        override val sampleRate: Int get() = RATE

        override fun speak(text: String, lengthScale: Float, speaker: Int, liveliness: Float): FloatArray? =
            runCatching {
                val speed = (1f / lengthScale.coerceAtLeast(0.1f) * 100).roundToInt() / 100f
                val (pcm, rate) = readWav(client.speak(engine, text, language, references, referenceLanguage, speed))
                    ?: return null
                resample(pcm, rate, RATE)
            }.getOrNull()
    }

    /**
     * A person's imitated voice, falling back to [fallback] (a Piper voice) for a line it cannot speak — a language
     * its engine does not speak, the helper gone — that line brought to the imitated voice's rate.
     */
    class WithFallback(private val voice: Dub.LineSpeaker, private val fallback: Dub.LineSpeaker) : Dub.LineSpeaker {
        override val sampleRate: Int get() = voice.sampleRate

        override fun speak(text: String, lengthScale: Float, speaker: Int, liveliness: Float): FloatArray? =
            voice.speak(text, lengthScale, speaker, liveliness)
                ?: fallback.speak(text, lengthScale, speaker, liveliness)
                    ?.let { resample(it, fallback.sampleRate, sampleRate) }
    }

    private const val CONNECT_MS = 2000
    // Speaking a line on a graphics card takes seconds; the first, while the network loads, longer.
    private const val SPEAK_MS = 120_000
}
