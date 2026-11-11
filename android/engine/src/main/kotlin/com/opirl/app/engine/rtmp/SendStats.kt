package com.opirl.app.engine.rtmp

private const val BUCKET_NANOS = 100_000_000L
private const val BUCKETS = 10
private const val MAX_TIMINGS = 500
private const val BYTES_PER_PACKET = 1400

internal class RateWindow(private val clock: () -> Long = System::nanoTime) {
    private val origin = clock()
    private val buckets = LongArray(BUCKETS)
    private var current = 0L

    @Synchronized
    fun add(bytes: Long) {
        advance(clock() - origin)
        buckets[(current % BUCKETS).toInt()] += bytes
    }

    @Synchronized
    fun bitsPerSecond(): Long {
        val elapsed = clock() - origin
        advance(elapsed)
        val covered = minOf((BUCKETS - 1) * BUCKET_NANOS + elapsed % BUCKET_NANOS, elapsed).coerceAtLeast(BUCKET_NANOS)
        return (buckets.sum() * 8.0 * 1_000_000_000.0 / covered).toLong()
    }

    private fun advance(elapsed: Long) {
        val index = elapsed / BUCKET_NANOS
        if (index - current >= BUCKETS) {
            buckets.fill(0)
        } else {
            for (i in current + 1..index) {
                buckets[(i % BUCKETS).toInt()] = 0
            }
        }
        if (index > current) {
            current = index
        }
    }
}

internal class AckTracker(private val clock: () -> Long = System::nanoTime) {
    private val timingTimes = LongArray(MAX_TIMINGS)
    private val timingSequences = LongArray(MAX_TIMINGS)
    private var timingHead = 0
    private var timingCount = 0
    private var written = 0L
    private var ackedLow = 0L
    private var ackedHigh = 0L
    private var rtt = 0.0
    private var inFlight = 0

    val rttMs: Double
        @Synchronized get() = rtt

    val packetsInFlight: Int
        @Synchronized get() = inFlight

    @Synchronized
    fun onWritten(totalBytes: Long) {
        written = totalBytes
        if (timingCount < MAX_TIMINGS) {
            val index = (timingHead + timingCount) % MAX_TIMINGS
            timingTimes[index] = clock()
            timingSequences[index] = totalBytes
            timingCount++
        }
        inFlight = computeInFlight()
    }

    @Synchronized
    fun onAck(sequence: Long) {
        if (sequence < ackedLow) {
            ackedHigh += if (ackedLow <= Int.MAX_VALUE) 0x8000_0000L else 0x1_0000_0000L
        }
        ackedLow = sequence
        val acked = ackedHigh + ackedLow
        var sentAt = 0L
        var found = false
        while (timingCount > 0 && acked > timingSequences[timingHead]) {
            sentAt = timingTimes[timingHead]
            found = true
            timingHead = (timingHead + 1) % MAX_TIMINGS
            timingCount--
        }
        if (found) {
            rtt = (clock() - sentAt) / 1_000_000.0
            inFlight = computeInFlight()
        }
    }

    private fun computeInFlight(): Int = (maxOf(written - (ackedHigh + ackedLow), 0L) / BYTES_PER_PACKET).toInt()
}
