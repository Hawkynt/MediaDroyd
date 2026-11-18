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
     * The video at [location]: its opening (IntroCredits.openingMs), or where [closing] its last
     * IntroCredits.CLOSING_MS, as gray frames; null where it cannot be read or holds no pictures.
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
                val startUs = if (closing) (durationUs!! - IntroCredits.CLOSING_MS * 1000).coerceAtLeast(0) else 0L
                val openingMs = IntroCredits.openingMs((durationUs ?: 0) / 1000)
                val endUs = startUs + (if (closing) IntroCredits.CLOSING_MS else openingMs) * 1000
                val stepMs = if (closing) PictureCues.CLOSING_STEP_MS else PictureCues.OPENING_STEP_MS
                // A closing at the text reader's size: decoded once for its pictures and its text (#535).
                val width = if (closing) CreditText.WIDTH else PictureCues.HASH_WIDTH
                val height = if (closing) CreditText.HEIGHT else PictureCues.HASH_HEIGHT
                val gray = demuxer.pictures(startUs, endUs, stepMs * 1000, width, height) ?: return null
                val size = width * height
                val frames = List(gray.size / size) { gray.copyOfRange(it * size, (it + 1) * size) }
                when {
                    frames.isEmpty() -> null
                    closing -> PictureCues.closing(startUs / 1000, durationUs!! / 1000, frames, AndroidNeural.loaded)
                    else -> PictureCues.Frames(width, height, startUs / 1000, stepMs, frames)
                }
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
