package com.hawkynt.mediadroyd.player

/**
 * Sound around you on headphones (#453), for one playback and sound format: stereo crossfed ([Crossfeed]), 5.1 and
 * 7.1 placed around the head ([VirtualSurround]), mono and other layouts as they are. Android's own spatial audio
 * needs Android 13 and headphones that support it, the desktop has none; this runs everywhere.
 */
class HeadphoneSound(sampleRate: Int, private val channels: Int) {
    private val crossfeed = if (channels == 2) Crossfeed(sampleRate) else null
    private val surround = VirtualSurround(sampleRate, channels).takeIf { it.places }

    // Whether the last buffer was processed: switched back on, the filters start from silence.
    private var running = false

    /** Processes [frames] frames of interleaved 16-bit samples from [offset] in place where [on]. */
    fun process(samples: ShortArray, offset: Int, frames: Int, on: Boolean) {
        if (!on) {
            running = false
            return
        }
        if (!running) {
            crossfeed?.clear()
            surround?.clear()
            running = true
        }
        crossfeed?.process(samples, offset, frames, channels)
        surround?.process(samples, offset, frames)
    }
}
