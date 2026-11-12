package com.opirl.app.engine.abr

private const val PACKETS_IN_FLIGHT_LIMIT = 200L
private const val RTT_DIFF_HIGH_FACTOR = 0.9
private const val RTT_DIFF_HIGH_ALLOWED_SPIKE = 50.0
private const val RTT_DIFF_HIGH_MINIMUM_DECREASE = 250_000L
private const val PACKETS_DIFF_INCREASE_FACTOR = 100_000L
private const val CONSTANT_PACKETS_IN_FLIGHT = 10.0

class RistAdaptiveBitrate(
    targetBitrate: Long,
    minimumBitrate: Long = 50_000,
) : AdaptiveBitrate() {
    private var target = targetBitrate
    private var minimum = minimumBitrate
    private var averageRtt = 0.0
    private var fastRtt = 0.0
    private var smoothPackets = 0.0
    private var fastPackets = 0.0
    private var maximumBitrate = minOf(ADAPTIVE_BITRATE_START, targetBitrate)

    override var bitrate: Long = maximumBitrate
        private set

    override fun setTargetBitrate(bitrate: Long) {
        target = bitrate
    }

    override fun setMinimumBitrate(bitrate: Long) {
        minimum = bitrate
    }

    override fun update(stats: StreamStats, nowNanos: Long): Long = update(stats.rttMs, stats = stats)

    fun update(rttMs: Double, packetsInFlight: Double = CONSTANT_PACKETS_IN_FLIGHT, stats: StreamStats? = null): Long {
        calculatePackets(packetsInFlight)
        calculateRtts(rttMs)
        increaseMaximum(packetsInFlight, allowedRttJitter = 15.0, allowedPacketsJitter = 10.0)
        if (smoothPackets > 100.0) {
            decreaseMaximum(0.9, 250_000)
        }
        if (averageRtt > 250.0) {
            decreaseMaximum(0.9, 250_000)
        }
        if (rttMs > averageRtt + RTT_DIFF_HIGH_ALLOWED_SPIKE) {
            decreaseMaximum(RTT_DIFF_HIGH_FACTOR, RTT_DIFF_HIGH_MINIMUM_DECREASE)
        }
        calculateBitrate(stats)
        return bitrate
    }

    private fun calculatePackets(packetsInFlight: Double) {
        if (packetsInFlight > smoothPackets) {
            smoothPackets = smoothPackets * 0.97 + packetsInFlight * 0.03
        } else {
            smoothPackets = smoothPackets * 0.9 + packetsInFlight * 0.1
        }
        fastPackets = fastPackets * 0.67 + packetsInFlight * 0.33
    }

    private fun calculateRtts(rttMs: Double) {
        if (averageRtt < 1) {
            averageRtt = rttMs
        }
        if (averageRtt > rttMs) {
            averageRtt = averageRtt * 0.60 + rttMs * 0.40
        } else {
            averageRtt *= 0.96
            averageRtt += if (rttMs < 450) rttMs * 0.04 else 450 * 0.001
        }
        if (fastRtt > rttMs) {
            fastRtt = fastRtt * 0.70 + rttMs * 0.30
        } else {
            fastRtt = fastRtt * 0.90 + rttMs * 0.10
        }
        if (averageRtt > 450) {
            averageRtt = 450.0
        }
    }

    private fun increaseMaximum(packetsInFlight: Double, allowedRttJitter: Double, allowedPacketsJitter: Double) {
        val spike = (packetsInFlight - smoothPackets).toLong().coerceIn(0, PACKETS_IN_FLIGHT_LIMIT)
        val headroom = PACKETS_IN_FLIGHT_LIMIT - spike
        if (smoothPackets < PACKETS_IN_FLIGHT_LIMIT &&
            fastRtt <= averageRtt + allowedRttJitter &&
            packetsInFlight - smoothPackets < allowedPacketsJitter
        ) {
            maximumBitrate += PACKETS_DIFF_INCREASE_FACTOR * headroom / PACKETS_IN_FLIGHT_LIMIT
            if (maximumBitrate > target) {
                maximumBitrate = target
            }
        }
    }

    private fun decreaseMaximum(factor: Double, minimumDecrease: Long) {
        maximumBitrate -= maxOf((maximumBitrate * (1 - factor)).toLong(), minimumDecrease)
    }

    private fun calculateBitrate(stats: StreamStats?) {
        var spike = fastPackets.toLong() - smoothPackets.toLong()
        if (spike > PACKETS_IN_FLIGHT_LIMIT) {
            maximumBitrate = (maximumBitrate * 0.95).toLong()
        }
        if (spike <= PACKETS_IN_FLIGHT_LIMIT / 5) {
            spike = 0
        }
        spike = spike.coerceIn(0, PACKETS_IN_FLIGHT_LIMIT)
        if (spike == PACKETS_IN_FLIGHT_LIMIT) {
            maximumBitrate -= 500_000
        }
        if (stats != null) maximumBitrate = stats.limitByTransportBitrate(maximumBitrate)
        maximumBitrate = minOf(maxOf(maximumBitrate, minimum), maxOf(target, minimum))
        bitrate = maxOf(maximumBitrate * (PACKETS_IN_FLIGHT_LIMIT - spike) / PACKETS_IN_FLIGHT_LIMIT, minimum)
        if ((fastPackets - smoothPackets).toInt() > PACKETS_IN_FLIGHT_LIMIT * 2) {
            bitrate = minimum
        }
    }
}
