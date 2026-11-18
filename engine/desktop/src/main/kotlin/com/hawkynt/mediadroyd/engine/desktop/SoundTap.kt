package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.FilmSound
import com.hawkynt.mediadroyd.player.ReadAhead
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext

/**
 * The film's sound for the analysis (FilmSound), tapped from the packets playback reads ahead (MediaReader.soundTap,
 * #535): decoded here on a decoder of its own ([parameters] the stream's, its times in [timeBaseNum] / [timeBaseDen]
 * from [startPts]) to mono floats at FilmSound.RATE from [fromMs], each piece laid where its time puts it. Where the
 * packets ahead are not read yet it waits; where its place is given back (it fell behind) [fallback] reads on from the
 * file itself.
 */
internal class SoundTap(
    private val cursor: ReadAhead.Cursor<AVPacket>,
    parameters: AVCodecParameters,
    private val timeBaseNum: Int,
    private val timeBaseDen: Int,
    private val startPts: Long,
    fromMs: Long,
    private val fallback: (atMs: Long) -> FilmSound.Stream?,
) : FilmSound.Stream {
    private val rate = FilmSound.RATE
    private val context: AVCodecContext? = run {
        val codec = avcodec.avcodec_find_decoder(parameters.codec_id())
        val made = codec?.let { avcodec.avcodec_alloc_context3(it) }
        val opened = made != null && avcodec.avcodec_parameters_to_context(made, parameters) >= 0 &&
            avcodec.avcodec_open2(made, codec, null as AVDictionary?) >= 0
        avcodec.avcodec_parameters_free(parameters)
        if (opened) made else made?.let { avcodec.avcodec_free_context(it) }.let { null }
    }
    private val frame = avutil.av_frame_alloc()
    private val out = avutil.av_frame_alloc()
    private var swr: SwrContext? = null

    // The next sample handed out, counted at FilmSound.RATE from the film's start.
    private var next = fromMs * rate / 1000
    private var own: FilmSound.Stream? = null
    private var drained = false
    @Volatile private var cancelled = false

    override fun read(): FloatArray? {
        own?.let { return it.read() }
        if (context == null) return fallBack()
        while (!cancelled) {
            when (val got = cursor.next()) {
                is ReadAhead.Next.Item -> {
                    val pcm = try {
                        decode(got.item)
                    } finally {
                        avcodec.av_packet_free(got.item)
                    }
                    if (pcm != null) return pcm
                }
                ReadAhead.Next.Wait -> cursor.await(WAIT_MS)
                ReadAhead.Next.End -> {
                    if (drained) return null
                    drained = true
                    return decode(null)
                }
                ReadAhead.Next.Lost -> return fallBack()
            }
        }
        return null
    }

    private fun fallBack(): FloatArray? {
        cursor.close()
        own = fallback(next * 1000 / rate)
        return own?.read()
    }

    // [input] decoded (null: what the decoder still holds), each frame resampled and laid at its time: a gap of more
    // than a fiftieth of a second filled with silence, an overlap dropped.
    private fun decode(input: AVPacket?): FloatArray? {
        val context = context ?: return null
        avcodec.avcodec_send_packet(context, input)
        var pcm = FloatArray(0)
        while (avcodec.avcodec_receive_frame(context, frame) == 0) {
            val pts = frame.best_effort_timestamp()
            val samples = resample() ?: continue
            avutil.av_frame_unref(frame)
            var piece = samples
            if (pts != avutil.AV_NOPTS_VALUE) {
                val at = Math.round((pts - startPts) * rate.toDouble() * timeBaseNum / timeBaseDen) + pcm.size
                val gap = at - (next + pcm.size)
                if (gap > rate / 50 && gap < MAX_GAP) {
                    piece = FloatArray(gap.toInt()) + samples
                } else if (gap < -rate / 50) {
                    val drop = (-gap).coerceAtMost(samples.size.toLong()).toInt()
                    piece = samples.copyOfRange(drop, samples.size)
                }
            }
            pcm += piece
        }
        if (pcm.isEmpty()) return null
        next += pcm.size
        return pcm
    }

    // The frame decoded as mono floats at FilmSound.RATE: through stereo, as playback mixes it, its two sides averaged
    // — as the file read for itself does (SpeechReads), so the levels the tone is measured by do not change with where
    // the sound came from.
    private fun resample(): FloatArray? {
        val resampler = swr ?: swresample.swr_alloc().also { swr = it }
        avutil.av_frame_unref(out)
        avutil.av_channel_layout_default(out.ch_layout(), 2)
        out.sample_rate(rate).format(avutil.AV_SAMPLE_FMT_FLT)
        // A layout of unspecified order is the default one for its channel count (as MediaReader.sound).
        if (frame.ch_layout().order() == avutil.AV_CHANNEL_ORDER_UNSPEC) {
            avutil.av_channel_layout_default(frame.ch_layout(), frame.ch_layout().nb_channels())
        }
        if (swresample.swr_convert_frame(resampler, out, frame) < 0) return null
        val n = out.nb_samples()
        if (n <= 0) return null
        val bytes = ByteArray(n * 8)
        out.data(0).get(bytes)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(n) { (floats.get() + floats.get()) / 2 }
    }

    override fun cancel() {
        cancelled = true
        own?.cancel()
    }

    override fun close() {
        cursor.close()
        own?.close()
        swr?.let { swresample.swr_free(it) }
        context?.let { avcodec.avcodec_free_context(it) }
        avutil.av_frame_free(frame)
        avutil.av_frame_free(out)
    }

    private companion object {
        const val WAIT_MS = 100L

        // The longest gap filled with silence: ten seconds; beyond, the times are taken as broken, not the sound.
        const val MAX_GAP = 10L * FilmSound.RATE
    }
}
