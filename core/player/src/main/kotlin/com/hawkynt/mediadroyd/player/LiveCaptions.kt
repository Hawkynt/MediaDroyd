package com.hawkynt.mediadroyd.player

/**
 * Subtitles made from the speech as the film plays (#529): its sound heard ahead of the position ([ahead]) in windows
 * of up to [WINDOW_MS] — what Whisper hears at once — each kept up to its last segment that ended before the window's
 * end (a word cut at the edge is heard again whole by the next window), until [AHEAD_MS] past the position is heard;
 * a seek outside what is heard starts hearing there. [hear] gives a window's segments (Whisper, NativeWhisper), times
 * from the window's start; null where the sound cannot be read. Thread-safe to read while one caller hears.
 */
class LiveCaptions(
    private val hear: (fromMs: Long, toMs: Long) -> Heard?,
    private val durationMs: Long,
) {
    /** One segment heard: from [startMs] to [endMs] of its window, its [text], its [tone] where it was measured. */
    data class Segment(val startMs: Long, val endMs: Long, val text: String, val tone: Prosody.Features? = null)

    /** What a window heard: its [language] (ISO 639-1, as Whisper names it) and its segments. */
    data class Heard(val language: String, val segments: List<Segment>)

    private val heard = ArrayList<Dub.Line>()

    /**
     * The film's own speech so far, which each line's tone is measured against (#534): men's and women's apart, so a
     * woman's line is not taken for a raised one (#532); lines with no voiced frames against all.
     */
    private val men = Prosody.Baseline()
    private val women = Prosody.Baseline()
    private val anyone = Prosody.Baseline()
    private val covered = ArrayList<LongRange>()

    /** The language the speech was heard in, once any was. */
    @Volatile
    var language: String? = null
        private set

    /** The lines heard so far, in order. */
    fun lines(): List<Dub.Line> = synchronized(heard) { heard.toList() }

    /** Whether the speech at [positionMs] is heard already: else the hearing runs behind the film. */
    fun heardAt(positionMs: Long): Boolean = synchronized(heard) { covered.any { positionMs in it } }

    /** The line showing at [positionMs], or null. */
    fun lineAt(positionMs: Long): Dub.Line? =
        synchronized(heard) { heard.lastOrNull { positionMs in it.startMs until it.endMs } }

    /** Hears what is due from [positionMs]; returns whether it heard anything. Blocks while it hears. */
    fun ahead(positionMs: Long): Boolean {
        var cursor = synchronized(heard) { covered.firstOrNull { positionMs in it }?.last ?: positionMs }
        var any = false
        while (cursor < minOf(positionMs + AHEAD_MS, durationMs)) {
            val end = minOf(cursor + WINDOW_MS, durationMs)
            val window = hear(cursor, end) ?: break
            language = window.language
            val last = end >= durationMs
            // Whole segments only: one running into the window's last stretch is heard again by the next window.
            val kept = window.segments.filter { last || it.endMs + cursor <= end - EDGE_MS }
            val next = if (last) end else kept.lastOrNull()?.let { cursor + it.endMs } ?: (end - EDGE_MS)
            synchronized(heard) {
                for (segment in kept) {
                    val text = segment.text.trim()
                    if (text.isEmpty()) continue
                    val tone = segment.tone
                    val baseline = when {
                        tone == null || tone.pitchHz <= 0 -> anyone
                        tone.pitchHz >= Dub.WOMAN_HZ -> women
                        else -> men
                    }
                    val delivery = tone?.let { baseline.add(it); baseline.delivery(it) } ?: Dub.Delivery()
                    val line = Dub.Line(
                        cursor + segment.startMs, cursor + segment.endMs, text, delivery = delivery,
                        heardPitchHz = tone?.pitchHz?.takeIf { it > 0 },
                    )
                    if (heard.none { it.startMs < line.endMs && line.startMs < it.endMs }) heard += line
                }
                heard.sortBy { it.startMs }
                cover(cursor until maxOf(next, cursor + 1))
            }
            any = true
            if (next <= cursor) break
            cursor = next
        }
        return any
    }

    // Marks [range] heard, joining the stretches it touches.
    private fun cover(range: LongRange) {
        var merged = range
        val touching = covered.filter { it.first <= merged.last + 1 && merged.first <= it.last + 1 }
        for (t in touching) merged = minOf(merged.first, t.first)..maxOf(merged.last, t.last)
        covered.removeAll(touching)
        covered += merged
    }

    companion object {
        /** What Whisper hears at once. */
        const val WINDOW_MS = 30_000L

        /** How far ahead of the position the speech is heard. */
        const val AHEAD_MS = 60_000L

        /** A segment ending this close to a window's end may be cut off: it is heard again by the next window. */
        const val EDGE_MS = 2_000L
    }
}

/**
 * How made subtitles reach the language wanted (#529, #530): whether Whisper writes English ([whisperTranslates]) and
 * into which language OPUS-MT then translates that English ([translator]; null for none). [of] decides from the
 * language [target]ed (null or empty: as spoken) and the one [spoken] (null before it is known): as spoken, Whisper
 * writes what it hears; into English, Whisper translates unless English is spoken; into another language, nothing
 * where that is spoken, else English first — Whisper's, unless English is spoken — then OPUS-MT.
 */
data class CaptionRoute(val whisperTranslates: Boolean, val translator: String?) {
    companion object {
        fun of(target: String?, spoken: String?): CaptionRoute = when {
            target.isNullOrEmpty() -> CaptionRoute(false, null)
            target == "en" -> CaptionRoute(spoken != "en", null)
            target == spoken -> CaptionRoute(false, null)
            else -> CaptionRoute(spoken != "en", target)
        }
    }
}
