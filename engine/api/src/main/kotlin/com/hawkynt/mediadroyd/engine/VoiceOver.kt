package com.hawkynt.mediadroyd.engine

/**
 * Something spoken over the film's sound (spoken subtitles, #531), mixed in by the engine as its decoded sound passes,
 * at the film's own time — before the playback speed, so a faster film speaks faster too.
 */
fun interface VoiceOver {
    /**
     * Mixes into [frames] frames of [samples] from [offset] (interleaved 16-bit, [channels] at [sampleRate]), the first
     * of them at [ptsMs] of the film. Called on the engine's sound thread.
     */
    fun mix(samples: ShortArray, offset: Int, frames: Int, channels: Int, sampleRate: Int, ptsMs: Long)
}
