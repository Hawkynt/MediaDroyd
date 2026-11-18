package com.hawkynt.mediadroyd.player

import kotlin.math.log2
import kotlin.math.pow

/**
 * One picture slider. Every slider runs from -1 to 1 with the neutral value in the middle, so the
 * fine adjustments near "unchanged" get the most travel. Multipliers are logarithmic — each half
 * covers the same ratio, ¼× to 4× — and saturation's left half is linear so it can reach grey. Hue runs a half turn
 * either way, tint from green to magenta, temperature from 4000 K through D65's 6504 K to 10000 K (#447).
 */
enum class PictureControl(val label: String, val neutral: Float) {
    BRIGHTNESS("Brightness", 0f),
    GAMMA("Gamma", 1f),
    CONTRAST("Contrast", 1f),
    SATURATION("Colors", 1f),
    HUE("Hue", 0f),
    TINT("Tint", 0f),
    TEMPERATURE("Temperature", ColourBalance.D65_KELVIN),
    ;

    /** The value at slider [position] (-1..1). */
    fun valueAt(position: Float): Float {
        val p = position.coerceIn(-1f, 1f)
        return when (this) {
            BRIGHTNESS -> p
            GAMMA, CONTRAST -> 2f.pow(p * OCTAVES)
            SATURATION -> if (p < 0f) 1f + p else 2f.pow(p * OCTAVES)
            HUE -> p * HALF_TURN
            TINT -> p
            TEMPERATURE -> neutral + p * kelvinSpan(p < 0f)
        }
    }

    /** The slider position (-1..1) showing [value]; values beyond the range sit at the ends. */
    fun positionOf(value: Float): Float = when (this) {
        BRIGHTNESS -> value
        GAMMA, CONTRAST -> log2(value.coerceAtLeast(Float.MIN_VALUE)) / OCTAVES
        SATURATION -> if (value < 1f) value - 1f else log2(value) / OCTAVES
        HUE -> value / HALF_TURN
        TINT -> value
        TEMPERATURE -> (value - neutral) / kelvinSpan(value < neutral)
    }.coerceIn(-1f, 1f)

    // How far temperature's half of the slider reaches from D65: down to the warm end, or up to the cold one.
    private fun kelvinSpan(warm: Boolean) =
        if (warm) neutral - ColourBalance.MIN_KELVIN else ColourBalance.MAX_KELVIN - neutral

    /** [value] as the slider's label shows it. */
    fun text(value: Float): String = when (this) {
        BRIGHTNESS, TINT -> "%+.2f".format(value)
        HUE -> "%+.0f°".format(value)
        TEMPERATURE -> "%.0f K".format(value)
        else -> "%.2f×".format(value)
    }

    fun get(picture: PictureAdjustment): Float = when (this) {
        BRIGHTNESS -> picture.brightness
        GAMMA -> picture.gamma
        CONTRAST -> picture.contrast
        SATURATION -> picture.saturation
        HUE -> picture.hue
        TINT -> picture.tint
        TEMPERATURE -> picture.temperature
    }

    fun set(picture: PictureAdjustment, value: Float): PictureAdjustment = when (this) {
        BRIGHTNESS -> picture.copy(brightness = value)
        GAMMA -> picture.copy(gamma = value)
        CONTRAST -> picture.copy(contrast = value)
        SATURATION -> picture.copy(saturation = value)
        HUE -> picture.copy(hue = value)
        TINT -> picture.copy(tint = value)
        TEMPERATURE -> picture.copy(temperature = value)
    }

    private companion object {
        /** Each half of a multiplier slider spans two doublings: ¼× … 1× … 4×. */
        const val OCTAVES = 2f

        /** Hue's reach either way, in degrees. */
        const val HALF_TURN = 180f
    }
}
