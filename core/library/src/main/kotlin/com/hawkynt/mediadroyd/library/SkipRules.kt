package com.hawkynt.mediadroyd.library

import com.hawkynt.mediadroyd.nfo.TimeRange

/**
 * What binge watching skips: the intro at the start and the credits at the end.
 * Matroska chapters are the authoritative source — they are what the file
 * carries — and the edit decision list beside the video (see
 * [com.hawkynt.mediadroyd.nfo.EditDecisionList]) fills in where a file has no
 * chapter atoms. Titles are matched in the languages real releases use; a
 * chapter named "Intro" in an English file and "Vorspann" in a German one mean
 * the same thing.
 */
object SkipRules {

    private val INTRO_TITLE = Regex("(?i)^(intro|opening|opener|vorspann|title sequence|leader)")
    private val CREDITS_TITLE = Regex("(?i)^(credits|end credits|closing|outro|abspann|ending|schlusstitel)")

    /** Millisecond to seek to when starting playback; null means no intro known. */
    fun introEndMs(chapters: List<Chapter>, intro: TimeRange?): Long? =
        chapters.firstOrNull { INTRO_TITLE.containsMatchIn(it.title) && it.endMs > 0 }?.endMs
            ?: intro?.endMs

    /** Where the intro runs, start to end in milliseconds; null means no intro known. */
    fun introSpanMs(chapters: List<Chapter>, intro: TimeRange?): LongRange? =
        chapters.firstOrNull { INTRO_TITLE.containsMatchIn(it.title) && it.endMs > 0 }?.let { it.startMs..it.endMs }
            ?: intro?.let { it.startMs..it.endMs }

    /**
     * Where to jump when playback is at [positionMs]: the intro's end while inside the intro, else
     * null. A cold open before the intro plays; the last second is left alone so a seek landing just
     * short of the end cannot loop.
     */
    fun skipTarget(positionMs: Long, intro: LongRange?): Long? {
        if (intro == null) return null
        return if (positionMs >= intro.first && positionMs < intro.last - 1000) intro.last else null
    }

    /** Millisecond at which the credits begin; null means no credits known. */
    fun creditsStartMs(chapters: List<Chapter>, credits: TimeRange?): Long? =
        chapters.firstOrNull { CREDITS_TITLE.containsMatchIn(it.title) }?.startMs
            ?: credits?.startMs

    /**
     * Where the credits run, as stretches start to end in milliseconds (#513): each chapter named for credits, so
     * credits split around a hidden scene are two stretches, else the edit decision list's or NFO's span. An end of
     * [Long.MAX_VALUE] runs to the end of the file (a chapter without an end).
     */
    fun creditsSpans(chapters: List<Chapter>, credits: TimeRange?): List<LongRange> {
        val named = chapters.filter { CREDITS_TITLE.containsMatchIn(it.title) }
            .map { it.startMs..(if (it.endMs > it.startMs) it.endMs else Long.MAX_VALUE) }
        return named.ifEmpty { listOfNotNull(credits?.let { it.startMs..it.endMs }) }
    }

    /** What the credits ask at a moment of playback (see [creditsAction]). */
    sealed interface Credits {
        /** The video is over here: nothing worth keeping follows. */
        data object End : Credits

        /** Over these credits to [toMs], where a scene hidden after them plays. */
        data class Jump(val toMs: Long) : Credits
    }

    /** Credits ending this close to the file's end leave nothing worth keeping after them. */
    const val HIDDEN_SCENE_MIN_MS = 10_000L

    /**
     * What to do at [positionMs] among the credits' [spans] in a video [durationMs] long (0: unknown): inside credits
     * that run to its end — or to within [HIDDEN_SCENE_MIN_MS] of it — [Credits.End]; inside credits a scene follows,
     * [Credits.Jump] to their end, so the scene plays; elsewhere null. The last second of a stretch is left alone, so a
     * jump landing just short of its end cannot loop.
     */
    fun creditsAction(positionMs: Long, spans: List<LongRange>, durationMs: Long): Credits? {
        val span = spans.firstOrNull { positionMs >= it.first && positionMs < it.last - 1000 } ?: return null
        val toEnd = span.last == Long.MAX_VALUE || durationMs <= 0 || span.last >= durationMs - HIDDEN_SCENE_MIN_MS
        return if (toEnd) Credits.End else Credits.Jump(span.last)
    }
}
