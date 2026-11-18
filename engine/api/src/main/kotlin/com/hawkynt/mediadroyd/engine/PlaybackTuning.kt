package com.hawkynt.mediadroyd.engine

/**
 * The knobs the screens turn while something plays, read by the engine as it plays — no rebuild, no reload: the
 * loudness gain in dB ([loudnessDb]: Settings plus a volume swipe past the device's maximum), night mode ([nightMode],
 * NightModeCompressor), even loudness from file to file ([normalizeLoudness]: ReplayGain tags, else EBU R128 measured
 * as it plays — LoudnessNormalization), the lip-sync delay for the current output ([audioDelayMs]) and the subtitle
 * delay ([subtitleDelayMs]), the voices raised ([voiceBoostDb]) and the equalizer ([equalizerPreampDb],
 * [equalizerBandsDb]). The engine reads them on its own threads, as often
 * as it needs, so an implementation answers from live state; [Mutable] holds them as plain values.
 */
interface PlaybackTuning {
    val loudnessDb: Float
    val nightMode: Boolean
    val normalizeLoudness: Boolean
    val audioDelayMs: Int
    val subtitleDelayMs: Int

    /** How much the voices are raised in dB (VoiceBoost, #452): 0 leaves the sound as it is. */
    val voiceBoostDb: Float get() = 0f

    /** The equalizer's preamp in dB (Equalizer, #451): 0 leaves the sound as it is. */
    val equalizerPreampDb: Float get() = 0f

    /** The equalizer's gains in dB, one per band (Equalizer.HZ); empty or all 0: no band changed. */
    val equalizerBandsDb: List<Float> get() = emptyList()

    /** Spoken subtitles mixed into the sound as it plays (Dub, #531); null for none. */
    val voiceOver: VoiceOver? get() = null

    /** The knobs as values set directly. */
    class Mutable : PlaybackTuning {
        @Volatile override var loudnessDb: Float = 0f
        @Volatile override var nightMode: Boolean = false
        @Volatile override var normalizeLoudness: Boolean = false
        @Volatile override var audioDelayMs: Int = 0
        @Volatile override var subtitleDelayMs: Int = 0
        @Volatile override var voiceBoostDb: Float = 0f
        @Volatile override var voiceOver: VoiceOver? = null
        @Volatile override var equalizerPreampDb: Float = 0f
        @Volatile override var equalizerBandsDb: List<Float> = emptyList()
    }
}
