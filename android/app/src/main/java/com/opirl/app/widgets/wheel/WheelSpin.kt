package com.opirl.app.widgets.wheel

import kotlin.random.Random

const val SPIN_MS = 12_000L
private const val PAUSE_MS = 3_000L
private const val HOLD_MS = 5_000L
private const val LEAD_MS = 1_500L
private const val FRAME_MS = 33L
private const val IDLE = Long.MAX_VALUE

class WheelTick(val rotation: Double, val finished: Boolean, val shown: Boolean, val wakeAt: Long)

class WheelResult(val text: String, val time: Long)

private fun turned(speed: Double, milliseconds: Long): Double {
    val seconds = milliseconds / 1000.0
    return Math.toDegrees(speed * (seconds - seconds * seconds / (2 * SPIN_MS / 1000.0)))
}

private fun normalized(degrees: Double) = (degrees % 360 + 360) % 360

class WheelSpin(private val random: Random = Random.Default) {
    private var pending = 0
    private var start = IDLE
    private var speed = 0.0
    private var rest = 0.0
    private var reported = true

    @Synchronized
    fun spin() {
        pending++
    }

    @Synchronized
    fun update(now: Long, onlyWhenSpinning: Boolean): WheelTick {
        var finished = false
        if (start != IDLE) {
            val elapsed = now - start
            if (elapsed >= SPIN_MS && !reported) {
                reported = true
                finished = true
            }
            if (elapsed >= SPIN_MS + HOLD_MS) {
                rest = normalized(rest + turned(speed, SPIN_MS))
                start = IDLE
            }
        }
        if (pending > 0 && (start == IDLE || now - start >= SPIN_MS + PAUSE_MS)) {
            if (start != IDLE) rest = normalized(rest + turned(speed, SPIN_MS))
            pending--
            speed = random.nextDouble(8.0, 15.0)
            start = now + if (onlyWhenSpinning && start == IDLE) LEAD_MS else 0
            reported = false
        }
        val rotation = if (start == IDLE) rest else normalized(rest + turned(speed, (now - start).coerceIn(0, SPIN_MS)))
        val shown = !onlyWhenSpinning || start != IDLE || pending > 0
        val wakeAt = when {
            start == IDLE -> IDLE
            now < start -> start
            now - start < SPIN_MS -> minOf(now + FRAME_MS, start + SPIN_MS)
            else -> minOf(start + SPIN_MS + HOLD_MS, if (pending > 0) start + SPIN_MS + PAUSE_MS else IDLE)
        }
        return WheelTick(rotation, finished, shown, wakeAt)
    }
}

class WheelState(random: Random = Random.Default) {
    val spin = WheelSpin(random)
    private val results = ArrayList<WheelResult>()

    @Synchronized
    fun results(): List<WheelResult> = results.toList()

    @Synchronized
    fun add(result: WheelResult) {
        results.add(0, result)
        if (results.size > 100) results.removeAt(100)
    }

    @Synchronized
    fun remove(result: WheelResult) {
        results.remove(result)
    }
}
