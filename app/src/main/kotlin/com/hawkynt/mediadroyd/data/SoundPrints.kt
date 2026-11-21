package com.hawkynt.mediadroyd.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroyd.player.AndroidNeural
import com.hawkynt.mediadroyd.player.FfmpegInputs
import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.NativeFingerprint
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * A video's sound fingerprinted on Android for finding its intro and credits (PlayerPlatform.soundPrint): its main
 * audio track demuxed and decoded by the app's own FFmpeg (FfmpegDemuxer.sound: every container and codec the extension
 * holds — DTS, TrueHD, WMA and RealAudio included — mixed to one channel at Chromaprint's rate; #491), else, where the
 * extension is missing or cannot read it, by the device's own decoder (MediaExtractor and MediaCodec), a file on a
 * network source read through FfmpegInputs.remote and FsMediaDataSource; then fingerprinted by Chromaprint inside the
 * app's native library (NativeFingerprint).
 */
object SoundPrints {
    private const val TIMEOUT_US = 10_000L

    /**
     * The video at [location]: its opening (IntroCredits.openingMs), or where [closing] its closing
     * (IntroCredits.closingMs); null where its sound cannot be read or decoded here.
     */
    fun of(context: Context, location: String, closing: Boolean): IntroCredits.SoundPrint? {
        if (!AndroidNeural.loaded) return null
        val ffmpeg = if (FfmpegDemuxer.isAvailable()) byFfmpeg(context, location, closing) else null
        return ffmpeg ?: byDevice(context, location, closing)
    }

    // The sound through the app's own FFmpeg: its demuxers and decoders, resampled to one channel.
    private fun byFfmpeg(context: Context, location: String, closing: Boolean): IntroCredits.SoundPrint? {
        val local = if (location.startsWith("/")) runCatching { RandomAccessFile(location, "r") }.getOrNull() else null
        return try {
            val input = when {
                local != null -> FfmpegInputs.local(local)
                location.startsWith("/") -> return null
                else -> FfmpegInputs.remote(SourceAccess.resolve(context, location) ?: return null)
            }
            val demuxer = FfmpegDemuxer.open(input) ?: return null
            try {
                val durationUs = demuxer.durationUs().takeIf { it != FfmpegDemuxer.TIME_UNSET && it > 0 }
                if (closing && durationUs == null) return null
                val startUs = if (closing) (durationUs!! - IntroCredits.closingMs(durationUs / 1000) * 1000).coerceAtLeast(0) else 0L
                val openingMs = IntroCredits.openingMs((durationUs ?: 0) / 1000)
                val endUs = startUs + (if (closing) IntroCredits.closingMs(durationUs!! / 1000) else openingMs) * 1000
                val first = LongArray(1)
                val pcm = demuxer.sound(startUs, endUs, NativeFingerprint.SAMPLE_RATE, first) ?: return null
                NativeFingerprint.fingerprint(pcm, NativeFingerprint.SAMPLE_RATE, 1)
                    ?.let { IntroCredits.SoundPrint(it, first[0] / 1000) }
            } finally {
                demuxer.close()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { local?.close() }
        }
    }

    // The sound through the device's own decoders, where the FFmpeg extension is missing.
    private fun byDevice(context: Context, location: String, closing: Boolean): IntroCredits.SoundPrint? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            val remote = if (location.startsWith("/")) null else SourceAccess.resolve(context, location)
            if (remote == null) {
                extractor.setDataSource(location)
            } else {
                extractor.setDataSource(FsMediaDataSource(remote))
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else {
                0L
            }
            val startMs = if (closing) (durationMs - IntroCredits.closingMs(durationMs)).coerceAtLeast(0) else 0L
            val endMs = startMs + if (closing) IntroCredits.closingMs(durationMs) else IntroCredits.openingMs(durationMs)
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            decode(extractor, decoder, endMs)?.let { (pcm, firstMs, rate, channels) ->
                NativeFingerprint.fingerprint(pcm, rate, channels)?.let { IntroCredits.SoundPrint(it, firstMs) }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private data class Decoded(val pcm: ShortArray, val firstMs: Long, val rate: Int, val channels: Int)

    // The decoder's output up to [endMs]: its samples, where they begin, and their rate and channels.
    private fun decode(extractor: MediaExtractor, decoder: MediaCodec, endMs: Long): Decoded? {
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var firstMs = -1L
        var inputDone = false
        var rate = 0
        var channels = 0
        while (true) {
            if (!inputDone) {
                val slot = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (slot >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(slot)!!, 0)
                    if (size < 0 || extractor.sampleTime / 1000 >= endMs) {
                        decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                out >= 0 -> {
                    if (rate == 0) {
                        rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (info.size > 0) {
                        if (firstMs < 0) firstMs = info.presentationTimeUs / 1000
                        val bytes = ByteArray(info.size)
                        decoder.getOutputBuffer(out)!!.apply { position(info.offset) }.get(bytes)
                        pcm.write(bytes)
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        val bytes = pcm.toByteArray()
        if (bytes.isEmpty() || rate <= 0 || channels <= 0) return null
        val samples = ShortArray(bytes.size / 2)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
        return Decoded(samples, firstMs, rate, channels)
    }
}
