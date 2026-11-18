package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The tone of a line as it was spoken (#534), so the voice-over keeps it: from the line's own sound ([measure]) its
 * loudness, its pitch (YIN: de Cheveigné & Kawahara, JASA 2002 — the cumulative mean normalised difference, its first
 * dip under 0.15), how much the pitch moves (the spread of its voiced frames in semitones) and its pace (letters a
 * second); against the film's own speech so far ([Baseline]) that becomes how to speak it ([Baseline.delivery]):
 * louder or quieter, faster or slower, higher or lower, livelier or flatter. Rung 3: YIN from its paper; the mapping
 * is our own, a hypothesis tuned by ear.
 */
object Prosody {
    /** A line's tone: its loudness (dBFS), pitch (Hz; 0 where nothing voiced), pace and pitch's spread (semitones). */
    data class Features(
        val levelDb: Double,
        val pitchHz: Double,
        val lettersPerSecond: Double,
        val pitchMovement: Double,
    )

    // YIN's frame, hop and the pitch range of speech.
    private const val FRAME_MS = 40
    private const val HOP_MS = 10
    private const val MIN_HZ = 70.0
    private const val MAX_HZ = 400.0
    private const val THRESHOLD = 0.15

    /** The tone of [samples] (mono floats at [rate]: the line's own sound) spoken as [letters] letters. */
    fun measure(samples: FloatArray, rate: Int, letters: Int): Features {
        val seconds = samples.size / rate.toDouble()
        val frame = rate * FRAME_MS / 1000
        val hop = rate * HOP_MS / 1000
        val levels = ArrayList<Double>()
        val pitches = ArrayList<Double>()
        var at = 0
        while (at + frame <= samples.size) {
            var sum = 0.0
            for (i in at until at + frame) sum += samples[i] * samples[i]
            val rms = sqrt(sum / frame)
            levels += rms
            if (rms > SILENT) yin(samples, at, frame, rate)?.let { pitches += it }
            at += hop
        }
        // The loudness of the speaking frames: those within 20 dB of the loudest.
        val loudest = levels.maxOrNull() ?: 0.0
        val speaking = levels.filter { it > loudest / 10 && it > SILENT }
        val level = if (speaking.isEmpty()) -120.0 else 20 * log10(sqrt(speaking.sumOf { it * it } / speaking.size))
        val pitch = if (pitches.isEmpty()) 0.0 else pitches.sorted()[pitches.size / 2]
        val movement = if (pitches.size < 3) {
            0.0
        } else {
            val semitones = pitches.map { 12 * ln(it / pitch) / ln(2.0) }.sorted()
            semitones[semitones.size * 9 / 10] - semitones[semitones.size / 10]
        }
        return Features(level, pitch, if (seconds > 0) letters / seconds else 0.0, movement)
    }

    // One frame's pitch by YIN, or null where it is not voiced.
    private fun yin(samples: FloatArray, from: Int, frame: Int, rate: Int): Double? {
        val minLag = (rate / MAX_HZ).toInt()
        val maxLag = minOf((rate / MIN_HZ).toInt(), frame / 2)
        val window = frame - maxLag - 1
        val d = DoubleArray(maxLag + 2)
        for (lag in 1..maxLag + 1) {
            var sum = 0.0
            for (i in 0 until window) {
                val delta = samples[from + i] - samples[from + i + lag]
                sum += delta * delta
            }
            d[lag] = sum
        }
        var running = 0.0
        val normalised = DoubleArray(maxLag + 2) { 1.0 }
        for (lag in 1..maxLag + 1) {
            running += d[lag]
            normalised[lag] = if (running == 0.0) 1.0 else d[lag] * lag / running
        }
        var lag = minLag
        while (lag <= maxLag) {
            if (normalised[lag] < THRESHOLD) {
                while (lag + 1 <= maxLag && normalised[lag + 1] < normalised[lag]) lag++
                // Between the samples: a parabola through the dip and its neighbours.
                val a = normalised[lag - 1]
                val b = normalised[lag]
                val c = normalised[lag + 1]
                val bend = a - 2 * b + c
                val fine = if (abs(bend) > 1e-12) lag + (a - c) / (2 * bend) else lag.toDouble()
                return rate / fine
            }
            lag++
        }
        return null
    }

    /**
     * The film's own speech so far: each line's tone [add]ed; a line's [delivery] is how it differs from the
     * median of those — after [ENOUGH] lines, before that each line is spoken the voice's own way.
     */
    class Baseline {
        private val heard = ArrayList<Features>()

        @Synchronized
        fun add(features: Features) {
            if (features.levelDb > -90) heard += features
        }

        /** How to speak a line of [features] tone against the film's usual speech. */
        @Synchronized
        fun delivery(features: Features): Dub.Delivery {
            if (heard.size < ENOUGH) return Dub.Delivery()
            fun median(of: (Features) -> Double) = heard.map(of).filter { it > 0 || of == Features::levelDb }
                .sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            val level = median(Features::levelDb)
            val pitch = median(Features::pitchHz)
            val pace = median(Features::lettersPerSecond)
            val movement = median(Features::pitchMovement)
            val gain = ((features.levelDb - level) * GAIN_SHARE).coerceIn(-9.0, 6.0)
            val speed = if (pace > 0 && features.lettersPerSecond > 0) {
                (features.lettersPerSecond / pace).coerceIn(0.8, 1.3)
            } else {
                1.0
            }
            val semitones = if (pitch > 0 && features.pitchHz > 0) {
                (12 * ln(features.pitchHz / pitch) / ln(2.0) * PITCH_SHARE).coerceIn(-3.0, 3.0)
            } else {
                0.0
            }
            val lively = if (movement > 0) {
                ((features.pitchMovement + 1) / (movement + 1)).coerceIn(0.7, 1.4)
            } else {
                1.0
            }
            fun near(value: Double, to: Double, by: Double) = if (abs(value - to) < by) to else value
            return Dub.Delivery(
                gainDb = near(gain, 0.0, 0.5).toFloat(),
                rate = near(speed, 1.0, 0.03).toFloat(),
                pitch = near(semitones, 0.0, 0.25).toFloat(),
                liveliness = near(lively, 1.0, 0.05).toFloat(),
            )
        }
    }

    /** The tone [toned] with a text's own [hint] on top: its loudness added, its pace times the tone's. */
    fun combine(toned: Dub.Delivery, hint: Dub.Delivery): Dub.Delivery = Dub.Delivery(
        gainDb = toned.gainDb + hint.gainDb,
        rate = toned.rate * hint.rate,
        pitch = toned.pitch + hint.pitch,
        liveliness = toned.liveliness * hint.liveliness,
    )

    /** Lines heard before the film's usual speech counts as known. */
    const val ENOUGH = 5

    // Below this a frame is silence; how much of a difference in loudness and pitch the voice takes over.
    private const val SILENT = 0.003
    private const val GAIN_SHARE = 0.7
    private const val PITCH_SHARE = 0.7
}
