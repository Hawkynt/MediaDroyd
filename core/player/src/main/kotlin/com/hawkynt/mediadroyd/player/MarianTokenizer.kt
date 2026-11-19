package com.hawkynt.mediadroyd.player

/**
 * The SentencePiece Unigram tokenizer OPUS-MT's Marian models read and write (#530), as their `tokenizer.json`
 * describes it: the text split at whitespace, each word marked with ▁ in front (Metaspace), then cut into the pieces
 * of the vocabulary whose log-probabilities sum highest (Viterbi over the word); a letter no piece covers is `<unk>`;
 * `</s>` (id 0) ends the sentence. [pieces] are the vocabulary in id order with their scores.
 *
 * Sourcing: rung 3/1 — the Unigram model as Kudo (2018) and Hugging Face's tokenizers describe it (Apache-2.0, read
 * for behaviour), written here; the vocabulary and its scores copied from each model's file.
 */
class MarianTokenizer(private val pieces: List<Pair<String, Double>>, private val unk: Int) {
    private val ids = HashMap<String, Int>(pieces.size * 2).apply { pieces.forEachIndexed { i, (p, _) -> put(p, i) } }
    private val longest = pieces.maxOf { it.first.length }
    private val unkScore = (pieces.minOf { it.second }) - 10.0

    /** [text]'s ids, `</s>` last. */
    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) out += viterbi(MARK + word)
        out += EOS
        return out
    }

    // The best cut of [word] into pieces.
    private fun viterbi(word: String): List<Int> {
        val n = word.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        val piece = IntArray(n + 1)
        best[0] = 0.0
        for (end in 1..n) {
            for (start in maxOf(0, end - longest) until end) {
                if (best[start] == Double.NEGATIVE_INFINITY) continue
                val id = ids[word.substring(start, end)] ?: continue
                val score = best[start] + pieces[id].second
                if (score > best[end]) {
                    best[end] = score
                    from[end] = start
                    piece[end] = id
                }
            }
            // A letter no piece covers: unknown, on its own.
            if (best[end] == Double.NEGATIVE_INFINITY && best[end - 1] != Double.NEGATIVE_INFINITY) {
                best[end] = best[end - 1] + unkScore
                from[end] = end - 1
                piece[end] = unk
            }
        }
        val out = ArrayList<Int>()
        var at = n
        while (at > 0) {
            out += piece[at]
            at = from[at]
        }
        return out.asReversed().fold(ArrayList()) { acc, id ->
            if (!(id == unk && acc.lastOrNull() == unk)) acc += id
            acc
        }
    }

    /** [ids] as text: the pieces joined, ▁ as spaces, `</s>`, `<unk>` and `<pad>` left out. */
    fun decode(ids: List<Int>): String = ids.mapNotNull { id ->
        pieces.getOrNull(id)?.first?.takeUnless { it == "</s>" || it == "<unk>" || it == "<pad>" }
    }.joinToString("").replace(MARK, ' ').trim()

    companion object {
        /** The word mark, and the end of sentence's id. */
        const val MARK = '▁'
        const val EOS = 0

        /** The tokenizer a model's `tokenizer.json` describes. */
        fun parse(json: String): MarianTokenizer {
            val model = kotlinx.serialization.json.Json.parseToJsonElement(json)
                .let { (it as kotlinx.serialization.json.JsonObject)["model"] as kotlinx.serialization.json.JsonObject }
            val vocab = (model["vocab"] as kotlinx.serialization.json.JsonArray).map { entry ->
                val pair = entry as kotlinx.serialization.json.JsonArray
                (pair[0] as kotlinx.serialization.json.JsonPrimitive).content to
                    (pair[1] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
            }
            val unk = (model["unk_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 1
            return MarianTokenizer(vocab, unk)
        }
    }
}
