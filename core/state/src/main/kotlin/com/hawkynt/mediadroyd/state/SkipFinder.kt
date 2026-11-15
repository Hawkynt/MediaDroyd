package com.hawkynt.mediadroyd.state

import com.hawkynt.mediadroyd.nfo.TimeRange
import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.PictureCues
import com.hawkynt.mediadroyd.player.TextReads

/**
 * Looks at the sound of the episodes around the one playing for their intros and credits (IntroCredits, #480), two
 * neighbours at a time, and keeps what it finds in the [store] (StateStore.saveFoundSkip) — found or not, so no video
 * is looked at twice. The episode playing comes first and its intro before anything else, from the two openings alone,
 * so it can still be skipped once it starts (or jumped out of at once, should it be playing already); then its
 * credits, then the next episode's. [print] fingerprints a video's opening (IntroCredits.openingMs from its start)
 * or, where `closing`, its last IntroCredits.CLOSING_MS; null where its sound cannot be read, and the video is then
 * left to be looked at another time. Where the sound finds nothing — or cannot be read — the [pictures] are looked at
 * (PictureCues, #492): both openings' frames for a shared intro, each closing's own frames for its credits — and where
 * those show no black and text frames, the closing's [text] (CreditText, #493): frames read every few seconds by the
 * platform's text recogniser. They are decoded only then. Each print and each video's frames are made once while this
 * finder lives. Finding blocks: not on the main thread; keeping touches the store: on its thread.
 */
class SkipFinder(
    private val store: StateStore,
    private val pictures: (location: String, closing: Boolean) -> PictureCues.Frames? = { _, _ -> null },
    private val text: TextReads = { _, _ -> null },
    private val print: (location: String, closing: Boolean) -> IntroCredits.SoundPrint?,
) {
    /** What one step looks for. */
    enum class Part { INTRO, CREDITS }

    /** One step: the [part] of [a] and [b], looked at together. */
    data class Step(val part: Part, val a: String, val b: String)

    /** What a step found for one video: its intro, or where its credits begin; either null where nothing was. */
    class Found(val intro: TimeRange?, val creditsStartMs: Long?)

    // The prints made, by location and opening or closing: the four the steps around one episode use, and more.
    private val prints = object : LinkedHashMap<Pair<String, Boolean>, IntroCredits.SoundPrint>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, Boolean>, IntroCredits.SoundPrint>,
        ) = size > CACHED
    }

    private fun printOf(location: String, closing: Boolean): IntroCredits.SoundPrint? =
        synchronized(prints) { prints[location to closing] }
            ?: print(location, closing)?.also { made -> synchronized(prints) { prints[location to closing] = made } }

    // The frames decoded, by location and opening or closing: fewer, a closing's are some megabytes.
    private val frames = object : LinkedHashMap<Pair<String, Boolean>, PictureCues.Frames>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, Boolean>, PictureCues.Frames>,
        ) = size > CACHED_FRAMES
    }

    private fun framesOf(location: String, closing: Boolean): PictureCues.Frames? =
        synchronized(frames) { frames[location to closing] }
            ?: pictures(location, closing)?.also { made -> synchronized(frames) { frames[location to closing] = made } }

    /**
     * Around [index] of the [queue] (video locations in playing order), the steps still to take, in order: the video
     * itself with its next (else its previous) — its intro, then its credits — then the next one with the one after it
     * (else the video itself) the same way, so binge-watching starts the next episode with its intro known.
     */
    fun steps(queue: List<String>, index: Int): List<Step> {
        if (queue.size < 2 || index !in queue.indices) return emptyList()
        fun partner(i: Int) = if (i + 1 < queue.size) i + 1 else i - 1
        val pairs = listOf(index, index + 1).filter { it < queue.size }.map { queue[it] to queue[partner(it)] }
        return pairs.flatMap { (a, b) -> listOf(Step(Part.INTRO, a, b), Step(Part.CREDITS, a, b)) }
            .filter { step -> step.a != step.b && listOf(step.a, step.b).any { !looked(it, step.part) } }
            .distinct()
    }

    private fun looked(location: String, part: Part) = when (part) {
        Part.INTRO -> store.hasLookedForSkips(location)
        Part.CREDITS -> store.hasLookedForCredits(location)
    }

    /**
     * What [step] finds for each of its two videos, by their sound, else by their pictures; null where neither the
     * sound nor the pictures of both can be read. Blocks.
     */
    fun find(step: Step): Map<String, Found>? {
        val closing = step.part == Part.CREDITS
        val a = printOf(step.a, closing)
        val b = if (a != null) printOf(step.b, closing) else null
        val heardBoth = a != null && b != null
        return if (closing) {
            val heard = if (heardBoth) IntroCredits.credits(a.items, a.startMs, b.items, b.startMs) else null
            // Each closing's own pictures where the sound gave no credits: they need no neighbour.
            val seenA = if (heard == null) framesOf(step.a, true) else null
            val seenB = if (heard == null) framesOf(step.b, true) else null
            if (!heardBoth && seenA == null && seenB == null) return null
            // Then their text, where the pictures could be read but showed no black and text frames.
            fun read(location: String, seen: PictureCues.Frames?) = seen?.let {
                PictureCues.creditsStartMs(it) ?: CreditText.find { at -> text(location, at) }
            }
            mapOf(
                step.a to Found(null, heard?.first ?: read(step.a, seenA)),
                step.b to Found(null, heard?.second ?: read(step.b, seenB)),
            )
        } else {
            val heard = if (heardBoth) {
                IntroCredits.intro(a.items, b.items)?.let { (x, y) -> x.shift(a.startMs) to y.shift(b.startMs) }
            } else {
                null
            }
            val seenA = if (heard == null) framesOf(step.a, false) else null
            val seenB = if (seenA != null) framesOf(step.b, false) else null
            if (!heardBoth && (seenA == null || seenB == null)) return null
            val intro = heard ?: if (seenA != null && seenB != null) PictureCues.intro(seenA, seenB) else null
            mapOf(step.a to Found(intro?.first, null), step.b to Found(intro?.second, null))
        }
    }

    /** Keeps what [step] found ([find]), for each video whose part was not looked at before. */
    fun keep(step: Step, found: Map<String, Found>) {
        for ((location, what) in found) {
            if (looked(location, step.part)) continue
            val before = store.foundSkipFor(location) ?: FoundSkip()
            store.saveFoundSkip(
                location,
                when (step.part) {
                    Part.INTRO -> before.copy(introStartMs = what.intro?.startMs, introEndMs = what.intro?.endMs)
                    Part.CREDITS -> before.copy(creditsStartMs = what.creditsStartMs, creditsLooked = true)
                },
            )
        }
    }

    /** Every step around [index] of the [queue] found and kept, one after the other, on this thread. */
    fun around(queue: List<String>, index: Int) {
        for (step in steps(queue, index)) find(step)?.let { keep(step, it) }
    }

    private fun TimeRange.shift(byMs: Long) = TimeRange(startMs + byMs, endMs + byMs)

    private companion object {
        // Prints kept: the four the steps around one episode use, and the next pair's.
        const val CACHED = 6

        // Frames kept: the two openings or closings of one step and the next one's.
        const val CACHED_FRAMES = 4
    }
}
