package com.hawkynt.mediadroyd.player

/**
 * Spoken subtitles (#531): the lines of a subtitle track spoken by a voice on the device and mixed into the film's
 * sound. This is the platform-free part: what a cue says and how ([lines], [clean]: its words without tags, speaker or
 * sound descriptions, and a [Delivery] from its hints — whispering, shouting), how fast a line must be spoken to fit
 * its time ([lengthScale], the voice model's own length scale), the spoken lines laid on a timeline ([Track]) and mixed
 * in with the film's dialogue stepping back ([Mixer]). The voices themselves (Piper's VITS on ONNX Runtime) are the
 * platforms'.
 */
object Dub {
    /** How far the film's own dialogue steps back under a spoken line: to an eighth. */
    const val DUCK = 0.125f

    /** How long the dialogue takes to step back or return. */
    const val RAMP_MS = 50L

    /** How much faster than its own pace a line may be spoken to fit: half as fast again. */
    const val MAX_SPEED = 1.5f

    /** How a line is spoken: [gainDb] louder (or quieter), at [rate] its pace. */
    data class Delivery(val gainDb: Float = 0f, val rate: Float = 1f)

    /** A whisper: quieter and slower. A shout: louder and faster. */
    val WHISPER = Delivery(-8f, 0.9f)
    val SHOUT = Delivery(4f, 1.1f)

    /** A cue's spoken part: its [text], who says it where the cue names them ([speaker]), and its [delivery]. */
    data class Cleaned(val text: String, val speaker: String? = null, val delivery: Delivery = Delivery())

    /** One line to speak: from [startMs] to [endMs], [text] by [speaker] with [delivery]. */
    data class Line(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speaker: String? = null,
        val delivery: Delivery = Delivery(),
    )

    private val TIMED = Regex(
        """(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{3})""",
    )
    private val DIALOGUE = Regex(
        """^Dialogue:\s*[^,]*,(\d+):(\d{2}):(\d{2})\.(\d{2}),(\d+):(\d{2}):(\d{2})\.(\d{2}),(?:[^,]*,){5}(.*)$""",
    )

    /** The lines to speak in a subtitle file's [text] (SubRip, WebVTT, SSA/ASS), cues with nothing to say left out. */
    fun lines(text: String): List<Line> {
        val raw = ArrayList<Triple<Long, Long, String>>()
        val all = text.lines()
        var i = 0
        while (i < all.size) {
            val line = all[i]
            val timed = TIMED.find(line)
            val dialogue = if (timed == null) DIALOGUE.find(line.trimStart()) else null
            when {
                timed != null -> {
                    val g = timed.groupValues
                    val words = ArrayList<String>()
                    i++
                    while (i < all.size && all[i].isNotBlank()) words += all[i++]
                    raw += Triple(ms(g[1], g[2], g[3], g[4], 1), ms(g[5], g[6], g[7], g[8], 1), words.joinToString(" "))
                }
                dialogue != null -> {
                    val g = dialogue.groupValues
                    raw += Triple(ms(g[1], g[2], g[3], g[4], 10), ms(g[5], g[6], g[7], g[8], 10), g[9])
                    i++
                }
                else -> i++
            }
        }
        return raw.mapNotNull { (start, end, words) ->
            val cleaned = clean(words)
            if (cleaned.text.isEmpty()) null else Line(start, end, cleaned.text, cleaned.speaker, cleaned.delivery)
        }
    }

