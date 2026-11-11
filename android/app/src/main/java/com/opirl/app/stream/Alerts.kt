package com.opirl.app.stream

enum class AlertLevel { INFO, WARNING, ERROR }

data class Alert(val id: Long, val level: AlertLevel, val title: String, val detail: String? = null)

class LowBitrateWatch(private val thresholdBps: Long = 500_000, private val samples: Int = 3) {
    private var low = 0
    private var high = 0
    private var armed = true

    fun update(bitrateBps: Long): Boolean {
        if (bitrateBps < thresholdBps) {
            high = 0
            if (armed && ++low >= samples) {
                armed = false
                return true
            }
        } else {
            low = 0
            if (bitrateBps >= thresholdBps * 2 && ++high >= samples) armed = true
        }
        return false
    }
}

class ThermalGuard(private val reduceAt: Int = 4, private val restoreAt: Int = 2, private val reducedFps: Int = 30) {
    var reduced = false
        private set

    fun update(status: Int, busy: Boolean): Boolean {
        val next = when {
            status >= reduceAt -> true
            status <= restoreAt && !busy -> false
            else -> reduced
        }
        if (next == reduced) return false
        reduced = next
        return true
    }

    fun fps(wanted: Int): Int = if (reduced) minOf(wanted, reducedFps) else wanted
}
