package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.NativeText
import com.hawkynt.mediadroyd.player.NeuralModels
import com.hawkynt.mediadroyd.player.PictureCues

/**
 * A video's frames read for their text on the desktop, for credits recognised by it (PlayerPlatform.readText, #493):
 * FFmpeg decodes the picture at each time asked for (MediaReader, in software), shrunk to CreditText's size in gray,
 * and PP-OCRv5 reads it on the CPU (NativeText, the carried models of NeuralModels.PPOCR). One reader for all videos,
 * one video at a time.
 */
object CreditReads {
    private var handle = 0L

    // Pictures read past a time before giving up on finding one there (a broken stretch, the end).
    private const val MAX_READS = 400

    /**
     * The video at [path] ([from] a network source where it lives on one): at each time [at] gives from its length, the
     * lines read there; null where it or the models cannot be read.
     */
    fun read(
        path: String,
        from: FsFile?,
        neural: DesktopNeural,
        at: (durationMs: Long) -> List<Long>,
    ): List<Pair<Long, List<CreditText.Line>>>? = synchronized(this) {
        if (!neural.loaded || !neural.ready(NeuralModels.PPOCR)) return null
        if (handle == 0L) handle = NativeText.open(null, neural.folder(NeuralModels.PPOCR).path, false)
        if (handle == 0L) return null
        runCatching {
            MediaReader(path, from, decoders = emptyList()).use { reader ->
                at(reader.durationMs).map { timeMs ->
                    reader.seek(timeMs)
                    var picture: MediaReader.Picture? = null
                    var reads = 0
                    while (picture == null && reads++ < MAX_READS) {
                        val item = reader.read() ?: break
                        picture = (item as? MediaReader.Picture)?.takeIf { it.ptsMs >= timeMs }?.eightBit()
                    }
                    val lines = picture?.let {
                        val grey = PictureCues.gray(it.rgba, it.width, it.height, CreditText.WIDTH, CreditText.HEIGHT)
                        NativeText.read(handle, grey, CreditText.WIDTH, CreditText.HEIGHT)?.let(CreditText::lines)
                    }
                    timeMs to lines.orEmpty()
                }
            }
        }.getOrNull()
    }
}
