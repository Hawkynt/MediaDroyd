package com.hawkynt.mediadroyd.player

import java.io.File

/**
 * How many threads heavy work like Whisper (whisper.cpp's ggml) should run on: the device's fast cores only. On a phone's
 * big.LITTLE processor the efficiency cores run at a fraction of the big ones' clock and every step of ggml waits for
 * its slowest thread, so using them slows the whole down — whisper.cpp recommends the performance cores. Read from each
 * core's highest clock (`cpufreq/cpuinfo_max_freq`): the cores within three quarters of the fastest. Where all are alike
 * (a desktop, its logical processors counting each hyper-thread), half of them; where nothing can be read, all but one.
 */
object FastCores {
    /** The threads to use, from each core's highest clock as [maxKhz] reads it (null where it cannot). */
    fun count(cores: Int = Runtime.getRuntime().availableProcessors(), maxKhz: (Int) -> Long? = ::readMaxKhz): Int {
        val clocks = (0 until cores).map(maxKhz)
        if (clocks.any { it == null || it <= 0 }) return (cores - 1).coerceAtLeast(1)
        val fastest = clocks.maxOf { it!! }
        val fast = clocks.count { it!! * 4 >= fastest * 3 }
        return if (fast == cores) (cores / 2).coerceAtLeast(1) else fast.coerceAtLeast(1)
    }

    private fun readMaxKhz(core: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
    }.getOrNull()
}
