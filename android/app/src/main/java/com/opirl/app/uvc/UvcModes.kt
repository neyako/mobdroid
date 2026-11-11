package com.opirl.app.uvc

internal class UvcMode(val format: UvcFormat, val fpsMatch: Int)

internal object UvcModes {
    private val continuousRates = intArrayOf(5, 10, 15, 20, 24, 25, 30, 50, 60, 90, 100, 120, 144, 240)

    fun parse(raw: IntArray): List<UvcMode> {
        val modes = LinkedHashMap<UvcFormat, UvcMode>()
        var i = 0
        while (i + 4 <= raw.size) {
            val mjpeg = raw[i] == 1
            val width = raw[i + 1]
            val height = raw[i + 2]
            val count = raw[i + 3]
            i += 4
            val usable = width > 0 && height > 0 && (mjpeg || width % 2 == 0)
            if (count > 0) {
                if (i + count > raw.size) break
                if (usable) {
                    for (k in i until i + count) {
                        addDiscrete(modes, mjpeg, width, height, raw[k])
                    }
                }
                i += count
            } else {
                if (i + 3 > raw.size) break
                if (usable) {
                    addContinuous(modes, mjpeg, width, height, raw[i], raw[i + 1], raw[i + 2])
                }
                i += 3
            }
        }
        return modes.values.sortedWith(
            compareByDescending<UvcMode> { it.format.width.toLong() * it.format.height }
                .thenByDescending { it.format.width }
                .thenByDescending { it.format.fps }
                .thenBy { !it.format.mjpeg },
        )
    }

    private fun addDiscrete(
        modes: MutableMap<UvcFormat, UvcMode>,
        mjpeg: Boolean,
        width: Int,
        height: Int,
        interval: Int,
    ) {
        if (interval <= 0) return
        val fps = ((10_000_000L + interval / 2) / interval).toInt()
        if (fps !in 1..1000) return
        val format = UvcFormat(width, height, fps, mjpeg)
        modes.putIfAbsent(format, UvcMode(format, 10_000_000 / interval))
    }

    private fun addContinuous(
        modes: MutableMap<UvcFormat, UvcMode>,
        mjpeg: Boolean,
        width: Int,
        height: Int,
        min: Int,
        max: Int,
        step: Int,
    ) {
        if (min <= 0 || max < min) return
        for (rate in continuousRates) {
            val interval = 10_000_000 / rate
            val offset = interval - min
            if (interval < min || interval > max) continue
            if (offset != 0 && (step <= 0 || offset % step != 0)) continue
            val format = UvcFormat(width, height, rate, mjpeg)
            modes.putIfAbsent(format, UvcMode(format, rate))
        }
    }
}
