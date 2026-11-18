package com.hawkynt.mediadroyd.data

import android.content.Context
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroyd.player.FfmpegInputs
import com.hawkynt.mediadroyd.player.SubtitleSync
import java.io.RandomAccessFile

/**
 * Where a video's sound speaks on Android, for moving subtitles onto it (PlayerPlatform.speech, SubtitleSync): its main
 * audio track decoded by the app's own FFmpeg (FfmpegDemuxer.sound, one channel at [RATE]) a stretch of [PIECE_US] at a
 * time — never the whole film in memory — each laid where it belongs on the timeline (what a seek hands out early is
 * cut off, a gap filled with silence) and judged by SubtitleSync.Speech.
 */
object SpeechReads {
    /** The rate the sound is decoded at: enough for the speech band's 3.4 kHz. */
    private const val RATE = 8000
    private const val PIECE_US = 300_000_000L

    /** The verdicts for the video at [location]; null where its sound cannot be read with the app's FFmpeg. */
    fun of(context: Context, location: String): BooleanArray? {
        if (!FfmpegDemuxer.isAvailable()) return null
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
                    ?: return null
                val speech = SubtitleSync.Speech(RATE, 1)
                var fedUs = 0L
                var start = 0L
                while (start < durationUs) {
                    val end = minOf(start + PIECE_US, durationUs)
                    val first = LongArray(1)
                    val pcm = demuxer.sound(start, end, RATE, first)
                    if (pcm != null && pcm.isNotEmpty()) {
                        // The samples from where the timeline stands; what came before it dropped, a gap silent.
                        val skip = ((fedUs - first[0]) * RATE / 1_000_000L).toInt()
                        if (skip < 0) {
                            val silence = ShortArray(-skip)
                            speech.feed(silence, 0, silence.size)
                            fedUs = first[0]
                        }
                        val from = skip.coerceIn(0, pcm.size)
                        speech.feed(pcm, from, pcm.size - from)
                        fedUs += (pcm.size - from).toLong() * 1_000_000L / RATE
                    }
                    start = end
                }
                speech.result().takeIf { it.isNotEmpty() }
            } finally {
                demuxer.close()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { local?.close() }
        }
    }

    /**
     * The video at [location]'s sound from [fromMs] to [toMs] as Whisper hears it (subtitles made from the speech,
     * #529): its main audio track decoded by the app's FFmpeg as one channel at NativeWhisper.SAMPLE_RATE, as floats,
     * the first at [fromMs] (what the decoder hands out before it dropped, a gap silent). Null where it cannot be read.
     */
    fun soundAt(context: Context, location: String, fromMs: Long, toMs: Long): FloatArray? {
        if (!FfmpegDemuxer.isAvailable()) return null
        val local = if (location.startsWith("/")) runCatching { RandomAccessFile(location, "r") }.getOrNull() else null
        return try {
            val input = when {
                local != null -> FfmpegInputs.local(local)
                location.startsWith("/") -> return null
                else -> FfmpegInputs.remote(SourceAccess.resolve(context, location) ?: return null)
            }
            val demuxer = FfmpegDemuxer.open(input) ?: return null
            try {
                val rate = com.hawkynt.mediadroyd.player.NativeWhisper.SAMPLE_RATE
                val first = LongArray(1)
                val pcm = demuxer.sound(fromMs * 1000, toMs * 1000, rate, first) ?: return null
                val skip = ((fromMs * 1000 - first[0]) * rate / 1_000_000L).toInt()
                val wanted = ((toMs - fromMs) * rate / 1000).toInt()
                // A skip below 0 is a gap before the first sample: silence there.
                FloatArray(minOf(wanted, pcm.size - skip).coerceAtLeast(0)) { i ->
                    val at = i + skip
                    if (at in pcm.indices) pcm[at] / 32_768f else 0f
                }.takeIf { it.isNotEmpty() }
            } finally {
                demuxer.close()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { local?.close() }
        }
    }
}
