package com.hawkynt.mediadroyd.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * An OPUS-MT translator (#530): its Marian encoder and decoder on ONNX Runtime's CPU, its text cut by its own
 * [MarianTokenizer]. [translateAll] reads a window's lines together (one encoder run, the lines padded to the longest)
 * and writes their translations a piece at a time, all lines in step, always each one's most likely next piece
 * (greedy; `<pad>` never, as the model's bad_words_ids say) until each has written `</s>`. The decoder is the export's
 * merged one: it keeps what it worked out of the pieces before and of the English line (its cache, `past_key_values`)
 * from step to step, so a step reads only the newest piece — a window's lines cost the steps of its longest, not the
 * sum of all, each a fraction of a step without the cache. ONNX Runtime's Java API is the same on both platforms; each
 * shell brings its own build.
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

    // The decoder's layers and the shape of their cache, read from the network itself.
    private val layers = decoder.inputNames.count { it.startsWith("past_key_values.") && it.endsWith(".decoder.key") }
    private val heads: Long
    private val headSize: Long

    init {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, TranslationModels.CONFIG).readText())
            as kotlinx.serialization.json.JsonObject
        fun number(key: String) = (config[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        pad = number("pad_token_id") ?: 58_100
        start = number("decoder_start_token_id") ?: pad
        val shape = (decoder.inputInfo.getValue("past_key_values.0.decoder.key").info as TensorInfo).shape
        heads = shape[1]
        headSize = shape[3]
    }

    /** [text] (English) translated; null where the networks fail. */
    fun translate(text: String): String? = translateAll(listOf(text))?.single()

    /** [texts] (English lines) translated together, in their order; null where the networks fail. */
    fun translateAll(texts: List<String>): List<String>? = runCatching {
        if (texts.isEmpty()) return@runCatching emptyList()
        val sources = texts.map { text -> tokenizer.encode(text).map { it.toLong() } }
        val batch = sources.size
        val length = sources.maxOf { it.size }
        val ids = LongArray(batch * length) { i -> sources[i / length].getOrElse(i % length) { pad } }
        val mask = LongArray(batch * length) { i -> if (i % length < sources[i / length].size) 1L else 0L }
        val shape = longArrayOf(batch.toLong(), length.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { attention ->
                encoder.run(mapOf("input_ids" to idTensor, "attention_mask" to attention)).use { encoded ->
                    val written = decode(encoded[0] as OnnxTensor, attention, batch, minOf(length * 2 + 10, MAX_STEPS))
                    written.mapIndexed { i, pieces ->
                        if (sources[i].size <= 1) "" else tokenizer.decode(pieces.map { it.toInt() })
                    }
                }
            }
        }
    }.getOrNull()

    // Every line's pieces, greedy, all in step, the cache carried from step to step.
    private fun decode(hidden: OnnxTensor, attention: OnnxTensor, batch: Int, steps: Int): List<List<Long>> {
        val written = List(batch) { ArrayList<Long>() }
        val done = BooleanArray(batch)
        var last = LongArray(batch) { start }
        // The first step's results hold the English line's cache for every later step; the last step's its own.
        var first: OrtSession.Result? = null
        var previous: OrtSession.Result? = null
        val emptyShape = longArrayOf(batch.toLong(), heads, 0, headSize)
        val empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape)
        try {
            for (step in 0 until steps) {
                val cached = step > 0
                OnnxTensor.createTensor(env, LongBuffer.wrap(last), longArrayOf(batch.toLong(), 1)).use { input ->
                    OnnxTensor.createTensor(env, booleanArrayOf(cached)).use { branch ->
                        val inputs = HashMap<String, OnnxTensor>()
                        inputs["input_ids"] = input
                        inputs["encoder_hidden_states"] = hidden
                        inputs["encoder_attention_mask"] = attention
                        inputs["use_cache_branch"] = branch
                        for (layer in 0 until layers) {
                            for (part in listOf("key", "value")) {
                                inputs["past_key_values.$layer.decoder.$part"] =
                                    previous?.tensor("present.$layer.decoder.$part") ?: empty
                                inputs["past_key_values.$layer.encoder.$part"] =
                                    first?.tensor("present.$layer.encoder.$part") ?: empty
                            }
                        }
                        val result = decoder.run(inputs)
                        val logits = (result[0] as OnnxTensor).floatBuffer
                        val vocab = logits.remaining() / batch
                        last = LongArray(batch) { row ->
                            if (done[row]) return@LongArray pad
                            val best = best(logits, row * vocab, vocab)
                            if (best == MarianTokenizer.EOS.toLong()) done[row] = true else written[row] += best
                            best
                        }
                        if (first == null) first = result else previous?.takeIf { it !== first }?.close()
                        previous = result
                    }
                }
                if (done.all { it }) break
            }
        } finally {
            previous?.takeIf { it !== first }?.close()
            first?.close()
            empty.close()
        }
        return written
    }

    private fun OrtSession.Result.tensor(name: String): OnnxTensor = get(name).get() as OnnxTensor

    // The most likely piece among [vocab] logits from [base], `<pad>` never.
    private fun best(logits: FloatBuffer, base: Int, vocab: Int): Long {
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

    override fun close() {
        encoder.close()
        decoder.close()
    }

    private companion object {
        // The most pieces a translation is written in: a subtitle line takes a few dozen; a speech model's loop of
        // hundreds of words would otherwise hold the window up for a minute (#541).
        const val MAX_STEPS = 160
    }
}
