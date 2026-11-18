package com.hawkynt.mediadroyd.player

import kotlin.math.pow

// VLC's preamp that leaves its equalizer's sound at unity: 12 dB against its −12 dB input (EQZ_IN_FACTOR).
private const val VLC_UNITY_DB = 12f

/**
 * The 10-band graphic equalizer (#451): a preamp, then a peaking filter per band ([HZ]) from Robert Bristow-Johnson's
 * Audio EQ Cookbook, an octave wide, on every channel of interleaved 16-bit sound; near full scale a soft knee bends
 * the peaks instead of clipping. A band at or above half the sample rate is left out. Gains are passed with every
 * buffer; a changed gain takes new coefficients and keeps the filters' state, so a slider moved mid-playback does not
 * click. Its presets are VLC's ([EqualizerPreset]).
 *
 * Sourcing: rung 1 for the presets and bands (VLC's `equalizer_presets.h`, LGPL-2.1-or-later: constants, copied), rung
 * 3 for the filters (the cookbook's peaking EQ) — VLC's own band filters are a different design.
 */
class Equalizer(private val sampleRate: Int, channels: Int) {
    private val channels = channels.coerceAtLeast(1)
    private val active = HZ.indices.filter { HZ[it] < sampleRate / 2.0 }
    private val filters = Array(this.channels) { Array(HZ.size) { Biquad.peaking(sampleRate, HZ[it], 0.0) } }
    private val gains = FloatArray(HZ.size)

    /**
     * Equalizes [frames] frames of interleaved 16-bit samples from [offset] in place: [preampDb] on everything, then
     * [bandsDb] (one gain per [HZ], −20..20; missing ones 0). All zero leaves the samples untouched.
     */
    fun process(samples: ShortArray, offset: Int, frames: Int, preampDb: Float, bandsDb: List<Float>) {
        if (frames <= 0) return
        val bands = active.filter { (bandsDb.getOrNull(it) ?: 0f) != 0f }
        if (preampDb == 0f && bands.isEmpty()) return
        for (b in bands) {
            val db = bandsDb[b].coerceIn(MIN_DB, MAX_DB)
            if (db != gains[b]) {
                gains[b] = db
                val made = Biquad.peaking(sampleRate, HZ[b], db.toDouble())
                for (c in 0 until channels) filters[c][b].set(made)
            }
        }
        val pre = 10.0.pow(preampDb.coerceIn(MIN_DB, MAX_DB) / 20.0).toFloat()
        for (f in 0 until frames) {
            val at = offset + f * channels
            for (c in 0 until channels) {
                var x = samples[at + c] * pre
                for (b in bands) x = filters[c][b].next(x)
                samples[at + c] = SoftKnee.apply(x)
            }
        }
    }

    companion object {
        /** The bands' centres in Hz: VLC's ten (`f_vlc_frequency_table_10b`). */
        val HZ = listOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)

        /** How far a band or the preamp goes, in dB: VLC's range. */
        const val MIN_DB = -20f
        const val MAX_DB = 20f
    }
}

/**
 * VLC's equalizer presets (`eqz_preset_10b` in its `equalizer_presets.h`, copied; its -1.11022e-15 taken as the 0 it
 * stands for): a [label] to show, the [bandsDb] for [Equalizer.HZ] and the [preampDb] — VLC's own less 12 dB, since VLC
 * feeds its filter the sound at −12 dB (EQZ_IN_FACTOR) and its preamp of 12 brings that back to unity.
 */
enum class EqualizerPreset(val label: String, vlcPreampDb: Float, vararg bands: Float) {
    FLAT("Flat", 12f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    CLASSICAL("Classical", 12f, 0f, 0f, 0f, 0f, 0f, 0f, -7.2f, -7.2f, -7.2f, -9.6f),
    CLUB("Club", 6f, 0f, 0f, 8f, 5.6f, 5.6f, 5.6f, 3.2f, 0f, 0f, 0f),
    DANCE("Dance", 5f, 9.6f, 7.2f, 2.4f, 0f, 0f, -5.6f, -7.2f, -7.2f, 0f, 0f),
    FULL_BASS("Full bass", 5f, -8f, 9.6f, 9.6f, 5.6f, 1.6f, -4f, -8f, -10.4f, -11.2f, -11.2f),
    FULL_BASS_TREBLE("Full bass and treble", 4f, 7.2f, 5.6f, 0f, -7.2f, -4.8f, 1.6f, 8f, 11.2f, 12f, 12f),
    FULL_TREBLE("Full treble", 3f, -9.6f, -9.6f, -9.6f, -4f, 2.4f, 11.2f, 16f, 16f, 16f, 16.8f),
    HEADPHONES("Headphones", 4f, 4.8f, 11.2f, 5.6f, -3.2f, -2.4f, 1.6f, 4.8f, 9.6f, 12.8f, 14.4f),
    LARGE_HALL("Large hall", 5f, 10.4f, 10.4f, 5.6f, 5.6f, 0f, -4.8f, -4.8f, -4.8f, 0f, 0f),
    LIVE("Live", 7f, -4.8f, 0f, 4f, 5.6f, 5.6f, 5.6f, 4f, 2.4f, 2.4f, 2.4f),
    PARTY("Party", 6f, 7.2f, 7.2f, 0f, 0f, 0f, 0f, 0f, 0f, 7.2f, 7.2f),
    POP("Pop", 6f, -1.6f, 4.8f, 7.2f, 8f, 5.6f, 0f, -2.4f, -2.4f, -1.6f, -1.6f),
    REGGAE("Reggae", 8f, 0f, 0f, 0f, -5.6f, 0f, 6.4f, 6.4f, 0f, 0f, 0f),
    ROCK("Rock", 5f, 8f, 4.8f, -5.6f, -8f, -3.2f, 4f, 8.8f, 11.2f, 11.2f, 11.2f),
    SKA("Ska", 6f, -2.4f, -4.8f, -4f, 0f, 4f, 5.6f, 8.8f, 9.6f, 11.2f, 9.6f),
    SOFT("Soft", 5f, 4.8f, 1.6f, 0f, -2.4f, 0f, 4f, 8f, 9.6f, 11.2f, 12f),
    SOFT_ROCK("Soft rock", 7f, 4f, 4f, 2.4f, 0f, -4f, -5.6f, -3.2f, 0f, 2.4f, 8.8f),
    TECHNO("Techno", 5f, 8f, 5.6f, 0f, -5.6f, -4.8f, 0f, 8f, 9.6f, 9.6f, 8.8f),
    ;

    /** The preamp in dB against unity. */
    val preampDb: Float = vlcPreampDb - VLC_UNITY_DB

    /** The gains in dB for [Equalizer.HZ]. */
    val bandsDb: List<Float> = bands.toList()

    companion object {
        /** The preset [preampDb] and [bandsDb] are, or null where they are the household's own. */
        fun matching(preampDb: Float, bandsDb: List<Float>): EqualizerPreset? {
            val bands = List(Equalizer.HZ.size) { bandsDb.getOrNull(it) ?: 0f }
            return entries.firstOrNull { it.preampDb == preampDb && it.bandsDb == bands }
        }
    }
}
