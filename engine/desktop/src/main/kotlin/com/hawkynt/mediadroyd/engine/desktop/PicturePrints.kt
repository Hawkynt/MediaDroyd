package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.PictureCues

/**
 * A video's pictures sampled on the desktop for finding its intro and credits where their sound does not give them
 * away (PlayerPlatform.pictures, #492): FFmpeg decodes them in software (MediaReader) and each step's first picture is
 * shrunk to gray (PictureCues.gray) — the opening at PictureCues' hash size every OPENING_STEP_MS, the closing at its
 * look size every CLOSING_STEP_MS.
 */
object PicturePrints {
    /**
     * The video at [path] ([from] a network source where it lives on one): its opening (IntroCredits.openingMs), or
     * where [closing] its last IntroCredits.CLOSING_MS, as gray frames; null where it cannot be read or holds no
     * pictures.
     */
    fun of(
        path: String,
        from: FsFile?,
        closing: Boolean,
        keepText: Boolean = false,
    ): PictureCues.Frames? = runCatching {
        MediaReader(path, from, decoders = emptyList()).use { reader ->
            val startMs = if (closing) (reader.durationMs - IntroCredits.CLOSING_MS).coerceAtLeast(0) else 0
            val endMs = startMs + if (closing) IntroCredits.CLOSING_MS else IntroCredits.openingMs(reader.durationMs)
            val stepMs = if (closing) PictureCues.CLOSING_STEP_MS else PictureCues.OPENING_STEP_MS
            // A closing at the text reader's size: decoded once for its pictures and its text (#535).
            val width = if (closing) CreditText.WIDTH else PictureCues.HASH_WIDTH
            val height = if (closing) CreditText.HEIGHT else PictureCues.HASH_HEIGHT
            if (startMs > 0) reader.seek(startMs)
            // Handed on one by one while they are read: a closing's eight minutes at the text reader's size at once
            // are hundreds of megabytes (#551).
            val frames = sequence {
                var nextMs = startMs
                while (nextMs < endMs) {
                    val item = reader.read() ?: break
                    val picture = (item as? MediaReader.Picture)?.eightBit() ?: continue
                    if (picture.ptsMs < nextMs) continue
                    val gray = PictureCues.gray(picture.rgba, picture.width, picture.height, width, height)
                    while (nextMs <= picture.ptsMs && nextMs < endMs) {
                        yield(gray)
                        nextMs += stepMs
                    }
                }
            }
            if (closing) {
                PictureCues.closing(startMs, reader.durationMs, frames, keepText).takeIf { it.frames.isNotEmpty() }
            } else {
                val taken = frames.toList()
                if (taken.isEmpty()) null else PictureCues.Frames(width, height, startMs, stepMs, taken)
            }
        }
    }.getOrNull()
}
