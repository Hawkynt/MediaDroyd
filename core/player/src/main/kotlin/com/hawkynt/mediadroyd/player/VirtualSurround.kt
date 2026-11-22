package com.hawkynt.mediadroyd.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Virtual surround for 5.1 and 7.1 on headphones (#453): folded down to two channels, a surround mix plays flat inside
 * the head. Instead each loudspeaker is put where ITU-R BS.775 places it — front left and right at ±30°, the centre
 * ahead, 5.1's surrounds at ±110°, 7.1's sides at ±90° and backs at ±150° — by convolving its channel with the
 * head-related impulse responses measured for that direction at each ear, the two ears' sums the binaural pair; the LFE
 * goes to both ears as it is. MIT's KEMAR measurements (Bill Gardner and Keith Martin, MIT Media Lab, 1994; free to
 * use with credit to the authors, see `THIRD-PARTY-NOTICE.KEMAR.txt`), the normal pinna, read from libmysofa's SOFA
 * copy by tools/hrir at 44.1 and 48 kHz ([TAPS] samples an ear), scaled so the centre's left ear has unit energy.
 *
 * The pair goes into the first two channels and the rest fall silent, so the layout stays as it came: whatever folds
 * the channels down for the headphones next hears the pair alone. FFmpeg's sofalizer and headphone filters do the
 * same; `VirtualSurroundTest` holds this to the headphone filter over the same responses.
 */
class VirtualSurround(sampleRate: Int, val channels: Int) {

    /** Whether this layout is one it places: 5.1 or 7.1. */
    val places: Boolean = channels == 6 || channels == 8

    // Each input channel's direction (an index into DIRECTIONS), or -1 for the LFE; WAVE's order.
    private val directionOf: IntArray = when (channels) {
        6 -> intArrayOf(1, 2, 0, -1, 5, 6)
        8 -> intArrayOf(1, 2, 0, -1, 7, 8, 3, 4)
        else -> IntArray(0)
    }

    // The responses for this rate: [direction][ear][tap].
    private val responses: Array<Array<FloatArray>> = responsesFor(sampleRate)

    // Each channel's last TAPS - 1 samples before the buffer, then the buffer's: the convolution's history.
    private var history = Array(channels) { FloatArray(TAPS - 1) }
    private var work = Array(channels) { FloatArray(0) }

    /**
     * Places [frames] frames of interleaved 16-bit samples from [offset], in place: the binaural pair into the first
     * two channels, the others silent. A layout it does not place is left as it is.
     */
    fun process(samples: ShortArray, offset: Int, frames: Int) {
        if (!places || frames <= 0) return
        val length = TAPS - 1 + frames
        for (c in 0 until channels) {
            if (work[c].size < length) work[c] = FloatArray(length)
            val w = work[c]
            history[c].copyInto(w)
            for (f in 0 until frames) w[TAPS - 1 + f] = samples[offset + f * channels + c].toFloat()
        }
        for (f in 0 until frames) {
            var left = 0f
            var right = 0f
            for (c in 0 until channels) {
                val w = work[c]
                val d = directionOf[c]
                if (d < 0) {
                    val lfe = w[TAPS - 1 + f]
                    left += lfe
                    right += lfe
                    continue
                }
                val l = responses[d][0]
                val r = responses[d][1]
                // Tap k reads the sample k before this one.
                val newest = TAPS - 1 + f
                for (k in 0 until TAPS) {
                    val x = w[newest - k]
                    left += l[k] * x
                    right += r[k] * x
                }
            }
            val at = offset + f * channels
            samples[at] = SoftKnee.apply(left)
            samples[at + 1] = SoftKnee.apply(right)
            for (c in 2 until channels) samples[at + c] = 0
        }
        for (c in 0 until channels) work[c].copyInto(history[c], 0, frames, frames + TAPS - 1)
    }

    /** Forgets the sound so far: the next buffer starts from silence. */
    fun clear() {
        history.forEach { it.fill(0f) }
    }

    companion object {
        /** Samples in each ear's response. */
        const val TAPS = 128

        /** The directions the responses are measured for, degrees on the horizontal plane, positive to the left. */
        val DIRECTIONS = floatArrayOf(0f, 30f, -30f, 90f, -90f, 110f, -110f, 150f, -150f)

        // The rates the responses come at.
        private val RATES = intArrayOf(44100, 48000)

        private val cache = HashMap<Int, Array<Array<FloatArray>>>()

        /**
         * The responses at [rate]: as read where they come at it, else the 48 kHz ones stretched to it by linear
         * interpolation. Scaled so the centre's left ear has unit energy.
         */
        @Synchronized
        fun responsesFor(rate: Int): Array<Array<FloatArray>> = cache.getOrPut(rate) {
            val source = RATES.firstOrNull { it == rate } ?: 48000
            val raw = read(source)
            val scale = 1f / sqrt(raw[0][0].sumOf { (it * it).toDouble() }.toFloat())
            Array(DIRECTIONS.size) { d ->
                Array(2) { e ->
                    val ir = raw[d][e]
                    FloatArray(TAPS) { k ->
                        val t = k * source.toFloat() / rate
                        val i = t.toInt()
                        val v = when {
                            i + 1 < TAPS -> ir[i] + (ir[i + 1] - ir[i]) * (t - i)
                            i < TAPS -> ir[i]
                            else -> 0f
                        }
                        // A stretched response keeps its energy per second.
                        v * scale * if (source == rate) 1f else source.toFloat() / rate
                    }
                }
            }
        }

        private fun read(rate: Int): Array<Array<FloatArray>> {
            val bytes = requireNotNull(VirtualSurround::class.java.getResourceAsStream("hrir-$rate.f32")).readBytes()
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return Array(DIRECTIONS.size) { Array(2) { FloatArray(TAPS).also { floats.get(it) } } }
        }
    }
}
