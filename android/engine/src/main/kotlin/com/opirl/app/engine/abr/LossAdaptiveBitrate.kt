package com.opirl.app.engine.abr

private const val DECREASE_INTERVAL_NANOS = 1_000_000_000L
private const val INCREASE_INTERVAL_NANOS = 1_000_000_000L
private const val RECOVERY_HOLD_NANOS = 4_000_000_000L
private const val HIGH_LOSS_PERCENT = 10.0
private const val LOW_LOSS_PERCENT = 2.0
private const val INCREASE_MINIMUM = 50_000L
private const val INCREASE_SCALE = 20L

class LossAdaptiveBitrate(
    targetBitrate: Long,
    minimumBitrate: Long = 250_000,
) : AdaptiveBitrate() {
    private var target = targetBitrate
    private var minimum = minimumBitrate
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
        var next = bitrate
        if (stats.lostPercent >= HIGH_LOSS_PERCENT) {
            if (lastDecreaseNanos == Long.MIN_VALUE || nowNanos - lastDecreaseNanos >= DECREASE_INTERVAL_NANOS) {
                next = (next * (1 - minOf(stats.lostPercent, 100.0) / 200)).toLong()
                lastDecreaseNanos = nowNanos
                if (recordActions) {
                    logAction(nowNanos, "Decr: loss ${stats.lostPercent.toInt()}%, bitrate ${bitrate / 1000} -> ${next / 1000}")
                }
            }
        } else if (
            stats.lostPercent < LOW_LOSS_PERCENT &&
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
