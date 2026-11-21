package com.hawkynt.mediadroyd.data

import android.content.Context
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroyd.player.AndroidNeural
import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.FfmpegInputs
import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.PictureCues
import java.io.RandomAccessFile

/**
 * A video's pictures sampled on Android for finding its intro and credits where their sound does not give them away
 * (PlayerPlatform.pictures, #492): the app's own FFmpeg demuxes and decodes them and hands over each step's first
 * picture already shrunk to gray (FfmpegDemuxer.pictures) — the opening at PictureCues' hash size every
 * OPENING_STEP_MS, the closing at its look size every CLOSING_STEP_MS. Without the FFmpeg extension, none.
 */
object PicturePrints {
    /**
     * The video at [location]: its opening (IntroCredits.openingMs), or where [closing] its closing
     * (IntroCredits.closingMs), as gray frames; null where it cannot be read or holds no pictures.
     */
    fun of(context: Context, location: String, closing: Boolean): PictureCues.Frames? {
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
                if (closing && durationUs == null) return null
                val closingUs = if (closing) IntroCredits.closingMs(durationUs!! / 1000) * 1000 else 0L
                val startUs = if (closing) (durationUs!! - closingUs).coerceAtLeast(0) else 0L
                val openingMs = IntroCredits.openingMs((durationUs ?: 0) / 1000)
                val endUs = startUs + if (closing) closingUs else openingMs * 1000
                val stepMs = if (closing) PictureCues.CLOSING_STEP_MS else PictureCues.OPENING_STEP_MS
                // A closing at the text reader's size: decoded once for its pictures and its text (#535).
                val width = if (closing) CreditText.WIDTH else PictureCues.HASH_WIDTH
                val height = if (closing) CreditText.HEIGHT else PictureCues.HASH_HEIGHT
                val size = width * height
                if (closing) {
                    // Window by window, each frame taken on before the next window is decoded: eight minutes of
                    // frames at the text reader's size at once are more than the heap (#551).
                    val frames = windows(demuxer, startUs, endUs, stepMs * 1000, width, height)
                    val made = PictureCues.closing(startUs / 1000, durationUs!! / 1000, frames, AndroidNeural.loaded)
                    return made.takeIf { it.frames.isNotEmpty() }
                }
                val gray = demuxer.pictures(startUs, endUs, stepMs * 1000, width, height) ?: return null
                val frames = List(gray.size / size) { gray.copyOfRange(it * size, (it + 1) * size) }
                if (frames.isEmpty()) null else PictureCues.Frames(width, height, startUs / 1000, stepMs, frames)
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
     * The video at [location] from [fromMs] to [toMs], one picture every [stepMs] at PictureCues' look size, gray —
     * the second around an edge of the credits, frame by frame (#557); null where it cannot be read or holds none.
     */
    fun between(context: Context, location: String, fromMs: Long, toMs: Long, stepMs: Long): PictureCues.Frames? {
        if (!FfmpegDemuxer.isAvailable() || toMs <= fromMs) return null
        val local = if (location.startsWith("/")) runCatching { RandomAccessFile(location, "r") }.getOrNull() else null
        return try {
            val input = when {
                local != null -> FfmpegInputs.local(local)
                location.startsWith("/") -> return null
                else -> FfmpegInputs.remote(SourceAccess.resolve(context, location) ?: return null)
            }
            val demuxer = FfmpegDemuxer.open(input) ?: return null
            try {
                val w = PictureCues.LOOK_WIDTH
                val h = PictureCues.LOOK_HEIGHT
                val gray = demuxer.pictures(fromMs * 1000, toMs * 1000, stepMs * 1000, w, h) ?: return null
                val frames = List(gray.size / (w * h)) { gray.copyOfRange(it * w * h, (it + 1) * w * h) }
                if (frames.isEmpty()) null else PictureCues.Frames(w, h, fromMs, stepMs, frames)
            } finally {
                demuxer.close()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { local?.close() }
        }
    }

    // The frames from [startUs] to [endUs], one every [stepUs], decoded [WINDOW_US] at a time and handed on one by
    // one; they end where a window gives fewer than it asked for.
    private fun windows(
        demuxer: FfmpegDemuxer,
        startUs: Long,
        endUs: Long,
        stepUs: Long,
        width: Int,
        height: Int,
    ): Sequence<ByteArray> = sequence {
        val size = width * height
        val stride = WINDOW_US / stepUs * stepUs
        var from = startUs
        while (from < endUs) {
            val to = minOf(from + stride, endUs)
            val gray = demuxer.pictures(from, to, stepUs, width, height) ?: break
            val count = gray.size / size
            for (i in 0 until count) yield(gray.copyOfRange(i * size, (i + 1) * size))
            if (count < (to - from + stepUs - 1) / stepUs) break
            from = to
        }
    }

    // How much of a closing is decoded at once: 30 frames at the text reader's size, some 16 MB.
    private const val WINDOW_US = 30_000_000L
}
