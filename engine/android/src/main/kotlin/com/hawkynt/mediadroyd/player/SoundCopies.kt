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
 * The sound of the films Media3 reads, copied as its extractors hand it over (#535): every compressed sample of every
 * sound track, kept for the last [TRAIL_MS] of what was read (Media3 loads up to two minutes ahead of playback) — so
 * the analysis (Whisper, the speech, the tone) hears the film without reading its file again ([open], TappedSound).
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

    /** A film's sound tracks, by the extractor's track id, and the one playing ([select]). */
    class Film {
        val tracks = ConcurrentHashMap<Int, Track>()

        /** The id (Format.id) of the sound track playing; null for the first. */
        @Volatile var selected: String? = null

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
    fun of(location: String): Film = synchronized(films) { films.getOrPut(location) { Film() } }

    /** The copies of [location] where any were made; else null. */
    fun find(location: String): Film? = synchronized(films) { films[location] }

    /** The sound track playing in [location] is the one of Format.id [id]. */
    fun select(location: String, id: String?) {
        of(location).selected = id
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
                    return Copying(track, film.tracks.getOrPut(id) { Track(id) })
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
    internal class Copying(private val inner: TrackOutput, private val track: Track) : TrackOutput {
        private var pending = ByteArray(1 shl 14)
        private var length = 0

        override fun format(format: Format) {
            track.format = format
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
            if (start >= 0 && size > 0) {
                val sample = Sample(pending.copyOfRange(start, end), timeUs)
                track.samples.offer(sample, timeUs / 1000, size, track.id, key = true)
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

    // A sound track's copies at most: TrueHD's 18 Mbit/s for two minutes; AC3's 640 kbit/s keeps under 10 MB.
    private const val MAX_BYTES = 256L shl 20

    // The films whose copies are kept: the one playing and the next one Media3 already reads.
    private const val FILMS = 3
}
