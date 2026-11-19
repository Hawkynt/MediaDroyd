package com.hawkynt.mediadroyd.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sound copied as Media3's extractors read it, for the analysis (SoundCopies, TappedSound, #535): the copies are
 * the very samples Media3 is handed, however the file comes in; the copies of raw sound decode to the analysis' mono.
 * Under Robolectric, so it runs with the screenshots' tier (-Pscreenshots).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(UnstableApi::class)
class SoundCopiesTest {
    private class Sample(val timeUs: Long, val data: ByteArray)

    private class Track(val type: Int) : TrackOutput {
        val samples = mutableListOf<Sample>()
        private var pending = ByteArray(0)

        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            if (read == C.RESULT_END_OF_INPUT) return read
            pending += bytes.copyOf(read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending += bytes
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val end = pending.size - offset
            samples += Sample(timeUs, pending.copyOfRange(end - size, end))
            pending = pending.copyOfRange(end, pending.size)
        }
    }

    // The clip read to its end through [extractor], the file handed over at most [chunk] bytes at a time.
    private fun extract(extractor: Extractor, chunk: Int): Map<Int, Track> {
        val file = checkNotNull(javaClass.getResourceAsStream("/contract/clip.mp4")).readBytes()
        val tracks = linkedMapOf<Int, Track>()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track(type) }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        var position = 0
        val reader = DataReader { buffer, offset, length ->
            if (position >= file.size) return@DataReader C.RESULT_END_OF_INPUT
            val n = minOf(length, chunk, file.size - position)
            System.arraycopy(file, position, buffer, offset, n)
            position += n
            n
        }
        val seek = PositionHolder()
        var input = DefaultExtractorInput(reader, 0, file.size.toLong())
        while (true) {
            when (extractor.read(input, seek)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    position = seek.position.toInt()
                    input = DefaultExtractorInput(reader, seek.position, file.size.toLong())
                }
                else -> break
            }
        }
        return tracks
    }

    @Test
    fun `the sound's copies are the very samples Media3 is handed, read whole or a few bytes at a time`() {
        for (chunk in listOf(1 shl 20, 7)) {
            val location = "test://clip-$chunk"
            val tracks = extract(SoundCopies.tapping(Mp4Extractor(DefaultSubtitleParserFactory()), location), chunk)
            val film = requireNotNull(SoundCopies.find(location))
            val sounds = tracks.filter { it.value.type == C.TRACK_TYPE_AUDIO }
            assertTrue(sounds.isNotEmpty())
            assertEquals(sounds.keys.min(), film.playing()?.id)
            for ((id, sound) in sounds.filterKeys { it == film.playing()?.id }) {
                val cursor = requireNotNull(film.tracks[id]).samples.cursor(Long.MIN_VALUE)
                val copied = generateSequence { (cursor.next() as? ReadAhead.Next.Item)?.item }.toList()
                assertTrue(sound.samples.size > 10)
                assertEquals(sound.samples.map { it.timeUs }, copied.map { it.timeUs })
                for ((a, b) in sound.samples.zip(copied)) assertTrue(a.data.contentEquals(b.data))
                // Read to the file's end, the copies end: a read past the last one ends instead of waiting.
                assertEquals(ReadAhead.Next.End, cursor.next())
            }
        }
    }

    // A sound track's output that takes everything and keeps nothing.
    private object Nowhere : TrackOutput {
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = length
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
        override fun sampleMetadata(t: Long, f: Int, size: Int, offset: Int, c: TrackOutput.CryptoData?) = Unit
    }

    private fun sound(id: Int) = Format.Builder().setId(id.toString()).setSampleMimeType(MimeTypes.AUDIO_AC3).build()

    // [count] samples of [bytes] each, a tenth of a second apart, handed to [output].
    private fun feed(output: TrackOutput, count: Int, bytes: Int) = repeat(count) { i ->
        output.sampleData(ParsableByteArray(ByteArray(bytes)), bytes, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(i * 100_000L, C.BUFFER_FLAG_KEY_FRAME, bytes, 0, null)
    }

    @Test
    fun `only the sound track playing is copied (#537)`() {
        val film = SoundCopies.of("test://two-tracks")
        val outputs = (1..2).map { id ->
            val track = film.tracks.getOrPut(id) { SoundCopies.Track(id) }
            SoundCopies.Copying(Nowhere, track, film).apply { format(sound(id)) }
        }
        SoundCopies.select("test://two-tracks", "2")
        outputs.forEach { feed(it, 20, 100) }
        assertEquals(0L, film.tracks.getValue(1).samples.heldBytes)
        assertTrue(film.tracks.getValue(2).samples.heldBytes >= 2000)
    }

    @Test
    fun `the copies stay within their memory however big the sound, and a film left lets go of them (#537)`() {
        val film = SoundCopies.of("test://big")
        val track = film.tracks.getOrPut(1) { SoundCopies.Track(1) }
        // TrueHD's peak: some 230 kB a tenth of a second, for two minutes.
        feed(SoundCopies.Copying(Nowhere, track, film).apply { format(sound(1)) }, 1200, 230_000)
        assertTrue(track.samples.heldBytes <= SoundCopies.MAX_BYTES)
        assertTrue(track.samples.heldBytes > SoundCopies.MAX_BYTES / 2)
        SoundCopies.of("test://next")
        SoundCopies.select("test://next", null)
        assertEquals(0L, track.samples.heldBytes)
    }

    @Test
    fun `raw sound's copies come out as the analysis hears it, a gap in the times as silence`() {
        val track = SoundCopies.Track(1).apply {
            format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(2).setSampleRate(48_000).build()
        }
        // Two tenths of a second, then a gap of a tenth, then two more.
        fun tone(frames: Int, from: Int) = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { f ->
                val v = (8000 * sin(2 * PI * 440 * (from + f) / 48_000)).toInt().toShort()
                putShort(v)
                putShort(v)
            }
        }.array()
        for ((i, timeUs) in listOf(0L, 100_000L, 300_000L, 400_000L).withIndex()) {
            track.samples.offer(SoundCopies.Sample(tone(4800, i * 4800), timeUs), timeUs / 1000, 19_200, 1, true)
        }
        track.samples.end()
        val stream = TappedSound(track.samples.cursor(0), track.format!!, 0) { null }
        val heard = generateSequence { stream.read() }.fold(FloatArray(0)) { all, piece -> all + piece }
        assertEquals(0.5 * FilmSound.RATE, heard.size.toDouble(), FilmSound.RATE * 0.01)
        // The gap (0.2 … 0.3 s) silent, the tone either side of it.
        val gap = (0.22 * FilmSound.RATE).toInt() until (0.28 * FilmSound.RATE).toInt()
        assertTrue(gap.all { heard[it] == 0f })
        assertTrue((0.05 * FilmSound.RATE).toInt().let { at -> (at until at + 100).any { heard[it] > 0.1f } })
    }

    @Test
    fun `where the copies have moved on, the analysis reads on from the file`() {
        val track = SoundCopies.Track(1).apply {
            format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(1).setSampleRate(16_000).build()
        }
        track.samples.offer(SoundCopies.Sample(ByteArray(3200), 0), 0, 3200, 1, true)
        val cursor = track.samples.cursor(0)
        track.samples.reset()
        var askedAt = -1L
        val stream = TappedSound(cursor, track.format!!, 0) { at ->
            askedAt = at
            object : FilmSound.Stream {
                override fun read() = FloatArray(10)
                override fun close() = Unit
            }
        }
        assertEquals(10, stream.read()?.size)
        assertEquals(0L, askedAt)
    }
}
