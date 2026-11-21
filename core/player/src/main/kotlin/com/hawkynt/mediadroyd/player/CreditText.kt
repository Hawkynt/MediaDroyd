package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.nfo.TimeRange

/**
 * Frames of a video read for their text: handed the times to read at from the video's length, it gives each time with
 * the lines read there; null where the video cannot be read (CreditText.find, PlayerPlatform.readText).
 */
typealias TextReads =
    (location: String, at: (durationMs: Long) -> List<Long>) -> List<Pair<Long, List<CreditText.Line>>>?

/**
 * Credits recognised by their text (#493), where neither the sound nor the closing's black and text-on-black frames
 * (PictureCues) gave them away — credits over a scene, over a still, beside the picture. The platforms read frames of
 * the closing with PaddleOCR's PP-OCRv5 on ncnn (NativeText: detection, then Latin-script recognition) and hand over
 * the ids it reads; this decodes them by the model's own alphabet ([ALPHABET], copied from its config.json), tells a
 * frame of credits from one of the story ([isCredits]) and finds where the credits begin ([creditsStartMs]).
 */
object CreditText {
    /** PP-OCRv5's Latin alphabet: id 1 is its first character, 0 CTC's blank, the id after the last a space. */
    val ALPHABET: List<String> by lazy {
        val stream = requireNotNull(CreditText::class.java.getResourceAsStream("/ocr/latin_dict.txt"))
        stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readLines().filter { it.isNotEmpty() } }
    }

    /** How often the closing is read for its credits, and how finely the first one found is narrowed down. */
    const val STEP_MS = 5000L
    const val FINE_STEP_MS = 1000L

    /** The size the closing's frames are read at: half of 1080p, a line of credits still some 15 pixels high. */
    const val WIDTH = 960
    const val HEIGHT = 540

    /** A line of text read in a frame: its box ([x], [y], [width], [height] in the frame's pixels) and its [text]. */
    data class Line(val x: Int, val y: Int, val width: Int, val height: Int, val text: String)

    /** [ids] as the recogniser gives them, greedy CTC already applied (blanks and repeats gone), as text. */
    fun decode(ids: IntArray): String = buildString {
        for (id in ids) {
            when {
                id in 1..ALPHABET.size -> append(ALPHABET[id - 1])
                id == ALPHABET.size + 1 -> append(' ')
            }
        }
    }

    /** The lines of NativeText.read's flat result: for each its x, y, width, height, the number of ids, the ids. */
    fun lines(flat: IntArray): List<Line> {
        val out = ArrayList<Line>()
        var at = 0
        while (at + 5 <= flat.size) {
            val count = flat[at + 4]
            if (count < 0 || at + 5 + count > flat.size) break
            val text = decode(flat.copyOfRange(at + 5, at + 5 + count))
            out += Line(flat[at], flat[at + 1], flat[at + 2], flat[at + 3], text)
            at += 5 + count
        }
        return out
    }

    // Words and phrases only credits are made of, English and German; matched as whole words in a short line.
    private val WORDS = listOf(
        "directed by", "produced by", "written by", "created by", "story by", "music by", "edited by",
        "executive producer", "executive producers", "producer", "producers", "co-producer", "casting", "cast",
        "director of photography", "cinematography", "production design", "production designer", "costume designer",
        "costumes", "make-up", "makeup", "visual effects", "special effects", "stunts", "stunt coordinator",
        "based on", "teleplay", "screenplay", "composer", "original music", "editor", "film editor", "sound design",
        "special thanks", "in memory of", "all rights reserved", "copyright",
        "regie", "drehbuch", "produzent", "produzenten", "produktion", "kamera", "schnitt", "musik", "besetzung",
        "ausstattung", "kostüme", "kostümbild", "maske", "szenenbild", "synchronisation", "synchronregie",
        "dialogbuch", "deutsche fassung", "nach einer idee von", "alle rechte vorbehalten",
    )

    // A line this long is a sentence, not a credit.
    private const val CREDIT_WORDS = 6

    /**
     * Whether [lines] (one frame's) read as credits: a credit word or phrase making up a short line ("Directed by",
     * "Regie", "Executive Producers", "©"), or a roll of at least four short lines of capitalised words without a
     * sentence's punctuation — names. A subtitle, a sign or a title card in the story is neither.
     */
    fun isCredits(lines: List<Line>): Boolean {
        val texts = lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (texts.any { it.contains('©') }) return true
        val worded = texts.any { text ->
            val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}-]+")).filter { it.isNotEmpty() }
            words.size <= CREDIT_WORDS && (
                WORDS.any { phrase -> contains(words, phrase.split(' ')) } ||
                    COMPACT.any { phrase -> nearly(compact(text), phrase) }
                )
        }
        if (worded) return true
        val names = texts.filter { text ->
            val words = text.split(' ').filter { it.isNotEmpty() }
            words.size in 1..4 && text.last() !in ".!?,;:" &&
                words.count { it.first().isUpperCase() } * 4 >= words.size * 3
        }
        return names.size >= 4 && names.size * 4 >= texts.size * 3
    }

    // The longer phrases as letters alone: a recogniser's slip — a space lost or read as a letter, a stray letter, a
    // dot for a space, an accent — still finds them; a short word is matched whole only ("cast" is in "broadcast").
    private const val COMPACT_LETTERS = 7
    private val COMPACT = WORDS.map(::compact).filter { it.length >= COMPACT_LETTERS }

    // [text] in lower case, letters alone, accents taken off.
    private fun compact(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).filter { it in 'a'..'z' }

    // Whether [text] holds [phrase] with at most one letter wrong, added or missing (Levenshtein).
    private fun nearly(text: String, phrase: String): Boolean {
        if (text.contains(phrase)) return true
        for (length in phrase.length - 1..phrase.length + 1) {
            for (at in 0..text.length - length) {
                if (distance(text.substring(at, at + length), phrase) <= 1) return true
            }
        }
        return false
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun contains(words: List<String>, phrase: List<String>): Boolean =
        (0..words.size - phrase.size).any { at -> phrase.indices.all { words[at + it] == phrase[it] } }

    // How much of the story may follow credits for them to be the end credits (a scene hidden after them, #513), and
    // how many frames read between may miss them.
    private const val HIDDEN_MAX_MS = 180_000L
    private const val GAP_READS = 1

    /**
     * Where the credits begin, from [reads] (the times of frames read [stepMs] apart, each whether it read as
     * credits): the start of the first of their stretches ([creditsSpans]); null where there is none.
     */
    fun creditsStartMs(reads: List<Pair<Long, Boolean>>, stepMs: Long): Long? =
        creditsSpans(reads, stepMs).firstOrNull()?.startMs

    /**
     * Where the credits run, from [reads] (the times of frames read [stepMs] apart, each whether it read as credits):
     * each run of credit frames — one frame between may miss — that lasts IntroCredits.MIN_CREDITS_MS, with at most
     * [HIDDEN_MAX_MS] of frames that are not credits after it: from its first frame to a step after its last (#513).
     */
    fun creditsSpans(reads: List<Pair<Long, Boolean>>, stepMs: Long): List<TimeRange> {
        val runs = ArrayList<IntRange>()
        var first = -1
        var last = -1
        var gap = 0
        for (i in reads.indices) {
            if (reads[i].second) {
                if (first < 0) first = i
                last = i
                gap = 0
            } else if (first >= 0 && ++gap > GAP_READS) {
                runs += first..last
                first = -1
                gap = 0
            }
        }
        if (first >= 0) runs += first..last
        return runs
            .filter { run ->
                reads[run.last].first - reads[run.first].first + stepMs >= IntroCredits.MIN_CREDITS_MS &&
                    (run.last + 1 until reads.size).count { !reads[it].second } * stepMs <= HIDDEN_MAX_MS
            }
            .map { run -> TimeRange(reads[run.first].first, reads[run.last].first + stepMs) }
    }

    /** Where a video's credits begin by their text: the start of the first stretch [findSpans] finds. */
    fun find(read: (at: (durationMs: Long) -> List<Long>) -> List<Pair<Long, List<Line>>>?): Long? =
        findSpans(read).firstOrNull()?.startMs

    /**
     * Where a video's credits run by their text, read by [read]: it is handed the times to read frames at from the
     * video's length (every [STEP_MS] across its closing, IntroCredits.closingMs; then every [FINE_STEP_MS] in the
     * five seconds before the first credits found) and gives each time with the lines read there, or null where the
     * video cannot be read. The stretches as [creditsSpans] finds them, the first beginning at the earliest second
     * from which every frame read is credits; none where there are none or nothing could be read.
     */
    fun findSpans(read: (at: (durationMs: Long) -> List<Long>) -> List<Pair<Long, List<Line>>>?): List<TimeRange> {
        val coarse = read { durationMs ->
            val fromMs = (durationMs - IntroCredits.closingMs(durationMs)).coerceAtLeast(0)
            (fromMs until durationMs step STEP_MS).toList()
        } ?: return emptyList()
        val spans = creditsSpans(coarse.map { (time, lines) -> time to isCredits(lines) }, STEP_MS)
        val startMs = spans.firstOrNull()?.startMs ?: return emptyList()
        val fine = read { ((startMs - STEP_MS + FINE_STEP_MS) until startMs step FINE_STEP_MS).toList() }
            ?: return spans
        var firstMs = startMs
        for ((time, lines) in fine.sortedByDescending { it.first }) {
            if (!isCredits(lines)) break
            firstMs = time
        }
        return listOf(TimeRange(firstMs, spans.first().endMs)) + spans.drop(1)
    }
}
