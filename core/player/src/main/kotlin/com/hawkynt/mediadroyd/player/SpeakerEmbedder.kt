package com.hawkynt.mediadroyd.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Who speaks, as a voice's fingerprint (#532): a stretch of speech (16 kHz, −1 … 1) made Kaldi's filterbank ([Fbank]),
 * each bin's mean taken out, through 3D-Speaker's CAM++ network (Alibaba's iic/speech_campplus_sv_zh_en_16k-common,
 * Apache-2.0, as sherpa-onnx exports it) on ONNX Runtime's CPU: 192 numbers that lie close together for the same
 * voice. As sherpa-onnx's speaker-embedding extractor computes it (Apache-2.0; its feature options and "global-mean"
 * normalisation), held to it by `SpeakerEmbedderTest`. The core only compiles against ONNX Runtime; each shell brings
 * its build.
 */
class SpeakerEmbedder(model: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        model.path,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        },
    )

    /** The network's own embedding of [samples]; null where they are shorter than [MIN_MS] or it fails. */
    fun raw(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_MS * Fbank.RATE / 1000) return null
        val frames = Fbank.compute(samples)
        Fbank.subtractMean(frames)
        val flat = FloatArray(frames.size * Fbank.BINS)
        for (f in frames.indices) frames[f].copyInto(flat, f * Fbank.BINS)
        return runCatching {
            val shape = longArrayOf(1, frames.size.toLong(), Fbank.BINS.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { x ->
                session.run(mapOf(session.inputNames.first() to x)).use { result ->
                    val out = (result[0] as OnnxTensor).floatBuffer
                    FloatArray(out.remaining()).also { out.get(it) }
                }
            }
        }.getOrNull()
    }

    /** [samples]' embedding scaled to unit length, for comparing by cosine ([Speakers]); null as [raw]. */
    fun embed(samples: FloatArray): FloatArray? = raw(samples)?.let(::unit)

    override fun close() = session.close()

    companion object {
        /** The least speech a fingerprint is taken from: shorter lines say too little about the voice. */
        const val MIN_MS = 1000L

        /** The network, fetched when the voice-over first needs it (ModelFolder.fetch, checked against its digest). */
        val DOWNLOAD = NeuralModels.Download(
            "speaker-campplus",
            listOf(
                NeuralModels.File(
                    MODEL, 28_281_165L, "53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b",
                ),
            ),
            source = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models",
        )

        /** The network's file name. */
        const val MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"

        /** [v] at unit length. */
        fun unit(v: FloatArray): FloatArray {
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: return v
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}
