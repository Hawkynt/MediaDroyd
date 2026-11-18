package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tanh

/**
 * One second-order section, transposed direct form II, with the filters of Robert Bristow-Johnson's Audio EQ Cookbook
 * (rung 3: the cookbook's own formulas). Its coefficients can be set anew while it runs ([set]): its state stays, so a
 * gain changed mid-playback does not click.
 */
internal class Biquad(b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
    private var b0 = b0
    private var b1 = b1
    private var b2 = b2
    private var a1 = a1
    private var a2 = a2
    private var z1 = 0f
    private var z2 = 0f

    fun next(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    /** Takes [other]'s coefficients, keeping this one's state. */
    fun set(other: Biquad) {
        b0 = other.b0
        b1 = other.b1
        b2 = other.b2
        a1 = other.a1
        a2 = other.a2
    }

    companion object {
        private const val Q = 0.7071

        fun highPass(rate: Int, hz: Double): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                ((1 + c) / 2 / a0).toFloat(), (-(1 + c) / a0).toFloat(), ((1 + c) / 2 / a0).toFloat(),
                (-2 * c / a0).toFloat(), ((1 - alpha) / a0).toFloat(),
            )
        }

        fun lowPass(rate: Int, hz: Double): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                ((1 - c) / 2 / a0).toFloat(), ((1 - c) / a0).toFloat(), ((1 - c) / 2 / a0).toFloat(),
                (-2 * c / a0).toFloat(), ((1 - alpha) / a0).toFloat(),
            )
        }

        /** The cookbook's peaking EQ at [hz], [octaves] wide (its BW, between the half-gain points), by [db]. */
        fun peaking(rate: Int, hz: Double, db: Double, octaves: Double = 1.0): Biquad {
            val a = 10.0.pow(db / 40)
            val w = 2 * PI * hz / rate
            val alpha = sin(w) * sinh(ln(2.0) / 2 * octaves * w / sin(w))
            val c = cos(w)
            val a0 = 1 + alpha / a
            return Biquad(
                ((1 + alpha * a) / a0).toFloat(), (-2 * c / a0).toFloat(), ((1 - alpha * a) / a0).toFloat(),
                (-2 * c / a0).toFloat(), ((1 - alpha / a) / a0).toFloat(),
            )
        }
    }
}

/** A sample past [KNEE] bent softly towards full scale instead of clipping; below it as it is. */
internal object SoftKnee {
    private const val FULL = 32767f
    private const val KNEE = 24000f

    fun apply(x: Float): Short {
        val magnitude = abs(x)
        val room = FULL - KNEE
        val shaped = if (magnitude <= KNEE) magnitude else KNEE + room * tanh((magnitude - KNEE) / room)
        return (shaped * sign(x)).toInt().toShort()
    }
}
