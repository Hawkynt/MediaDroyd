package com.hawkynt.mediadroyd.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Applies the household loudness setting to the decoded audio: the dB value is
 * re-read per buffer, so the settings slider takes effect mid-playback without
 * rebuilding the chain. The sink feeds the chain PCM16, which is the only
 * encoding accepted here; the sample math itself is [LoudnessChain]'s, shared
 * with the desktop engine and reached there by a plain unit test.
 *
 * With [normalize] on, the file's own correction comes on top: its ReplayGain from [fileLoudness], else its loudness
 * measured as it plays. A new [FileLoudness] — the next file, another audio track — or a new sound format starts that
 * afresh.
 */
class LoudnessAudioProcessor(
    private val gainDb: () -> Float,
    private val nightMode: () -> Boolean = { false },
    private val normalize: () -> Boolean = { false },
    private val fileLoudness: () -> FileLoudness? = { null },
    private val voiceBoostDb: () -> Float = { 0f },
    private val eqPreampDb: () -> Float = { 0f },
    private val eqBandsDb: () -> List<Float> = { emptyList() },
    private val voiceOver: () -> com.hawkynt.mediadroyd.engine.VoiceOver? = { null },
    private val headphones: () -> Boolean = { false },
) : BaseAudioProcessor() {

    // Where the sound passing stands in the film (VoiceOver, #531): the stream's position at the last flush — where
    // a seek or the start put it — and the frames handed through since.
    private var rate = 0
    private var streamStartUs = 0L
    private var framesSince = 0L

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        streamStartUs = streamMetadata.positionOffsetUs
        framesSince = 0
    }

    private var channels = 1
    private var chain: LoudnessChain? = null
    private var samples = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        rate = inputAudioFormat.sampleRate
        chain = LoudnessChain(inputAudioFormat.sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        // The sink hands over whole frames.
        val frames = inputBuffer.remaining() / 2 / channels
        val count = frames * channels
        if (samples.size < count) samples = ShortArray(count)
        inputBuffer.asShortBuffer().get(samples, 0, count)
        inputBuffer.position(inputBuffer.limit())
        // Spoken subtitles at the film's own time, before the speed (the chain's Sonic comes after this processor).
        voiceOver()?.let { over ->
            val ptsMs = (streamStartUs + framesSince * 1_000_000L / rate.coerceAtLeast(1)) / 1000
            over.mix(samples, 0, frames, channels, rate, ptsMs)
        }
        framesSince += frames
        checkNotNull(chain).process(
            samples, 0, frames, gainDb(), nightMode(), normalize(), fileLoudness(), voiceBoostDb(),
            eqPreampDb(), eqBandsDb(), headphones(),
        )
        val buffer = replaceOutputBuffer(count * 2)
        buffer.asShortBuffer().put(samples, 0, count)
        buffer.position(count * 2)
        buffer.flip()
    }
}
