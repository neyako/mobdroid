package com.opirl.app.engine.abr

import java.util.Locale

private const val BITRATE_INCREASE_MINIMUM = 100_000L
private const val BITRATE_INCREASE_INTERVAL_NANOS = 400_000_000L
private const val BITRATE_INCREASE_SCALE = 30L
private const val BITRATE_DECREASE_MINIMUM = 100_000L
private const val BITRATE_DECREASE_INTERVAL_NANOS = 200_000_000L
private const val BITRATE_DECREASE_FAST_INTERVAL_NANOS = 250_000_000L
private const val BITRATE_DECREASE_SCALE = 10L

class BelaboxAdaptiveBitrate(
    targetBitrate: Long,
    minimumBitrate: Long = 250_000,
) : AdaptiveBitrate() {
    private var target = targetBitrate
    private var minimum = minimumBitrate
    private var sendBufferSizeAverage = 0.0
    private var sendBufferSizeJitter = 0.0
    private var previousSendBufferSize = 0.0
    private var rttAverage = 0.0
    private var rttAverageDelta = 0.0
    private var previousRtt = 300.0
    private var rttMin = 200.0
    private var rttJitter = 0.0
    private var throughput = 0.0
    private var nextIncreaseNanos = Long.MIN_VALUE
    private var nextDecreaseNanos = Long.MIN_VALUE

    override var bitrate: Long = clampBitrate(ADAPTIVE_BITRATE_START)
        private set

    override fun setTargetBitrate(bitrate: Long) {
        target = bitrate
    }

    override fun setMinimumBitrate(bitrate: Long) {
        minimum = bitrate
    }

    override fun update(stats: StreamStats, nowNanos: Long): Long {
        val sendRate = stats.mbpsSendRate
        if (stats.rttMs == 0.0 || sendRate == null) {
            return bitrate
        }
        val sendBufferSize = stats.packetsInFlight
        sendBufferSizeAverage = sendBufferSizeAverage * 0.99 + sendBufferSize * 0.01
        sendBufferSizeJitter *= 0.99
        val deltaSendBufferSize = sendBufferSize - previousSendBufferSize
        if (deltaSendBufferSize > sendBufferSizeJitter) {
            sendBufferSizeJitter = deltaSendBufferSize
        }
        previousSendBufferSize = sendBufferSize
        val rtt = stats.rttMs
        rttAverage = if (rttAverage == 0.0) rtt else rttAverage * 0.99 + 0.01 * rtt
        val deltaRtt = rtt - previousRtt
        rttAverageDelta = rttAverageDelta * 0.8 + deltaRtt * 0.2
        previousRtt = rtt
        rttMin *= 1.001
        if (rtt != 100.0 && rtt < rttMin && rttAverageDelta < 1.0) {
            rttMin = rtt
        }
        rttJitter *= 0.99
        if (deltaRtt > rttJitter) {
            rttJitter = deltaRtt
        }
        throughput *= 0.97
        throughput += (sendRate * 1000.0 * 1000.0 / 1024.0) * 0.03
        val latency = (stats.latencyMs ?: DEFAULT_SRT_LATENCY_MS).toDouble()
        var next = bitrate
        val sendBufferSizeTh3 = (sendBufferSizeAverage + sendBufferSizeJitter) * 4
        var sendBufferSizeTh2 = maxOf(
            50.0,
            sendBufferSizeAverage + maxOf(sendBufferSizeJitter * 3.0, sendBufferSizeAverage),
        )
        sendBufferSizeTh2 = minOf(sendBufferSizeTh2, rttToSendBufferSize(latency / 2, throughput))
        if (stats.relaxed) {
            sendBufferSizeTh2 *= 2
        }
        val sendBufferSizeTh1 = maxOf(50.0, sendBufferSizeAverage + sendBufferSizeJitter * 2.5)
        val rttThMax = rttAverage + maxOf(rttJitter * 4, rttAverage * 15 / 100)
        val rttThMin = rttMin + maxOf(1.0, rttJitter * 2)
        if (next > minimum && (rtt >= latency / 3 || sendBufferSize > sendBufferSizeTh3)) {
            next = minimum
            nextDecreaseNanos = nowNanos + BITRATE_DECREASE_INTERVAL_NANOS
            if (recordActions) {
                logAction(
                    nowNanos,
                    "Set min: ${minimum / 1000}, rtt: ${format(rtt)} >= latency / 3: ${format(latency / 3)} " +
                        "or bs: ${format(sendBufferSize)} > bs_th3: ${format(sendBufferSizeTh3)}",
                )
            }
        } else if (nowNanos > nextDecreaseNanos && (rtt > latency / 5 || sendBufferSize > sendBufferSizeTh2)) {
            val decrease = BITRATE_DECREASE_MINIMUM + next / BITRATE_DECREASE_SCALE
            next -= decrease
            nextDecreaseNanos = nowNanos + BITRATE_DECREASE_FAST_INTERVAL_NANOS
            if (recordActions) {
                logAction(
                    nowNanos,
                    "Fast decr: ${decrease / 1000}, rtt: ${format(rtt)} > latency / 5: ${format(latency / 5)} " +
                        "or bs: ${format(sendBufferSize)} > bs_th2: ${format(sendBufferSizeTh2)}",
                )
            }
        } else if (nowNanos > nextDecreaseNanos && (rtt > rttThMax || sendBufferSize > sendBufferSizeTh1)) {
            next -= BITRATE_DECREASE_MINIMUM
            nextDecreaseNanos = nowNanos + BITRATE_DECREASE_INTERVAL_NANOS
            if (recordActions) {
                logAction(
                    nowNanos,
                    "Decr: ${BITRATE_DECREASE_MINIMUM / 1000}, rtt: ${format(rtt)} > rtt_th_max: " +
                        "${format(rttThMax)} or bs: ${format(sendBufferSize)} > bs_th1: ${format(sendBufferSizeTh1)}",
                )
            }
        } else if (nowNanos > nextIncreaseNanos && rtt < rttThMin && rttAverageDelta < 0.01) {
            next += BITRATE_INCREASE_MINIMUM + next / BITRATE_INCREASE_SCALE
            nextIncreaseNanos = nowNanos + BITRATE_INCREASE_INTERVAL_NANOS
        }
        bitrate = clampBitrate(stats.limitByTransportBitrate(next))
        return bitrate
    }

    private fun clampBitrate(value: Long): Long = maxOf(minOf(value, target), minimum)

    private fun rttToSendBufferSize(rtt: Double, throughput: Double): Double = (throughput / 8) * rtt / 1316

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}
