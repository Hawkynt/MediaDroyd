package com.hawkynt.mediadroyd.engine.desktop

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.hawkynt.mediadroyd.player.NativeEspeak
import com.hawkynt.mediadroyd.player.PiperVoice
import com.hawkynt.mediadroyd.player.PiperVoices
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * espeak-ng on the desktop (#531): `mediadroyd_espeak` and its data, beside the native library — only in builds for one's
 * own machines (-Pespeak). Loaded once; [dataDir] null where it is not there.
 */
object EspeakLibrary {
    /** The folder holding `espeak-ng-data` once the library loaded; null where it is not carried. */
    val dataDir: File? by lazy {
        val folder = System.getProperty(NativeNetworks.FOLDER_PROPERTY)?.let(::File) ?: NativeNetworks.file?.parentFile
            ?: return@lazy null
        val name = System.mapLibraryName("mediadroyd_espeak")
        val library = File(folder, name)
        if (!library.isFile || !File(folder, "espeak-ng-data").isDirectory) return@lazy null
        runCatching { System.load(library.absolutePath) }.getOrNull() ?: return@lazy null
        folder
    }
}

/**
 * A Piper voice speaking on the desktop (#531): its VITS network on ONNX Runtime's CPU, its text phonemised by espeak-ng
 * (EspeakLibrary) for Piper's own voices. [speak] makes one line's sound, mono floats at the voice's rate.
 */
class PiperSpeaker(dir: File, entry: PiperVoices.Entry) : AutoCloseable {
    /** The voice's config. */
    val voice: PiperVoice = PiperVoice.parse(File(dir, entry.config).readText())
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        File(dir, entry.model).path,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2)) },
    )

    /** The phonemes of [text] as the voice was trained on them; null where its phonemiser is not in this build. */
    fun phonemes(text: String): String? = when (voice.phonemes) {
        PiperVoice.Phonemes.ESPEAK -> {
            val data = EspeakLibrary.dataDir
            val name = voice.espeakVoice
            if (data == null || name == null) null else NativeEspeak.phonemize(data.path, name, text)
        }
        else -> null
    }

    /**
     * [text] spoken at [lengthScale] (1 the voice's pace, smaller faster) by [speaker] of a voice with several: mono
     * samples at PiperVoice.sampleRate, −1 … 1; null where it cannot be phonemised or the network fails.
     */
    fun speak(text: String, lengthScale: Float = voice.lengthScale, speaker: Int = 0): FloatArray? {
        val ids = phonemes(text)?.let(voice::ids) ?: return null
        if (ids.size <= 3) return null
        return runCatching {
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            inputs["input_lengths"] =
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
            inputs["scales"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(voice.noiseScale, lengthScale, voice.noiseW)), longArrayOf(3),
            )
            if (voice.speakers > 1 && "sid" in session.inputNames) {
                inputs["sid"] = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(speaker.coerceIn(0, voice.speakers - 1).toLong())), longArrayOf(1),
                )
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
