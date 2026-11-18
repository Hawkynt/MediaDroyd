package com.hawkynt.mediadroyd.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.contentOrNull

/**
 * A Piper voice (#531): a VITS network (its `.onnx`, run by the platforms on ONNX Runtime) and its config (its
 * `.onnx.json`) — the rate it speaks at, the ids of the phonemes it was trained on, how its text becomes phonemes
 * ([phonemes]: espeak-ng's IPA for Piper's own voices, piper-plus's G2P for piper-plus's) and its default noise. This
 * reads the config and makes the ids the network takes ([ids]), as Piper's `phonemes_to_ids` does: the start, each
 * phoneme followed by the pad, the end. espeak-ng is run clause by clause ([clauses], [join]) so each clause's
 * punctuation — which the voices speak as pauses and intonation — stays, as Piper's patched espeak-ng keeps it.
 *
 * Sourcing: rung 1, Piper's config format and id rules (MIT, rhasspy/piper), constants copied.
 */
class PiperVoice(
    val sampleRate: Int,
    private val idMap: Map<String, List<Int>>,
    val phonemes: Phonemes,
    /** The espeak-ng voice its text is phonemised with, for [Phonemes.ESPEAK]. */
    val espeakVoice: String?,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    val speakers: Int,
) {
    /** How the voice's text becomes phonemes. */
    enum class Phonemes { ESPEAK, PIPER_PLUS, TEXT }

    /** The network's input for [phonemes] (Unicode code points, one phoneme each). */
    fun ids(phonemes: String): LongArray {
        val out = ArrayList<Long>()
        idMap[BOS]?.forEach { out += it.toLong() }
        idMap[PAD]?.forEach { out += it.toLong() }
        var at = 0
        while (at < phonemes.length) {
            val point = phonemes.codePointAt(at)
            val phoneme = String(Character.toChars(point))
            at += Character.charCount(point)
            val ids = idMap[phoneme] ?: continue
            ids.forEach { out += it.toLong() }
            idMap[PAD]?.forEach { out += it.toLong() }
        }
        idMap[EOS]?.forEach { out += it.toLong() }
        return out.toLongArray()
    }

    companion object {
        private const val PAD = "_"
        private const val BOS = "^"
        private const val EOS = "$"

        /** The voice whose config is [json] (a Piper `.onnx.json`). */
        fun parse(json: String): PiperVoice {
            val root = Json.parseToJsonElement(json).jsonObject
            val map = root.getValue("phoneme_id_map").jsonObject.mapValues { (_, ids) ->
                ids.jsonArray.map { it.jsonPrimitive.int }
            }
            val inference = root["inference"] as? JsonObject
            val type = root["phoneme_type"]?.jsonPrimitive?.contentOrNull ?: "espeak"
            return PiperVoice(
                sampleRate = root.getValue("audio").jsonObject.getValue("sample_rate").jsonPrimitive.int,
                idMap = map,
                phonemes = when (type) {
                    "text" -> Phonemes.TEXT
                    "espeak" -> Phonemes.ESPEAK
                    else -> Phonemes.PIPER_PLUS
                },
                espeakVoice = (root["espeak"] as? JsonObject)?.get("voice")?.jsonPrimitive?.contentOrNull,
                noiseScale = inference?.get("noise_scale")?.jsonPrimitive?.float ?: 0.667f,
                lengthScale = inference?.get("length_scale")?.jsonPrimitive?.float ?: 1f,
                noiseW = inference?.get("noise_w")?.jsonPrimitive?.float ?: 0.8f,
                speakers = root["num_speakers"]?.jsonPrimitive?.int ?: 1,
            )
        }

        private val CLAUSE_END = setOf('.', ',', ';', ':', '!', '?')

        /** [text] split into clauses, each with the punctuation that ends it (null for the last, unended one). */
        fun clauses(text: String): List<Pair<String, Char?>> {
            val out = ArrayList<Pair<String, Char?>>()
            val clause = StringBuilder()
            for (c in text) {
                if (c in CLAUSE_END) {
                    val words = clause.toString().trim()
                    if (words.isNotEmpty()) out += words to c
                    clause.clear()
                } else {
                    clause.append(c)
                }
            }
            clause.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it to null }
            return out
        }

        /**
         * espeak-ng's phonemes for each clause joined as Piper joins them: each clause's punctuation after it, a space
         * between clauses; espeak-ng's language switches — `(en)` … `(de)` — dropped.
         */
        fun join(phonemised: List<Pair<String, Char?>>): String = phonemised.joinToString(" ") { (phonemes, end) ->
            phonemes.replace(Regex("""\([a-z\-]+\)"""), "").trim() + (end?.toString() ?: "")
        }
    }
}
