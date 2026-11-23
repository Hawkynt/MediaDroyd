package com.hawkynt.mediadroyd.player

import kotlin.math.round

/**
 * A voice per character in the voice-over (#532): the lines sorted into the people who speak them, each person cast a
 * voice of their own.
 *
 * Who speaks: a name the subtitles give the line (`JOHN: …`, Dub.clean) makes it that person's; else the line's own
 * sound, its fingerprint ([embed]: SpeakerEmbedder), joins the person whose fingerprints it lies closest to by cosine,
 * where that is at least [SAME], or starts a person of its own — online, in the order lines come, each line sorted once.
 * A line shorter than SpeakerEmbedder.MIN_MS, or without a network, stays unsorted and is spoken as before.
 *
 * Their voice ([cast]): a man's or a woman's by the person's usual pitch — the median of their lines' — so a raised or
 * a low line does not change who speaks it; in a voice of many speakers (LibriTTS-R) a speaker of their own; and the
 * people of one sex spread over [MAX_SEMITONES] either way by their usual pitch, the deeper one spoken deeper, so two
 * men cast the same voice still sound like two. The threshold and the spread are hypotheses, tuned by ear.
 */
class VoiceCasting(
    private val embed: ((FloatArray) -> FloatArray?)?,
    private val same: Float = SAME,
) {
    /** A person's voice: a [woman]'s or a man's, [pitch] semitones off it, [sid] the speaker in a voice of many. */
    data class Cast(val woman: Boolean, val pitch: Float, val sid: Int)

    private class Person(val name: String?) {
        var centroid: FloatArray? = null
        var count = 0
        val pitches = ArrayList<Double>()

        fun usualPitch(): Double? = pitches.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    }

    private val all = ArrayList<Person>()
    private val sorted = HashMap<Pair<Long, String>, Int?>()

    /** How many people are known. */
    val people: Int get() = synchronized(all) { all.size }

    /**
     * The person speaking [line] (an index for [cast]), from its name or its [sound] (16 kHz, −1 … 1, read only when
     * needed); null where neither tells. Each line is sorted once.
     */
    fun speakerOf(line: Dub.Line, sound: () -> FloatArray?): Int? = synchronized(all) {
        val key = line.startMs to line.text
        if (key in sorted) return sorted[key]
        val samples by lazy(LazyThreadSafetyMode.NONE) { sound() }
        val person = line.speaker?.let { name ->
            all.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: all.size.also { all += Person(name) }
        } ?: fingerprint { samples }
        // The line's pitch as heard, else measured from its own sound (a subtitle file's lines are not heard).
        val pitch = line.heardPitchHz ?: if (person == null) {
            null
        } else {
            samples?.let { Prosody.measure(it, Fbank.RATE, line.text.count(Char::isLetter)).pitchHz }
        }
        person?.let { p -> pitch?.takeIf { it > 0 }?.let { all[p].pitches += it } }
        sorted[key] = person
        person
    }

    private fun fingerprint(sound: () -> FloatArray?): Int? {
        val embedding = embed ?: return null
        val samples = sound() ?: return null
        if (samples.size < SpeakerEmbedder.MIN_MS * Fbank.RATE / 1000) return null
        val print = embedding(samples) ?: return null
        val nearest = all.withIndex().filter { it.value.centroid != null }
            .maxByOrNull { cosine(it.value.centroid!!, print) }
        val index = nearest?.takeIf { cosine(it.value.centroid!!, print) >= same }?.index
            ?: all.size.also { all += Person(null) }
        val person = all[index]
        val before = person.centroid
        person.centroid = if (before == null) {
            print
        } else {
            SpeakerEmbedder.unit(FloatArray(print.size) { before[it] * person.count + print[it] })
        }
        person.count++
        return index
    }

    /** The voice of person [speaker], in a voice holding [speakers] speakers. */
    fun cast(speaker: Int, speakers: Int = 1): Cast = synchronized(all) {
        val usual = all[speaker].usualPitch()
        val woman = usual != null && usual >= Dub.WOMAN_HZ
        // The people of the same sex whose pitch is known, low to high; this one's place among them.
        val alike = all.mapNotNull { p -> p.usualPitch()?.takeIf { (it >= Dub.WOMAN_HZ) == woman } }.sorted()
        val pitch = if (usual == null || alike.size < 2) {
            0f
        } else {
            val rank = alike.indexOf(usual).toFloat()
            ((rank - (alike.size - 1) / 2f) * STEP_SEMITONES).coerceIn(-MAX_SEMITONES, MAX_SEMITONES)
        }
        val sid = if (speakers > 1) (speaker * SID_STRIDE) % speakers else 0
        Cast(woman, round(pitch * 4) / 4, sid)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    companion object {
        /** How alike two fingerprints (unit length) must be, by cosine, to be one person's: a hypothesis. */
        const val SAME = 0.5f

        /** How far apart the people of one sex are spoken, a step a person, and the most either way. */
        const val STEP_SEMITONES = 1f
        const val MAX_SEMITONES = 2f

        // Speakers of a voice of many taken this far apart: coprime to LibriTTS-R's 904, so no two people share one.
        private const val SID_STRIDE = 151
    }
}
