package com.hawkynt.mediadroyd.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/**
 * An OPUS-MT translator (#530): its Marian encoder and decoder on ONNX Runtime's CPU, its text cut by its own
 * [MarianTokenizer]. [translate] reads a line once (the encoder) and writes the translation a piece at a time, always the
 * most likely next one (greedy; `<pad>` never, as the model's bad_words_ids say), until `</s>`. The decoder without its
 * cache reads the whole translation so far each step: subtitle lines are short. ONNX Runtime's Java API is the same on
 * both platforms; each shell brings its own build.
 */
class MarianTranslator(dir: File) : AutoCloseable {
    private val tokenizer = MarianTokenizer.parse(File(dir, TranslationModels.TOKENIZER).readText())
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
    }
    private val encoder = env.createSession(File(dir, TranslationModels.ENCODER).path, options)
    private val decoder = env.createSession(File(dir, TranslationModels.DECODER).path, options)
    private val start: Long
    private val pad: Long

    init {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, TranslationModels.CONFIG).readText())
            as kotlinx.serialization.json.JsonObject
        fun number(key: String) = (config[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        pad = number("pad_token_id") ?: 58_100
        start = number("decoder_start_token_id") ?: pad
    }

    /** [text] (English) translated; null where the networks fail. */
    fun translate(text: String): String? = runCatching {
        val source = tokenizer.encode(text).map { it.toLong() }.toLongArray()
        if (source.size <= 1) return@runCatching ""
        val shape = longArrayOf(1, source.size.toLong())
        val mask = LongArray(source.size) { 1L }
        OnnxTensor.createTensor(env, LongBuffer.wrap(source), shape).use { ids ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { attention ->
                encoder.run(mapOf("input_ids" to ids, "attention_mask" to attention)).use { encoded ->
                    val hidden = encoded[0] as OnnxTensor
                    val written = arrayListOf(start)
                    repeat(source.size * 2 + 10) {
                        val next = step(written, hidden, attention)
                        if (next == MarianTokenizer.EOS.toLong()) return@repeat
                        written += next
                    }
                    tokenizer.decode(written.drop(1).map { it.toInt() })
                }
            }
        }
    }.getOrNull()

    // The most likely piece after [written], `<pad>` never.
    private fun step(written: List<Long>, hidden: OnnxTensor, attention: OnnxTensor): Long {
        val prefix = written.toLongArray()
        OnnxTensor.createTensor(env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong())).use { ids ->
            val inputs = mapOf(
                "input_ids" to ids, "encoder_hidden_states" to hidden, "encoder_attention_mask" to attention,
            )
            decoder.run(inputs).use { result ->
                val logits = (result[0] as OnnxTensor).floatBuffer
                val vocab = logits.remaining() / prefix.size
                val base = (prefix.size - 1) * vocab
                var best = 0
                var bestScore = Float.NEGATIVE_INFINITY
                for (v in 0 until vocab) {
                    if (v.toLong() == pad) continue
                    val score = logits.get(base + v)
                    if (score > bestScore) {
                        bestScore = score
                        best = v
                    }
                }
                return best.toLong()
            }
        }
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }
}
