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

    /**
     * The video at [path]'s sound from [fromMs] to [toMs] as Whisper hears it (subtitles made from the speech, #529):
     * mono floats at NativeWhisper.SAMPLE_RATE, the first at [fromMs] — FFmpeg's 48 kHz stereo mixed to one channel
     * and every three samples averaged into one. Null where it cannot be read.
     */
    fun soundAt(path: String, from: FsFile?, fromMs: Long, toMs: Long): FloatArray? = runCatching {
        MediaReader(path, from, decoders = emptyList(), pictures = false).use { reader ->
            if (fromMs > 0) reader.seek(fromMs)
            val rate = MediaReader.SAMPLE_RATE
            val step = rate / com.hawkynt.mediadroyd.player.NativeWhisper.SAMPLE_RATE
            val out = ArrayList<Float>()
            var skip = -1L
            var sum = 0f
            var count = 0
            loop@ while (true) {
                val sound = reader.read() ?: break
                if (sound !is MediaReader.Sound) continue
                if (sound.ptsMs >= toMs) break
                // What a seek hands out before fromMs is dropped.
                if (skip < 0) skip = ((fromMs - sound.ptsMs).coerceAtLeast(0) * rate / 1000)
                val shorts = ByteBuffer.wrap(sound.pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (shorts.remaining() >= 2) {
                    val mono = (shorts.get() + shorts.get()) / 65_536f
                    if (skip > 0) {
                        skip--
                        continue
                    }
                    sum += mono
                    if (++count == step) {
                        out += sum / step
                        sum = 0f
                        count = 0
                    }
                }
                if (out.size >= (toMs - fromMs) * com.hawkynt.mediadroyd.player.NativeWhisper.SAMPLE_RATE / 1000) break@loop
            }
            if (out.isEmpty()) null else out.toFloatArray()
        }
    }.getOrNull()
}
