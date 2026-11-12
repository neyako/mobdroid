package com.opirl.app.widgets.alerts

const val MIN_FRAME_INTERVAL_MS = 33L

class AlertTimeline(private val durationsMs: IntArray, val loops: Int) {
    private val loopMs = durationsMs.sumOf { it.toLong() }
    val totalMs: Long = loopMs * loops

    fun frameAt(elapsedMs: Long): Int? {
        if (loopMs == 0L || elapsedMs >= totalMs) return null
        var remaining = elapsedMs.coerceAtLeast(0) % loopMs
        for (index in durationsMs.indices) {
            if (remaining < durationsMs[index]) return index
            remaining -= durationsMs[index]
        }
        return durationsMs.lastIndex
    }

    fun nextChangeMs(elapsedMs: Long): Long {
        if (loopMs == 0L || elapsedMs >= totalMs) return totalMs
        val elapsed = elapsedMs.coerceAtLeast(0)
        val loopStart = elapsed - elapsed % loopMs
        val inLoop = elapsed - loopStart
        var end = 0L
        for (duration in durationsMs) {
            end += duration
            if (inLoop < end) return loopStart + end
        }
        return loopStart + loopMs
    }

    fun nextDrawAt(startedAt: Long, now: Long): Long = maxOf(startedAt + nextChangeMs(now - startedAt), now + MIN_FRAME_INTERVAL_MS)
}

class AlertSequence<E : Any, P : Any, C : Any>(private val limit: Int = 100) {
    class Active<C>(val content: C, val startedAt: Long, val endAt: Long)

    private val queue = ArrayDeque<E>()
    private var current: Active<C>? = null
    private var freeAt = 0L
    private var preparing = false

    @Synchronized
    fun enqueue(event: E) {
        if (queue.size >= limit) queue.removeFirst()
        queue.addLast(event)
    }

    @Synchronized
    fun active(now: Long): Active<C>? {
        current?.let {
            if (now >= it.endAt) {
                current = null
                freeAt = it.endAt + ALERT_DELAY_AFTER_MS
            }
        }
        return current
    }

    @Synchronized
    fun begin(now: Long, resolve: (E) -> P?): P? {
        if (preparing || current != null || now < freeAt) return null
        while (true) {
            val event = queue.removeFirstOrNull() ?: return null
            val plan = resolve(event)
            if (plan != null) {
                preparing = true
                return plan
            }
        }
    }

    @Synchronized
    fun start(content: C, now: Long, durationMs: Long) {
        preparing = false
        current = Active(content, now, now + durationMs)
    }

    @Synchronized
    fun wakeAt(): Long? = if (queue.isNotEmpty() && !preparing && current == null) freeAt else null

    @Synchronized
    fun clear() {
        queue.clear()
        current = null
        preparing = false
    }
}
