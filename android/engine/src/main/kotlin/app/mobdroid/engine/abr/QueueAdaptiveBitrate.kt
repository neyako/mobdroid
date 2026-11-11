package app.mobdroid.engine.abr

private const val DECREASE_INTERVAL_NANOS = 500_000_000L
private const val INCREASE_INTERVAL_NANOS = 1_000_000_000L
private const val RECOVERY_HOLD_NANOS = 4_000_000_000L
private const val HIGH_QUEUE_MS = 400L
private const val GROWING_QUEUE_MS = 150L
private const val LOW_QUEUE_MS = 100L
private const val INCREASE_MINIMUM = 50_000L
private const val INCREASE_SCALE = 20L

class QueueAdaptiveBitrate(
    targetBitrate: Long,
    minimumBitrate: Long = 250_000,
) : AdaptiveBitrate() {
    private var target = targetBitrate
    private var minimum = minimumBitrate
    private var previousQueuedBytes = 0L
    private var lastDecreaseNanos = Long.MIN_VALUE
    private var lastIncreaseNanos = Long.MIN_VALUE

    override var bitrate: Long = clampBitrate(targetBitrate)
        private set

    override fun setTargetBitrate(bitrate: Long) {
        target = bitrate
    }

    override fun setMinimumBitrate(bitrate: Long) {
        minimum = bitrate
    }

    override fun update(stats: StreamStats, nowNanos: Long): Long {
        val queuedMs = stats.queuedBytes * 8000 / maxOf(bitrate, 1)
        val growing = stats.queuedBytes > previousQueuedBytes
        previousQueuedBytes = stats.queuedBytes
        var next = bitrate
        if (queuedMs >= HIGH_QUEUE_MS || (growing && queuedMs >= GROWING_QUEUE_MS)) {
            if (lastDecreaseNanos == Long.MIN_VALUE || nowNanos - lastDecreaseNanos >= DECREASE_INTERVAL_NANOS) {
                next = next * 3 / 4
                val measured = stats.transportBitrate
                if (measured != null && measured > 0) {
                    next = minOf(next, measured * 9 / 10)
                }
                lastDecreaseNanos = nowNanos
                if (recordActions) {
                    logAction(nowNanos, "Decr: queue ${queuedMs} ms, bitrate ${bitrate / 1000} -> ${next / 1000}")
                }
            }
        } else if (
            queuedMs < LOW_QUEUE_MS &&
            (lastDecreaseNanos == Long.MIN_VALUE || nowNanos - lastDecreaseNanos >= RECOVERY_HOLD_NANOS) &&
            (lastIncreaseNanos == Long.MIN_VALUE || nowNanos - lastIncreaseNanos >= INCREASE_INTERVAL_NANOS)
        ) {
            next += maxOf(INCREASE_MINIMUM, next / INCREASE_SCALE)
            lastIncreaseNanos = nowNanos
        }
        bitrate = clampBitrate(stats.limitByTransportBitrate(next))
        return bitrate
    }

    private fun clampBitrate(value: Long): Long = maxOf(minOf(value, target), minimum)
}
