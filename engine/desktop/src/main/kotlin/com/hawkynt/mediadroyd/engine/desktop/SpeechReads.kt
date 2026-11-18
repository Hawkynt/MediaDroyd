package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.player.SubtitleSync
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where a video's sound speaks on the desktop, for moving subtitles onto it (PlayerPlatform.speech, SubtitleSync):
 * FFmpeg decodes its whole sound (MediaReader, its pictures left alone) as 16-bit stereo at MediaReader.SAMPLE_RATE,
 * piece by piece into SubtitleSync.Speech.
 */
object SpeechReads {
    /** The verdicts for the video at [path] ([from] a network source where it lives on one); null where unreadable. */
    fun of(path: String, from: FsFile?): BooleanArray? = runCatching {
        MediaReader(path, from, decoders = emptyList(), pictures = false).use { reader ->
            val speech = SubtitleSync.Speech(MediaReader.SAMPLE_RATE, 2)
            var samples = ShortArray(0)
            var read = false
            while (true) {
                val sound = reader.read() ?: break
                if (sound !is MediaReader.Sound) continue
                val count = sound.pcm.size / 2
                if (samples.size < count) samples = ShortArray(count)
                ByteBuffer.wrap(sound.pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, count)
                speech.feed(samples, 0, count / 2)
                read = true
            }
            if (read) speech.result() else null
        }
    }.getOrNull()
}