    private fun ms(h: String, m: String, s: String, f: String, unit: Int): Long =
        ((h.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.toLong() * unit

    private val WHISPERED = Regex("""\b(whisper\w*|quietly|softly|murmur\w*|under (his|her|their) breath)\b""")
    private val SHOUTED = Regex("""\b(shout\w*|yell\w*|scream\w*|screech\w*|bellow\w*|loudly)\b""")
    private val SPEAKER = Regex("""^([A-Z][A-Z0-9 .'-]{0,30}):\s+""")

    /**
     * A cue's raw [words] as spoken: ASS override blocks, HTML-like tags and line breaks gone, a speaker's name
     * before a colon taken off, descriptions in brackets or parentheses dropped — their whispering or shouting kept as the
     * delivery — songs (♪) not spoken; a line in capitals or ending in ! shouted, its capitals made sentence case.
     */
    fun clean(words: String): Cleaned {
        var text = words.replace(Regex("""\{[^}]*}"""), "").replace(Regex("""<[^>]*>"""), "")
            .replace("\\N", " ").replace("\\n", " ")
        if ('♪' in text || '♫' in text) return Cleaned("")
        var delivery = Delivery()
        for (hint in Regex("""[(\[]([^)\]]*)[)\]]""").findAll(text).map { it.groupValues[1].lowercase() }) {
            if (WHISPERED.containsMatchIn(hint)) delivery = WHISPER
            if (SHOUTED.containsMatchIn(hint)) delivery = SHOUT
        }
        text = text.replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
        text = text.replace(Regex("""(^|\s)-\s+"""), " ").replace(Regex("""\s+"""), " ").trim()
        val speaker = SPEAKER.find(text)?.groupValues?.get(1)?.trim()
        if (speaker != null) text = text.substring(SPEAKER.find(text)!!.range.last + 1).trim()
        val letters = text.filter { it.isLetter() }
        val capitals = letters.length >= 4 && letters.all { it.isUpperCase() }
        if (capitals) text = sentenceCase(text)
        if ((capitals || text.endsWith("!")) && delivery == Delivery()) delivery = SHOUT
        return Cleaned(text, speaker, delivery)
    }

    private fun sentenceCase(text: String): String {
        val out = StringBuilder(text.length)
        var start = true
        for (c in text.lowercase()) {
            out.append(if (start && c.isLetter()) c.uppercaseChar() else c)
            if (c.isLetter()) start = false
            if (c in ".!?") start = true
        }
        return out.toString()
    }

    /**
     * The voice model's length scale for a line it speaks in [spokenMs] at scale 1: its delivery's pace (1 / [rate]),
     * shortened where that would run past the next line's start ([nextStartMs], null for none) from [cueStartMs] — but
     * never more than [MAX_SPEED] times faster.
     */
    fun lengthScale(spokenMs: Long, cueStartMs: Long, nextStartMs: Long?, rate: Float): Float {
        val natural = 1f / rate
        val room = nextStartMs?.let { it - cueStartMs } ?: return natural
        if (spokenMs <= 0 || room <= 0) return 1f / MAX_SPEED
        return minOf(natural, room.toFloat() / spokenMs).coerceAtLeast(1f / MAX_SPEED)
    }

    /**
     * The spoken lines on the film's timeline at the output's [rate]: each [add]ed clip from its start, resampled from
     * the voice's own rate (linearly: speech, nothing above a few kHz that matters). Thread-safe: lines are added as
     * they are spoken while the sound plays.
     */
    class Track(private val rate: Int) {
        private class Clip(val startFrame: Long, val pcm: FloatArray)

        private val clips = ArrayList<Clip>()

        /** [pcm] (mono, at [sourceRate]) from [startMs] on. */
        fun add(startMs: Long, pcm: FloatArray, sourceRate: Int = rate) {
            val samples = if (sourceRate == rate) {
                pcm
            } else {
                val length = (pcm.size.toLong() * rate / sourceRate).toInt()
                FloatArray(length) { i ->
                    val at = i.toDouble() * sourceRate / rate
                    val a = at.toInt().coerceAtMost(pcm.size - 1)
                    val b = (a + 1).coerceAtMost(pcm.size - 1)
                    val f = (at - a).toFloat()
                    pcm[a] * (1 - f) + pcm[b] * f
                }
            }
            synchronized(clips) {
                clips += Clip(startMs * rate / 1000, samples)
                clips.sortBy { it.startFrame }
            }
        }

        /** Forgets every line: another track, or another video. */
        fun clear() = synchronized(clips) { clips.clear() }

        /**
         * The voice for [voice].size frames from [fromMs] into [voice], and where a line speaks into [speaking] (as
         * long); whether any does.
         */
        fun voiceAt(fromMs: Long, voice: FloatArray, speaking: BooleanArray? = null): Boolean =
            fill(fromMs * rate / 1000, voice, speaking)

        internal fun fill(fromFrame: Long, voice: FloatArray, speaking: BooleanArray?): Boolean {
            voice.fill(0f)
            speaking?.fill(false)
            val n = voice.size
            var any = false
            synchronized(clips) {
                for (clip in clips) {
                    val end = clip.startFrame + clip.pcm.size
                    if (end <= fromFrame) continue
                    if (clip.startFrame >= fromFrame + n) break
                    val from = maxOf(fromFrame, clip.startFrame)
                    val to = minOf(fromFrame + n, end)
                    for (f in from until to) {
                        val at = (f - fromFrame).toInt()
                        voice[at] += clip.pcm[(f - clip.startFrame).toInt()]
                        speaking?.set(at, true)
                    }
                    any = true
                }
            }
            return any
        }
    }

    /**
     * The spoken lines of a [Track] mixed into the film's sound (interleaved 16-bit, [channels] at [rate]): while a
     * line speaks the dialogue steps back to [DUCK] over [RAMP_MS] — a file's centre channel where its channels arrive
     * unmixed (3, 5.1, 7.1: front left, front right, centre, …), else stereo's middle, (L + R) / 2, the sides staying —
     * and the voice goes where the dialogue was. Near full scale a soft knee bends the peaks.
     */
    class Mixer(private val rate: Int, channels: Int) {
        private val channels = channels.coerceAtLeast(1)
        private val step = 1f / (rate * RAMP_MS / 1000f)
        private var gain = 1f
        private var voice = FloatArray(0)
        private var speaking = BooleanArray(0)

        /** Mixes [track] into [frames] frames of [samples] from [offset], the first of them at [ptsMs]. */
        fun process(samples: ShortArray, offset: Int, frames: Int, ptsMs: Long, track: Track) {
            if (frames <= 0) return
            if (voice.size < frames) {
                voice = FloatArray(frames)
                speaking = BooleanArray(frames)
            }
            val v = if (voice.size == frames) voice else FloatArray(frames)
            val s = if (speaking.size == frames) speaking else BooleanArray(frames)
            val any = track.fill(ptsMs * rate / 1000, v, s)
            if (!any && gain == 1f) return
            for (f in 0 until frames) {
                val target = if (s[f]) DUCK else 1f
                gain = if (gain < target) minOf(target, gain + step) else maxOf(target, gain - step)
                val at = offset + f * channels
                when {
                    channels == 1 -> samples[at] = SoftKnee.apply(samples[at] * gain + v[f])
                    channels == 3 || channels >= 5 -> samples[at + CENTRE] =
                        SoftKnee.apply(samples[at + CENTRE] * gain + v[f])
                    else -> {
                        val l = samples[at].toFloat()
                        val r = samples[at + 1].toFloat()
                        val mid = (l + r) / 2 * gain + v[f]
                        val side = (l - r) / 2
                        samples[at] = SoftKnee.apply(mid + side)
                        samples[at + 1] = SoftKnee.apply(mid - side)
                    }
                }
            }
        }

        private companion object {
            const val CENTRE = 2
        }
    }
}
