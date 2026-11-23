package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kaldi's log mel filterbank, as a speaker-embedding network takes its sound (#532): 16 kHz samples in −1 … 1, frames
 * of 25 ms every 10 ms — the first centred on the start, the sound mirrored past either end (Kaldi's `snip_edges`
 * off) — each with its mean taken out, pre-emphasised (0.97), Povey-windowed and zero-padded to 512, its power
 * spectrum through [BINS] triangular mel filters from 20 Hz to 400 Hz below Nyquist, the logs floored at the float's
 * epsilon.
 *
 * Converted from kaldi-native-fbank 1.32 (Apache-2.0, © Xiaomi Corporation, after Kaldi's `feature-fbank.cc`,
 * `feature-window.cc` and `mel-computations.cc`), with the options sherpa-onnx's speaker-embedding extractor gives it;
 * held to kaldi-native-fbank's own output by `FbankTest`.
 */
object Fbank {
    const val RATE = 16000
    const val BINS = 80
    private const val SHIFT = 160
    private const val LENGTH = 400
    private const val PADDED = 512
    private const val PREEMPH = 0.97f
    private const val LOW_HZ = 20.0
    private const val HIGH_HZ = RATE / 2.0 - 400.0
    private val EPSILON = Math.ulp(1.0f)

    private val window = DoubleArray(LENGTH) { i -> (0.5 - 0.5 * cos(2 * PI * i / (LENGTH - 1))).pow(0.85) }

    // Each mel filter's first FFT bin and its weights.
    private val filters: List<Pair<Int, FloatArray>> = run {
        fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)
        val low = mel(LOW_HZ)
        val delta = (mel(HIGH_HZ) - low) / (BINS + 1)
        List(BINS) { b ->
            val left = low + b * delta
            val centre = left + delta
            val right = centre + delta
            val weights = FloatArray(PADDED / 2) { i ->
                val m = mel(RATE.toDouble() * i / PADDED)
                when {
                    m <= left || m >= right -> 0f
                    m <= centre -> ((m - left) / (centre - left)).toFloat()
                    else -> ((right - m) / (right - centre)).toFloat()
                }
            }
            val first = weights.indexOfFirst { it > 0f }.coerceAtLeast(0)
            val last = weights.indexOfLast { it > 0f }.coerceAtLeast(first)
            first to weights.copyOfRange(first, last + 1)
        }
    }

    /** The frames of [samples] (16 kHz, −1 … 1): [BINS] log energies each. */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val n = samples.size
        val count = if (n == 0) 0 else (n + SHIFT / 2) / SHIFT
        val re = DoubleArray(PADDED)
        val im = DoubleArray(PADDED)
        val frame = DoubleArray(LENGTH)
        return Array(count) { f ->
            val first = f * SHIFT + SHIFT / 2 - LENGTH / 2
            for (i in 0 until LENGTH) {
                var s = first + i
                while (s < 0 || s >= n) s = if (s < 0) -s - 1 else 2 * n - 1 - s
                frame[i] = samples[s].toDouble()
            }
            val mean = frame.average()
            for (i in 0 until LENGTH) frame[i] -= mean
            for (i in LENGTH - 1 downTo 1) frame[i] -= PREEMPH * frame[i - 1]
            frame[0] -= PREEMPH * frame[0]
            re.fill(0.0)
            im.fill(0.0)
            for (i in 0 until LENGTH) re[i] = frame[i] * window[i]
            fft(re, im)
            FloatArray(BINS) { b ->
                val (start, weights) = filters[b]
                var energy = 0.0
                for (k in weights.indices) {
                    val i = start + k
                    energy += weights[k] * (re[i] * re[i] + im[i] * im[i])
                }
                ln(max(energy, EPSILON.toDouble())).toFloat()
            }
        }
    }

    /** Takes each bin's mean over [frames] out of it, as the 3D-Speaker networks expect ("global-mean"). */
    fun subtractMean(frames: Array<FloatArray>) {
        if (frames.isEmpty()) return
        for (b in 0 until BINS) {
            val mean = frames.sumOf { it[b].toDouble() } / frames.size
            for (frame in frames) frame[b] = (frame[b] - mean).toFloat()
        }
    }

    // An in-place radix-2 FFT of PADDED points.
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var size = 2
        while (size <= n) {
            val angle = -2 * PI / size
            for (start in 0 until n step size) {
                for (k in 0 until size / 2) {
                    val c = cos(angle * k)
                    val s = sin(angle * k)
                    val a = start + k
                    val b = a + size / 2
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            size *= 2
        }
    }
}
