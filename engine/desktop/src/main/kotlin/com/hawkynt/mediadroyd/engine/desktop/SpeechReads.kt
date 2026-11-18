package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.player.FilmSound
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A film's sound read once on the desktop for everything that analyses it (PlayerPlatform.openSound, FilmSound, #535):
 * FFmpeg decodes it (MediaReader, its pictures left alone) as 16-bit stereo at MediaReader.SAMPLE_RATE, mixed to one
 * channel and every three samples averaged into one — FilmSound.RATE — handed on a decoded piece at a time.
 */
object SpeechReads {
    /** The video at [path] ([from] a network source where it is on one) read from [fromMs]; null where unreadable. */
    fun open(path: String, from: FsFile?, fromMs: Long): FilmSound.Stream? = runCatching {
        val reader = MediaReader(path, from, decoders = emptyList(), pictures = false)
        if (fromMs > 0) reader.seek(fromMs)
        val step = MediaReader.SAMPLE_RATE / FilmSound.RATE
        object : FilmSound.Stream {
            // What a seek hands out before fromMs, in input frames; -1 until the first piece says how much.
            var skip = -1L
            var sum = 0f
            var count = 0

            override fun read(): FloatArray? {
                while (true) {
                    val sound = runCatching { reader.read() }.getOrNull() ?: return null
                    if (sound !is MediaReader.Sound) continue
                    if (skip < 0) skip = (fromMs - sound.ptsMs).coerceAtLeast(0) * MediaReader.SAMPLE_RATE / 1000
                    val shorts = ByteBuffer.wrap(sound.pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val out = FloatArray(shorts.remaining() / 2 / step + 1)
                    var n = 0
                    while (shorts.remaining() >= 2) {
                        val mono = (shorts.get() + shorts.get()) / 65_536f
                        if (skip > 0) {
                            skip--
                            continue
                        }
                        sum += mono
                        if (++count == step) {
                            out[n++] = sum / step
                            sum = 0f
                            count = 0
                        }
                    }
                    if (n > 0) return out.copyOf(n)
                }
            }

            override fun close() = reader.close()
        }
    }.getOrNull()
}
