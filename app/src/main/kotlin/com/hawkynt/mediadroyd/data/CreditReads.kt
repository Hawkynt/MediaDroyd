package com.hawkynt.mediadroyd.data

import android.content.Context
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroyd.player.AndroidNeural
import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.FfmpegInputs
import com.hawkynt.mediadroyd.player.NativeText
import com.hawkynt.mediadroyd.player.NeuralModels
import java.io.RandomAccessFile

/**
 * A video's frames read for their text on Android, for credits recognised by it (PlayerPlatform.readText, #493): the
 * app's own FFmpeg hands over the picture at each time asked for already in gray at CreditText's size
 * (FfmpegDemuxer.pictures), and PP-OCRv5 reads it on the CPU (NativeText, the models the app carries in its assets,
 * NeuralModels.PPOCR). One reader for all videos, one video at a time.
 */
object CreditReads {
    private var handle = 0L

    /** The lines read in [gray] (CreditText's size), a frame decoded already (PictureCues.closing); null if not. */
    fun recognise(context: Context, gray: ByteArray): List<CreditText.Line>? = synchronized(this) {
        if (!AndroidNeural.loaded) return null
        if (handle == 0L) handle = NativeText.open(context.assets, "models/${NeuralModels.PPOCR.folder}", false)
        if (handle == 0L) return null
        NativeText.read(handle, gray, CreditText.WIDTH, CreditText.HEIGHT)?.let(CreditText::lines)
    }

    /** The video at [location]: at each time [at] gives from its length, the lines read there; null if unreadable. */
    fun read(
        context: Context,
        location: String,
        at: (durationMs: Long) -> List<Long>,
    ): List<Pair<Long, List<CreditText.Line>>>? = synchronized(this) {
        if (!AndroidNeural.loaded || !FfmpegDemuxer.isAvailable()) return null
        if (handle == 0L) handle = NativeText.open(context.assets, "models/${NeuralModels.PPOCR.folder}", false)
        if (handle == 0L) return null
        val local = if (location.startsWith("/")) runCatching { RandomAccessFile(location, "r") }.getOrNull() else null
        try {
            val input = when {
                local != null -> FfmpegInputs.local(local)
                location.startsWith("/") -> return null
                else -> FfmpegInputs.remote(SourceAccess.resolve(context, location) ?: return null)
            }
            val demuxer = FfmpegDemuxer.open(input) ?: return null
            try {
                val durationUs = demuxer.durationUs().takeIf { it != FfmpegDemuxer.TIME_UNSET && it > 0 } ?: return null
                at(durationUs / 1000).map { timeMs ->
                    val startUs = timeMs * 1000
                    val grey = demuxer.pictures(startUs, startUs + 1000, 1000, CreditText.WIDTH, CreditText.HEIGHT)
                    val lines = grey?.let { NativeText.read(handle, it, CreditText.WIDTH, CreditText.HEIGHT) }
                    timeMs to lines?.let(CreditText::lines).orEmpty()
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
