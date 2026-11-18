package com.hawkynt.mediadroyd.player

/**
 * How subtitles show: [delayMs] against the picture (positive: later), their [size], whether they sit
 * [raised] above burned-in text at the bottom of the picture, and whether a dark [box] backs them for
 * readability. The household sets a default; each video may keep its own.
 */
data class SubtitleLook(
    val delayMs: Int = 0,
    val size: Size = Size.NORMAL,
    val raised: Boolean = false,
    val box: Boolean = false,
    /**
     * How much slower the cues run than the film (SubtitleSync, #450): a file timed for another frame rate, 1 where
     * not. The cue at t then belongs at t × drift + [delayMs].
     */
    val drift: Double = 1.0,
) {
    /**
     * The delay at playback [positionMs]: [delayMs], and with a [drift] what it adds by then — the cue shown there was
     * timed at (position − delay) / drift.
     */
    fun delayAt(positionMs: Long): Int =
        if (drift == 1.0) delayMs else (delayMs + (positionMs - delayMs) * (drift - 1) / drift).toInt()

    enum class Size(val label: String, val scale: Float) {
        SMALL("Small", 0.8f),
        NORMAL("Normal", 1f),
        LARGE("Large", 1.3f),
        EXTRA_LARGE("Extra large", 1.6f),
    }

    /** Text height as a fraction of the picture height. */
    val textSizeFraction: Float get() = DEFAULT_TEXT_SIZE_FRACTION * size.scale

    /** Space kept free below the subtitles, as a fraction of the picture height. */
    val bottomPaddingFraction: Float get() = if (raised) RAISED_BOTTOM_FRACTION else DEFAULT_BOTTOM_FRACTION

    /** The delay moved by [steps] of [DELAY_STEP_MS], held within ±[DELAY_LIMIT_MS]. */
    fun nudgeDelay(steps: Int): SubtitleLook =
        copy(
            delayMs = (delayMs + steps * DELAY_STEP_MS)
                .coerceIn(minOf(-DELAY_LIMIT_MS, delayMs), maxOf(DELAY_LIMIT_MS, delayMs)),
        )

    companion object {
        const val DELAY_STEP_MS = 100
        const val DELAY_LIMIT_MS = 10_000

        /** Media3's `SubtitleView.DEFAULT_TEXT_SIZE_FRACTION` and `DEFAULT_BOTTOM_PADDING_FRACTION`. */
        const val DEFAULT_TEXT_SIZE_FRACTION = 0.0533f
        const val DEFAULT_BOTTOM_FRACTION = 0.08f
        const val RAISED_BOTTOM_FRACTION = 0.25f
    }
}
