package com.hawkynt.mediadroyd.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap

/**
 * The sound of the films Media3 reads, copied as its extractors hand it over (#535): every compressed sample of the
 * sound track playing, kept for the last [TRAIL_MS] of what was read (Media3 loads up to two minutes ahead of playback)
 * and within [MAX_BYTES] — so the analysis (Whisper, the speech, the tone) hears the film without reading its file
 * again ([open], TappedSound). The other tracks are not copied, and a film another one was chosen after lets go of its
 * copies: the copies live on the Java heap, which a lossless track would otherwise fill (#537).
 * Whatever extractor takes the file — Media3's own or FFmpeg's — is wrapped ([tapping]); a seek outside what Media3
 * holds starts the copies again. Keyed by the location as Media3 is given it (playableUri).
 */
@OptIn(UnstableApi::class)
object SoundCopies {
    /** A compressed sample of a sound track: its [data] and its time ([timeUs], film time). */
    class Sample(val data: ByteArray, val timeUs: Long)

    /** One sound track's copies: its [format] once told, its samples. */
    class Track(val id: Int) {
        /** The track's format as the extractor told it; null until then. */
        @Volatile var format: Format? = null

        /** Its samples, trailing what Media3 has read. */
        val samples = ReadAhead<Sample>(aheadMs = 0, maxBytes = MAX_BYTES, behindMs = 0, trailMs = TRAIL_MS) {}
    }

    // The films made so far, numbering each: a film chosen lets go of those made before it.
    private var made = 0L

    /** A film's sound tracks, by the extractor's track id, and the one playing ([select]). */
    class Film internal constructor(internal val number: Long) {
        val tracks = ConcurrentHashMap<Int, Track>()

        /** The id (Format.id) of the sound track playing; null for the first. */
        @Volatile var selected: String? = null

        // False once another film was chosen after it: nothing is copied any more.
        @Volatile internal var live = true

        // The track playing as last worked out: asked for every sample, worked out only when a track or the choice
        // changes.
        @Volatile private var known: Track? = null

        internal fun copies(track: Track): Boolean {
            if (!live) return false
            val playing = known ?: playing().also { known = it }
            return playing === track
        }

        internal fun changed() {
            known = null
        }

        /** The track playing: the selected one, matched by the id after any period prefix, else the first told. */
        fun playing(): Track? {
            val told = tracks.values.filter { it.format != null }.sortedBy { it.id }
            val want = selected?.substringAfterLast(':')
            return told.firstOrNull { it.format?.id?.substringAfterLast(':') == want } ?: told.firstOrNull()
        }

        internal fun reset() = tracks.values.forEach { it.samples.reset() }

        internal fun end() = tracks.values.forEach { it.samples.end() }
    }

    private val films = object : LinkedHashMap<String, Film>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Film>) = size > FILMS
    }

    /** The copies of the film at [location] (as Media3 is given it), made where there are none yet. */
    fun of(location: String): Film = synchronized(films) { films.getOrPut(location) { Film(made++) } }

    /** The copies of [location] where any were made; else null. */
    fun find(location: String): Film? = synchronized(films) { films[location] }

    /** The sound track playing in [location] is the one of Format.id [id]. */
    fun select(location: String, id: String?) {
        val film = of(location)
        film.selected = id
        film.live = true
        film.changed()
        val playing = film.playing()
        film.tracks.values.filter { it !== playing }.forEach { it.samples.reset() }
        // The films chosen before it are left: their copies go. Those made after it (the next one, read ahead) stay.
        val left = synchronized(films) { films.values.filter { it.number < film.number } }
        for (before in left) {
            before.live = false
            before.reset()
        }
    }

    /** [inner] reading [location], its sound copied on the way. */
    fun tapping(inner: Extractor, location: String): Extractor = Tapping(inner, location)

    private class Tapping(private val inner: Extractor, private val location: String) : Extractor {
        private var film: Film? = null

        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

        override fun init(output: ExtractorOutput) {
            val film = of(location).also { film = it }
            film.reset()
            inner.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val track = output.track(id, type)
                    if (type != C.TRACK_TYPE_AUDIO) return track
                    return Copying(track, film.tracks.getOrPut(id) { Track(id) }, film)
                }

                override fun endTracks() = output.endTracks()

                override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
            })
        }

        // At the file's end the copies end too: a read of the last stretch then ends instead of waiting for more.
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            inner.read(input, seekPosition).also { result ->
                if (result == Extractor.RESULT_END_OF_INPUT) film?.end()
            }

        override fun seek(position: Long, timeUs: Long) {
            film?.reset()
            inner.seek(position, timeUs)
        }

        override fun release() = inner.release()

        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
    }

    /**
     * A sound track's output that copies each sample as it is finished: the bytes handed over are kept until the
     * sample's metadata says which of them it was (the last [size] before the last [offset]).
     */
    internal class Copying(
        private val inner: TrackOutput,
        private val track: Track,
        private val film: Film,
    ) : TrackOutput {
        private var pending = ByteArray(1 shl 14)
        private var length = 0

        override fun format(format: Format) {
            track.format = format
            film.changed()
            inner.format(format)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            room(this.length + length)
            val read = input.read(pending, this.length, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            val data = ParsableByteArray(pending, this.length + read)
            data.setPosition(this.length)
            inner.sampleData(data, read, sampleDataPart)
            this.length += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            room(this.length + length)
            val start = data.position
            data.readBytes(pending, this.length, length)
            data.setPosition(start)
            inner.sampleData(data, length, sampleDataPart)
            this.length += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            val end = length - offset
            val start = end - size
            if (start >= 0 && size > 0 && film.copies(track)) {
                val sample = Sample(pending.copyOfRange(start, end), timeUs)
                track.samples.offer(sample, timeUs / 1000, size + SAMPLE_OVERHEAD, track.id, key = true)
            }
            System.arraycopy(pending, end.coerceAtLeast(0), pending, 0, offset.coerceAtMost(length))
            length = offset.coerceAtMost(length)
        }

        private fun room(size: Int) {
            if (size > pending.size) pending = pending.copyOf(maxOf(size, pending.size * 2))
        }
    }

    /**
     * The sound playing in [location] from [fromMs], decoded from its copies (TappedSound); null where none are kept
     * near there — the analysis then reads the file itself. Where the copies' place is passed, [fallback] reads on.
     */
    fun open(location: String, fromMs: Long, fallback: (atMs: Long) -> FilmSound.Stream?): FilmSound.Stream? {
        val track = find(location)?.playing() ?: return null
        val format = track.format ?: return null
        if (!track.samples.covers(fromMs)) return null
        return TappedSound(track.samples.cursor(fromMs), format, fromMs, fallback)
    }

    /**
     * How much of what Media3 read is kept: the two minutes it reads ahead, and a minute and a half behind playback —
     * a hearing that fell behind still finds the sound there.
     */
    const val TRAIL_MS = 210_000L

    /**
     * A sound track's copies at most, on a Java heap of some 512 MB: AC3's 640 kbit/s and DTS's 1.5 Mbit/s keep the
     * whole trail; a lossless track (TrueHD, DTS-HD MA, some 3–18 Mbit/s) keeps its last half minute or more, and an
     * analysis that falls behind that reads the file itself.
     */
    const val MAX_BYTES = 32L shl 20

    // What a sample costs beyond its bytes: the Sample, its array's header, the buffer's entry.
    private const val SAMPLE_OVERHEAD = 64

    // The films whose copies are kept: the one playing and the next one Media3 already reads.
    private const val FILMS = 2
}
