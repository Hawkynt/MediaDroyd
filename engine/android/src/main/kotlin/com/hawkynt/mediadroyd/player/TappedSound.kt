package com.hawkynt.mediadroyd.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import java.nio.ByteOrder

/**
 * The sound playing for the analysis, from the copies Media3's extractors handed over (SoundCopies, #535): decoded by
 * Media3's FFmpeg decoder (any codec playback plays — DTS and TrueHD too), mixed and resampled to mono at
 * FilmSound.RATE ([Mono16k]), each piece laid where its time puts it. Waits where Media3 has not read yet; where its
 * place is passed, or the decoder cannot be had, [fallback] reads on from the file itself.
 */
@OptIn(UnstableApi::class)
internal class TappedSound(
    private val cursor: ReadAhead.Cursor<SoundCopies.Sample>,
    private val format: Format,
    fromMs: Long,
    private val fallback: (atMs: Long) -> FilmSound.Stream?,
) : FilmSound.Stream {
    private val rate = FilmSound.RATE
    private val raw = format.sampleMimeType == MimeTypes.AUDIO_RAW
    private val decoder: FfmpegAudioDecoder? = if (raw) {
        null
    } else {
        runCatching { FfmpegAudioDecoder(format, BATCH, BATCH, maxOf(format.maxInputSize, INPUT_BYTES), false) }
            .getOrNull()
    }
    private val mono = Mono16k()

    // The next sample handed out, counted at FilmSound.RATE from the film's start.
    private var next = fromMs * rate / 1000
    private var own: FilmSound.Stream? = null
    @Volatile private var cancelled = false

    override fun read(): FloatArray? {
        own?.let { return it.read() }
        if (decoder == null && !(raw && format.pcmEncoding == C.ENCODING_PCM_16BIT)) return fallBack()
        while (!cancelled) {
            when (val got = cursor.next()) {
                is ReadAhead.Next.Item -> {
                    // What else is read already goes along: the decoder takes several at once.
                    val samples = arrayListOf(got.item)
                    while (samples.size < BATCH) samples += (cursor.peekItem() ?: break)
                    runCatching { decode(samples) }.getOrElse { return fallBack() }?.let { return it }
                }
                ReadAhead.Next.Wait -> cursor.await(WAIT_MS)
                ReadAhead.Next.End -> return null
                ReadAhead.Next.Lost -> return fallBack()
            }
        }
        return null
    }

    // The next one read already, if any; else nothing taken.
    private fun ReadAhead.Cursor<SoundCopies.Sample>.peekItem(): SoundCopies.Sample? = nextIfReady()

    private fun fallBack(): FloatArray? {
        cursor.close()
        own = fallback(next * 1000 / rate)
        return own?.read()
    }

    // [samples] decoded and laid at their times: a gap of more than a fiftieth of a second filled with silence, an
    // overlap dropped.
    private fun decode(samples: List<SoundCopies.Sample>): FloatArray? {
        var out = FloatArray(0)
        for ((timeUs, pcm, channels, inputRate) in pcmOf(samples)) {
            val heard = mono.feed(pcm, 0, pcm.size / channels, channels, inputRate)
            if (heard.isEmpty()) continue
            val gap = timeUs * rate / 1_000_000 - next
            val piece = when {
                gap > rate / 50 && gap < MAX_GAP -> FloatArray(gap.toInt()) + heard
                gap < -rate / 50 -> heard.copyOfRange((-gap).coerceAtMost(heard.size.toLong()).toInt(), heard.size)
                else -> heard
            }
            next += piece.size
            out += piece
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private data class Pcm(val timeUs: Long, val samples: ShortArray, val channels: Int, val rate: Int)

    // The samples' sound as 16-bit interleaved PCM with its time, channels and rate; those that gave none left out.
    // As many go into the decoder as it takes before their sound is taken out, in order.
    private fun pcmOf(samples: List<SoundCopies.Sample>): List<Pcm> {
        if (decoder == null) {
            return samples.map { sample ->
                val shorts = ShortArray(sample.data.size / 2)
                java.nio.ByteBuffer.wrap(sample.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                Pcm(sample.timeUs, shorts, format.channelCount.coerceAtLeast(1), format.sampleRate)
            }
        }
        val out = ArrayList<Pcm>()
        var queued = 0
        var taken = 0
        while (taken < samples.size) {
            while (queued < samples.size) {
                // The first waited for, the rest only where the decoder has room.
                val input = if (queued == taken) {
                    waitFor { decoder.dequeueInputBuffer() }
                } else {
                    decoder.dequeueInputBuffer() ?: break
                }
                val sample = samples[queued++]
                input.ensureSpaceForWrite(sample.data.size)
                input.data!!.put(sample.data)
                input.timeUs = sample.timeUs
                input.flip()
                decoder.queueInputBuffer(input)
            }
            val output = waitFor { decoder.dequeueOutputBuffer() }
            taken++
            try {
                val data = output.data
                if (output.shouldBeSkipped || data == null || !data.hasRemaining()) continue
                val shorts = ShortArray(data.remaining() / 2)
                data.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                out += Pcm(output.timeUs, shorts, decoder.channelCount.coerceAtLeast(1), decoder.sampleRate)
            } finally {
                output.release()
            }
        }
        return out
    }

    // What [take] gives once it gives anything: the decoder works on a thread of its own.
    private fun <T : Any> waitFor(take: () -> T?): T {
        val until = System.nanoTime() + DECODE_TIMEOUT_NS
        while (true) {
            take()?.let { return it }
            check(System.nanoTime() < until && !cancelled) { "the decoder gave nothing" }
            java.util.concurrent.locks.LockSupport.parkNanos(POLL_NS)
        }
    }

    override fun cancel() {
        cancelled = true
        own?.cancel()
    }

    override fun close() {
        cursor.close()
        own?.close()
        decoder?.release()
    }

    private companion object {
        const val WAIT_MS = 100L

        // Samples in the decoder at once, and how often it is looked at while it works.
        const val BATCH = 16
        const val POLL_NS = 100_000L
        const val INPUT_BYTES = 1 shl 16
        const val DECODE_TIMEOUT_NS = 2_000_000_000L

        // The longest gap filled with silence: ten seconds; beyond, the times are taken as broken, not the sound.
        const val MAX_GAP = 10L * FilmSound.RATE
    }
}
