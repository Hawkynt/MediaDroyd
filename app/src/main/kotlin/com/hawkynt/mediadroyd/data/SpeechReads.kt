package com.hawkynt.mediadroyd.data

import android.content.Context
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroyd.player.FfmpegInputs
import com.hawkynt.mediadroyd.player.FilmSound
import com.hawkynt.mediadroyd.player.SoundCopies
import com.hawkynt.mediadroyd.player.playableUri
import java.io.RandomAccessFile

/**
 * A film's sound read once on Android for everything that analyses it (PlayerPlatform.openSound, FilmSound, #535): its
 * main audio track decoded by the app's own FFmpeg (FfmpegDemuxer.sound, one channel at FilmSound.RATE) a stretch of
 * [PIECE_US] at a time from one open demuxer, each laid where it belongs (what the decoder hands out before it dropped,
 * a gap filled with silence), handed on piece by piece. Near the position of the film playing, decoded from the copies
 * of what Media3 read ahead instead (SoundCopies), the file not read again.
 */
object SpeechReads {
    private const val PIECE_US = 10_000_000L
    private const val TAG = "MediaDroyd.Sound"

    /**
     * The video at [location] read from [fromMs]: where [near], from what Media3 read ahead of playback where it plays
     * it; else from the file. Null where its sound cannot be read with the app's FFmpeg.
     */
    fun open(context: Context, location: String, fromMs: Long, near: Boolean = false): FilmSound.Stream? {
        if (near) {
            SoundCopies.open(playableUri(location).toString(), fromMs) { at ->
                android.util.Log.i(TAG, "sound at $at ms read from the file: the copies moved on")
                own(context, location, at)
            }?.let {
                android.util.Log.i(TAG, "sound from $fromMs ms decoded from what playback read")
                return it
            }
        }
        android.util.Log.i(TAG, "sound from $fromMs ms read from the file${if (near) ": no copies there" else ""}")
        return own(context, location, fromMs)
    }

    private fun own(context: Context, location: String, fromMs: Long): FilmSound.Stream? {
        if (!FfmpegDemuxer.isAvailable()) return null
        val local = if (location.startsWith("/")) runCatching { RandomAccessFile(location, "r") }.getOrNull() else null
        return try {
            val input = when {
                local != null -> FfmpegInputs.local(local)
                location.startsWith("/") -> return null
                else -> FfmpegInputs.remote(SourceAccess.resolve(context, location) ?: return null)
            }
            val demuxer = FfmpegDemuxer.open(input) ?: run {
                runCatching { local?.close() }
                return null
            }
            val durationUs = demuxer.durationUs().takeIf { it != FfmpegDemuxer.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
            val rate = FilmSound.RATE
            object : FilmSound.Stream {
                // Where the next sample handed on belongs, in µs of the film.
                var nextUs = fromMs * 1000

                override fun read(): FloatArray? {
                    while (nextUs < durationUs) {
                        val end = minOf(nextUs + PIECE_US, durationUs)
                        val first = LongArray(1)
                        val pcm = runCatching { demuxer.sound(nextUs, end, rate, first) }.getOrNull()
                        if (pcm == null || pcm.isEmpty()) {
                            if (end >= durationUs) return null
                            nextUs = end
                            continue
                        }
                        // From where the timeline stands: what came before it dropped, a gap before the first silent.
                        val skip = ((nextUs - first[0]) * rate / 1_000_000L).toInt()
                        val wanted = ((end - nextUs) * rate / 1_000_000L).toInt()
                        val out = FloatArray(minOf(wanted, pcm.size - skip).coerceAtLeast(0)) { i ->
                            val at = i + skip
                            if (at in pcm.indices) pcm[at] / 32_768f else 0f
                        }
                        nextUs = end
                        if (out.isNotEmpty()) return out
                    }
                    return null
                }

                override fun close() {
                    runCatching { demuxer.close() }
                    runCatching { local?.close() }
                }
            }
        } catch (_: Exception) {
            runCatching { local?.close() }
            null
        }
    }
}
