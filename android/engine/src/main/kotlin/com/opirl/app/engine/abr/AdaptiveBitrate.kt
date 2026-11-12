package com.opirl.app.engine.abr

import com.opirl.app.engine.net.PublisherStats

const val ADAPTIVE_BITRATE_START = 1_000_000L
const val ADAPTIVE_BITRATE_TRANSPORT_MINIMUM = ADAPTIVE_BITRATE_START
const val DEFAULT_SRT_LATENCY_MS = 3000
const val ADAPTIVE_BITRATE_UPDATE_INTERVAL_MS = 20L

private const val ACTION_LIFETIME_NANOS = 15_000_000_000L
private const val MAXIMUM_ACTIONS = 6

data class StreamStats(
    val rttMs: Double,
    val packetsInFlight: Double,
    val transportBitrate: Long? = null,
    val latencyMs: Int? = null,
    val mbpsSendRate: Double? = null,
    val relaxed: Boolean = false,
    val queuedBytes: Long = 0,
    val lostPercent: Double = 0.0,
) {
    fun limitByTransportBitrate(bitrate: Long): Long {
        val transport = transportBitrate ?: return bitrate
        val maximum = maxOf(transport + ADAPTIVE_BITRATE_TRANSPORT_MINIMUM, (17 * transport) / 10)
        return minOf(bitrate, maximum)
    }
}

fun PublisherStats.toStreamStats(
    latencyMs: Int? = null,
    transportBitrate: Long? = null,
    relaxed: Boolean = false,
): StreamStats = StreamStats(
    rttMs = rttMs,
    packetsInFlight = packetsInFlight.toDouble(),
    transportBitrate = transportBitrate,
    latencyMs = latencyMs,
    mbpsSendRate = sendBitrateBps / 1_000_000.0,
    relaxed = relaxed,
    queuedBytes = queuedBytes,
    lostPercent = lostPercent,
)

class TransportBitrateMeter(initialBitrate: Long) {
    var bitrate = initialBitrate
        private set
    private var previousBytes = -1L
    private var previousNanos = 0L

    fun update(totalBytesSent: Long, nowNanos: Long): Long {
        if (previousBytes in 0..totalBytesSent && nowNanos > previousNanos) {
            val seconds = (nowNanos - previousNanos) / 1e9
            val latest = 8 * (totalBytesSent - previousBytes) / seconds
            bitrate = (bitrate * 0.7 + latest * 0.3).toLong()
        }
        previousBytes = totalBytesSent
        previousNanos = nowNanos
        return bitrate
    }

    fun pause() {
        previousBytes = -1
    }
}

private class ActionTaken(val nanos: Long, val message: String)

abstract class AdaptiveBitrate {
    var recordActions = false
    private val actions = ArrayDeque<ActionTaken>()

    abstract val bitrate: Long

    abstract fun setTargetBitrate(bitrate: Long)

    abstract fun setMinimumBitrate(bitrate: Long)

    abstract fun update(stats: StreamStats, nowNanos: Long = System.nanoTime()): Long

    fun actionsTaken(nowNanos: Long = System.nanoTime()): List<String> {
        while (actions.isNotEmpty() && nowNanos - actions.first().nanos > ACTION_LIFETIME_NANOS) {
            actions.removeFirst()
        }
        return actions.map { it.message }
    }

    protected fun logAction(nowNanos: Long, message: String) {
        actions.addLast(ActionTaken(nowNanos, message))
        while (actions.size > MAXIMUM_ACTIONS) {
            actions.removeFirst()
        }
    }
}
