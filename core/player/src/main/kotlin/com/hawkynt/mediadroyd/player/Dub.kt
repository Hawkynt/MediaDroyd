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
    /** The voice band suppressed under a spoken line: a man's fundamental up to the consonants' hiss. */
    const val VOICE_LOW_HZ = 150.0
    const val VOICE_HIGH_HZ = 5000.0

    /** How long the dialogue takes to step back or return. */
    const val RAMP_MS = 50L

    /** How much faster than its own pace a line may be spoken to fit: half as fast again. */
    const val MAX_SPEED = 1.5f

    /**
     * How a line is spoken: [gainDb] louder (or quieter), at [rate] its pace, [pitch] semitones higher (or lower), its
     * [liveliness] the voice's own variation scaled (more: livelier, less: flatter) — from the text's hints, and from
     * the tone of the original line where it was heard (Prosody, #534).
     */
    data class Delivery(val gainDb: Float = 0f, val rate: Float = 1f, val pitch: Float = 0f, val liveliness: Float = 1f)

    /** A whisper: quieter and slower. A shout: louder and faster. */
    val WHISPER = Delivery(-8f, 0.9f)
    val SHOUT = Delivery(4f, 1.1f)

    /** A cue's spoken part: its [text], who says it where the cue names them ([speaker]), and its [delivery]. */
    data class Cleaned(val text: String, val speaker: String? = null, val delivery: Delivery = Delivery())

    /**
     * One line to speak: from [startMs] to [endMs], [text] by [speaker] with [delivery]; [spokenMs] how long the film's
     * own speech it replaces lasted, where [align] found it (its spoken line is fitted into that).
     */
    data class Line(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speaker: String? = null,
        val delivery: Delivery = Delivery(),
        val spokenMs: Long? = null,
        /** The pitch of the voice that spoke it in the film (Hz), where it was heard: a man's or a woman's (#532). */
        val heardPitchHz: Double? = null,
    )

    /** Below this a voice is taken for a man's, from it on for a woman's: between their usual ranges. */
    const val WOMAN_HZ = 165.0

    /** Whether [line] was spoken by a woman, by its heard pitch; null where that is not known. */
    fun womanSpoke(line: Line): Boolean? = line.heardPitchHz?.takeIf { it > 0 }?.let { it >= WOMAN_HZ }

    /** How far from its cue a line's speech is looked for. */
    const val ALIGN_MS = 700L

    /** How much longer than the speech it replaces a spoken line may run, before the next line allows no more. */
    private const val OVERRUN = 1.1

    /**
     * [lines] moved onto the film's own speech ([speech]: SubtitleSync.Speech's verdicts, SubtitleSync.RATE a second):
     * each from the first word to the last of the speech in its cue — speech starting up to [ALIGN_MS] before it or
     * running on up to as long after it — never back into the line before; a line with no speech near it keeps its
     * cue's times. So a dubbed line starts where the actor's did and lasts as long ([Line.spokenMs]).
     */
    fun align(lines: List<Line>, speech: BooleanArray): List<Line> {
        val step = 1000L / SubtitleSync.RATE
        fun spoken(ms: Long) = (ms / step).toInt().let { it in speech.indices && speech[it] }
        val out = ArrayList<Line>(lines.size)
        var floor = 0L
        for (line in lines) {
            val from = maxOf(floor, line.startMs - ALIGN_MS)
            val to = line.endMs + ALIGN_MS
            // The speech runs inside the cue, or reaching into it from just before or after.
            var first = -1L
            var last = -1L
            var ms = from
            while (ms <= to) {
                if (spoken(ms)) {
                    var end = ms
                    while (end + step <= to && spoken(end + step)) end += step
                    val overlaps = end >= line.startMs - ALIGN_MS && ms <= line.endMs
                    if (overlaps) {
                        if (first < 0) first = ms
                        last = end + step
                    }
                    ms = end + step
                } else {
                    ms += step
                }
            }
            val moved = if (first < 0) line else line.copy(startMs = first, endMs = last, spokenMs = last - first)
            out += moved
            floor = moved.endMs
        }
        return out
    }

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
     * before a colon taken off, descriptions in brackets or parentheses dropped — their whispering or shouting kept as
     * the delivery — songs (♪) not spoken; a line in capitals or ending in ! shouted, its capitals made sentence case.
     */
    fun clean(words: String): Cleaned {
        // Every brace escaped: Android's regular expressions (ICU) refuse a bare "}" the JVM's accept.
        var text = words.replace(Regex("""\{[^}]*\}"""), "").replace(Regex("""<[^>]*>"""), "")
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

    /** A voice that speaks a line (the platforms' Piper): mono samples at [sampleRate], −1 … 1. */
    interface LineSpeaker : AutoCloseable {
        val sampleRate: Int

        /**
         * [text] at [lengthScale] (1 the voice's pace, smaller faster) by [speaker], its own variation scaled by
         * [liveliness]; null where it cannot.
         */
        fun speak(text: String, lengthScale: Float, speaker: Int = 0, liveliness: Float = 1f): FloatArray?

        override fun close() {}
    }

    /** How often a line is spoken again to fit: its length does not follow the length scale in proportion. */
    private const val FIT_TRIES = 3
    private const val FIT_MARGIN = 0.95f

    /** A spoken line: its samples, to be played at [rate] (the voice's own, raised where its pitch is). */
    class Spoken(val pcm: FloatArray, val rate: Int)

    /**
     * [line] spoken by [speaker] to fit before [nextStartMs] (null: no limit): at its delivery's pace first, then —
     * where too long — again with the length scale cut by how much it ran over and a little more, up to [FIT_TRIES]
     * times, never past [MAX_SPEED]; its delivery's loudness and liveliness applied, and its pitch: spoken that much
     * slower and played that much faster, so it sounds higher (or lower) and lasts as long. The last try where none
     * fits; null where the voice cannot speak it.
     */
    fun fit(line: Line, speaker: LineSpeaker, nextStartMs: Long?, voice: Int = 0): Spoken? {
        val shift = Math.pow(2.0, line.delivery.pitch / 12.0).toFloat()
        val rate = (speaker.sampleRate * shift).toInt()
        val lively = line.delivery.liveliness
        var scale = 1f / line.delivery.rate
        var spoken = speaker.speak(line.text, scale * shift, voice, lively) ?: return null
        val beforeNext = nextStartMs?.let { it - line.startMs }
        val asLong = line.spokenMs?.let { (it * OVERRUN).toLong() }
        val room = listOfNotNull(beforeNext, asLong).minOrNull()
        repeat(FIT_TRIES - 1) {
            val ms = spoken.size * 1000L / rate
            if (room == null || ms <= room || scale <= 1f / MAX_SPEED + 1e-4f) return@repeat
            // A little more than it ran over: the pauses and the voice's own variation do not shrink with it.
            scale = (scale * room / ms * FIT_MARGIN).coerceAtLeast(1f / MAX_SPEED)
            spoken = speaker.speak(line.text, scale * shift, voice, lively) ?: return@repeat
        }
        val gain = Math.pow(10.0, line.delivery.gainDb / 20.0).toFloat()
        val pcm = if (gain == 1f) spoken else FloatArray(spoken.size) { (spoken[it] * gain).coerceIn(-1f, 1f) }
        return Spoken(pcm, rate)
    }

    /**
     * Speaks the [lines] of a subtitle track — a file's, or those made from the speech as it is heard, so the list may
     * grow — onto [track] as the film plays: [ahead] those starting from the position up to [AHEAD_MS] later that are
     * not spoken yet (known by their start), each fitted before the next line ([fit]), scaled to 16-bit. One line at a
     * time, off the main thread; a seek simply speaks from the new position on. What lies more than [BEHIND_MS] behind
     * the position is let go (Track.forget) — a whole film's lines would fill the memory (#537) — and spoken again
     * should a seek back reach it.
     */
    class Speaking(
        private val lines: () -> List<Line>,
        private val speakerFor: (Line) -> LineSpeaker,
        private val track: Track,
    ) {
        constructor(lines: List<Line>, speaker: LineSpeaker, track: Track) : this({ lines }, { speaker }, track)
        constructor(lines: () -> List<Line>, speaker: LineSpeaker, track: Track) : this(lines, { speaker }, track)

        // The lines spoken, by start and text: a line laid on the speech anew (Dub.align, as more of it is found)
        // moves by some milliseconds and is still the one spoken.
        private val spoken = ArrayList<Pair<Long, String>>()

        private fun said(line: Line) =
            spoken.any { (at, text) -> text == line.text && kotlin.math.abs(at - line.startMs) <= ALIGN_MS }

        /** Told of each line as it is spoken: how long its voice took, and whether it could be spoken at all. */
        var onSpoken: ((line: Line, tookMs: Long, spoken: Boolean) -> Unit)? = null

        /** Speaks what is due from [positionMs]; returns how many lines it spoke. Blocks. */
        fun ahead(positionMs: Long, voice: Int = 0): Int {
            val behind = positionMs - BEHIND_MS
            track.forget(behind)
            spoken.removeAll { (at, _) -> at < behind }
            var count = 0
            val now = lines()
            for ((i, line) in now.withIndex()) {
                if (line.endMs < positionMs || said(line)) continue
                if (line.startMs > positionMs + AHEAD_MS) break
                // Under way already (the voice-over came on, or the line was heard, too late): left out, not spoken
                // from its middle.
                if (line.startMs < positionMs - LATE_MS) {
                    spoken += line.startMs to line.text
                    continue
                }
                val next = now.getOrNull(i + 1)?.startMs
                val began = System.nanoTime()
                val said = fit(line, speakerFor(line), next, voice)
                onSpoken?.invoke(line, (System.nanoTime() - began) / 1_000_000, said != null)
                spoken += line.startMs to line.text
                if (said != null) {
                    val pcm = said.pcm
                    track.add(line.startMs, FloatArray(pcm.size) { pcm[it] * FULL_SCALE }, said.rate)
                    track.duck(line.startMs..line.endMs)
                    count++
                }
            }
            return count
        }
    }

    /** How far a line may be under way and still be spoken: past it, it is left out. */
    const val LATE_MS = 300L

    /** How far ahead of the position lines are spoken. */
    const val AHEAD_MS = 20_000L

    /** How long a spoken line is kept behind the position: a seek back that far still hears it at once. */
    const val BEHIND_MS = 60_000L
    private const val FULL_SCALE = 32_767f

    /**
     * The spoken lines on the film's timeline, each [add]ed clip kept at the voice's own rate from its start and read
     * at whatever rate the sound plays at ([voiceAt]), resampled linearly (speech: nothing above a few kHz matters),
     * so one track serves every output. Thread-safe: lines are added as they are spoken while the sound plays.
     */
    class Track {
        private class Clip(val startMs: Long, val pcm: FloatArray, val rate: Int)

        private val clips = ArrayList<Clip>()

        // Where the film's own lines ran that are spoken over: its voice steps back there too, however short the
        // spoken line is.
        private val ducks = ArrayList<LongRange>()

        /** Has the film's voice step back over [range] (ms) as well: the original line a spoken one replaces. */
        fun duck(range: LongRange) = synchronized(clips) { ducks += range }

        /** [pcm] (mono, at [rate]) from [startMs] on. */
        fun add(startMs: Long, pcm: FloatArray, rate: Int) {
            if (pcm.isEmpty() || rate <= 0) return
            synchronized(clips) {
                clips += Clip(startMs, pcm, rate)
                clips.sortBy { it.startMs }
            }
        }

        /** How many spoken lines it holds. */
        val lines: Int get() = synchronized(clips) { clips.size }

        /** Lets go of the lines, and the film's lines stepped back under them, that ended before [beforeMs]. */
        fun forget(beforeMs: Long) = synchronized(clips) {
            clips.removeAll { it.startMs + it.pcm.size * 1000L / it.rate < beforeMs }
            ducks.removeAll { it.last < beforeMs }
        }

        /** Forgets every line: another track, or another video. */
        fun clear() = synchronized(clips) {
            clips.clear()
            ducks.clear()
        }

        /**
         * The voice for [voice].size frames at [rate] from [fromMs] into [voice], and where a line speaks into
         * [speaking] (as long); whether any does.
         */
        fun voiceAt(fromMs: Long, rate: Int, voice: FloatArray, speaking: BooleanArray? = null): Boolean =
            voiceAtFrame(fromMs * rate / 1000, rate, voice, speaking)

        /** As [voiceAt], from frame [fromFrame] of the film at [rate]. */
        fun voiceAtFrame(fromFrame: Long, rate: Int, voice: FloatArray, speaking: BooleanArray? = null): Boolean {
            voice.fill(0f)
            speaking?.fill(false)
            val n = voice.size
            var any = false
            synchronized(clips) {
                for (clip in clips) {
                    val start = clip.startMs * rate / 1000
                    val length = clip.pcm.size.toLong() * rate / clip.rate
                    if (start + length <= fromFrame) continue
                    if (start >= fromFrame + n) break
                    val from = maxOf(fromFrame, start)
                    val to = minOf(fromFrame + n, start + length)
                    for (f in from until to) {
                        val at = (f - start).toDouble() * clip.rate / rate
                        val a = at.toInt().coerceAtMost(clip.pcm.size - 1)
                        val b = (a + 1).coerceAtMost(clip.pcm.size - 1)
                        val w = (at - a).toFloat()
                        val k = (f - fromFrame).toInt()
                        voice[k] += clip.pcm[a] * (1 - w) + clip.pcm[b] * w
                        speaking?.set(k, true)
                    }
                    any = true
                }
                if (speaking != null) {
                    for (range in ducks) {
                        val start = range.first * rate / 1000
                        val end = range.last * rate / 1000
                        if (end < fromFrame || start >= fromFrame + n) continue
                        for (f in maxOf(start, fromFrame) until minOf(end, fromFrame + n)) {
                            speaking[(f - fromFrame).toInt()] = true
                        }
                        any = true
                    }
                }
            }
            return any
        }
    }

    /** What becomes of the film's own sound under a spoken line. */
    enum class Original {
        /**
         * The dialogue channel removed: a file whose channels arrive unmixed (3, 5.1, 7.1: front left, front right,
         * centre, …) has its centre channel — where films put the dialogue — silent while a line speaks. Where the
         * sound has no centre channel it falls back to [BAND].
         */
        CHANNEL,

        /**
         * The voice band suppressed: the voice band (150 Hz … 5 kHz) taken out of where the dialogue is — the centre
         * channel, else stereo's middle (L + R), else the one channel — the bass, the highs and the sides staying.
         */
        BAND,

        /** All of the film's sound silent while a line speaks. */
        MUTE,
    }

    /** Whether sound of [channels] channels has a centre channel of its own (Original.CHANNEL can remove it). */
    fun hasCentre(channels: Int): Boolean = channels == 3 || channels >= 5

    /**
     * The spoken lines of a [Track] mixed into the film's sound (interleaved 16-bit, [channels] at [rate]): while a
     * line speaks the original steps back as [original] says, over [RAMP_MS] either way, and the voice goes where the
     * dialogue was — the centre channel where there is one, else the middle of both, else the one channel. Near full
     * scale a soft knee bends the peaks.
     */
    class Mixer(private val rate: Int, channels: Int, original: Original = Original.BAND) {
        private val channels = channels.coerceAtLeast(1)
        private val centred = hasCentre(this.channels)
        private val original = if (original == Original.CHANNEL && !centred) Original.BAND else original
        private val step = 1f / (rate * RAMP_MS / 1000f)
        private var gain = 1f
        private var voice = FloatArray(0)
        private var speaking = BooleanArray(0)

        // The film's frame the next buffer starts at, where it follows on: the engines give a buffer's time in whole
        // milliseconds, which a buffer's length is not — taken as it is, the voice would jump back and forth by up
        // to a millisecond's frames at every buffer, and crack (#541).
        private var nextFrame = Long.MIN_VALUE

        // What is left of where the dialogue is without its voice band: below it and above it, each through two
        // sections (Linkwitz–Riley, 24 dB an octave), kept running so a line starting finds them settled. Faded to
        // rather than the band subtracted: a filter's phase would make a subtraction boost the band's edges.
        private val below = listOf(Biquad.lowPass(rate, VOICE_LOW_HZ), Biquad.lowPass(rate, VOICE_LOW_HZ))
        private val above = listOf(Biquad.highPass(rate, VOICE_HIGH_HZ), Biquad.highPass(rate, VOICE_HIGH_HZ))

        private fun withoutVoice(x: Float): Float =
            below.fold(x) { y, section -> section.next(y) } + above.fold(x) { y, section -> section.next(y) }
        private val banded = this.original == Original.BAND

        /** Mixes [track] into [frames] frames of [samples] from [offset], the first of them at [ptsMs]. */
        fun process(samples: ShortArray, offset: Int, frames: Int, ptsMs: Long, track: Track) {
            if (frames <= 0) return
            if (voice.size != frames) {
                voice = FloatArray(frames)
                speaking = BooleanArray(frames)
            }
            val told = ptsMs * rate / 1000
            val follows = nextFrame != Long.MIN_VALUE && kotlin.math.abs(told - nextFrame) <= rate * FOLLOW_MS / 1000
            val fromFrame = if (follows) nextFrame else told
            nextFrame = fromFrame + frames
            val any = track.voiceAtFrame(fromFrame, rate, voice, speaking)
            if (!any && gain == 1f && !banded) return
            for (f in 0 until frames) {
                val target = if (speaking[f]) 0f else 1f
                gain = if (gain < target) minOf(target, gain + step) else maxOf(target, gain - step)
                val at = offset + f * channels
                val v = voice[f]
                when {
                    original == Original.MUTE -> for (c in 0 until channels) {
                        val spoken = if (channels == 1 || (centred && c == CENTRE) || (!centred && c < 2)) v else 0f
                        samples[at + c] = SoftKnee.apply(samples[at + c] * gain + spoken)
                    }
                    original == Original.CHANNEL ->
                        samples[at + CENTRE] = SoftKnee.apply(samples[at + CENTRE] * gain + v)
                    centred || channels == 1 -> {
                        val c = if (centred) CENTRE else 0
                        val x = samples[at + c].toFloat()
                        samples[at + c] = SoftKnee.apply(x * gain + withoutVoice(x) * (1 - gain) + v)
                    }
                    else -> {
                        val l = samples[at].toFloat()
                        val r = samples[at + 1].toFloat()
                        val mid = (l + r) / 2
                        val out = mid * gain + withoutVoice(mid) * (1 - gain) + v
                        val side = (l - r) / 2
                        samples[at] = SoftKnee.apply(out + side)
                        samples[at + 1] = SoftKnee.apply(out - side)
                    }
                }
            }
        }

        private companion object {
            const val CENTRE = 2

            // How far a buffer's time may lie from where the last one ended and still follow on: more is a seek.
            const val FOLLOW_MS = 5L
        }
    }
}
