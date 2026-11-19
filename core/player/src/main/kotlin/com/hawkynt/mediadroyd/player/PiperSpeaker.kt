package com.hawkynt.mediadroyd.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * A Piper voice speaking (#531): its VITS network on ONNX Runtime's CPU, its text made phonemes by [phonemize]
 * (espeak-ng for Piper's own voices, NativeEspeak). ONNX Runtime's Java API is the same on both platforms — its desktop jar there,
 * its Android library on the phone, each brought by its shell; the core only compiles against it.
 */
class PiperSpeaker(
    dir: File,
    entry: PiperVoices.Entry,
    private val phonemize: (text: String, voice: PiperVoice) -> String?,
) : Dub.LineSpeaker {
    /** The voice's config. */
    val voice: PiperVoice = PiperVoice.parse(File(dir, entry.config).readText())
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        File(dir, entry.model).path,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        },
    )

    override val sampleRate: Int get() = voice.sampleRate

    /** The phonemes of [text] as the voice was trained on them; null where its phonemiser is not in this build. */
    fun phonemes(text: String): String? = phonemize(text, voice)

    /**
     * [text] spoken at [lengthScale] (1 the voice's pace, smaller faster) by [speaker] of a voice with several, its
     * noise — how much it varies — scaled by [liveliness]: mono samples at [sampleRate], −1 … 1; null where it cannot
     * be phonemised or the network fails.
     */
    override fun speak(text: String, lengthScale: Float, speaker: Int, liveliness: Float): FloatArray? {
        val ids = phonemes(text)?.let(voice::ids) ?: return null
        if (ids.size <= 3) return null
        return runCatching {
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            inputs["input_lengths"] =
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
            inputs["scales"] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floatArrayOf(voice.noiseScale * liveliness, lengthScale, voice.noiseW * liveliness)),
                longArrayOf(3),
            )
            if (voice.speakers > 1 && "sid" in session.inputNames) {
                val id = speaker.coerceIn(0, voice.speakers - 1).toLong()
                inputs["sid"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(id)), longArrayOf(1))
            }
            try {
                session.run(inputs).use { result ->
                    val samples = (result[0] as OnnxTensor).floatBuffer
                    FloatArray(samples.remaining()).also { samples.get(it) }
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
        }.getOrNull()
    }

    override fun close() = session.close()
}
