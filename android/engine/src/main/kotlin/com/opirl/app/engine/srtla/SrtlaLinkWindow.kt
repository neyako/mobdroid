package com.opirl.app.engine.srtla

import com.opirl.app.engine.srt.isSrtSnAcked

internal const val WINDOW_DEFAULT = 20
internal const val WINDOW_MINIMUM = 1
internal const val WINDOW_MAXIMUM = 60
internal const val WINDOW_STABLE_MINIMUM = 10
internal const val WINDOW_STABLE_MAXIMUM = 20
internal const val WINDOW_MULTIPLY = 1000
internal const val WINDOW_DECREMENT = 100
internal const val WINDOW_INCREMENT = 30

private const val IN_FLIGHT_CAPACITY = 4096

internal class SrtlaLinkWindow {
    var windowSize = WINDOW_DEFAULT * WINDOW_MULTIPLY
        private set
    private val inFlight = IntArray(IN_FLIGHT_CAPACITY)
    var packetsInFlight = 0
        private set

    fun reset() {
        windowSize = WINDOW_DEFAULT * WINDOW_MULTIPLY
        packetsInFlight = 0
    }

    fun onSend(sequenceNumber: Int) {
        if (indexOf(sequenceNumber) >= 0) {
            return
        }
        if (packetsInFlight == IN_FLIGHT_CAPACITY) {
            removeAt(0)
        }
        inFlight[packetsInFlight] = sequenceNumber
        packetsInFlight += 1
    }

    fun onSrtAck(ackSequenceNumber: Int) {
        var kept = 0
        for (index in 0 until packetsInFlight) {
            val sequenceNumber = inFlight[index]
            if (!isSrtSnAcked(sequenceNumber, ackSequenceNumber)) {
                inFlight[kept] = sequenceNumber
                kept += 1
            }
        }
        packetsInFlight = kept
    }

    fun onNak(sequenceNumber: Int) {
        if (!remove(sequenceNumber)) {
            return
        }
        windowSize = maxOf(windowSize - WINDOW_DECREMENT, WINDOW_MINIMUM * WINDOW_MULTIPLY)
    }

    fun onSrtlaAck(sequenceNumber: Int) {
        if (remove(sequenceNumber) && packetsInFlight * WINDOW_MULTIPLY > windowSize) {
            windowSize += WINDOW_INCREMENT - 1
        }
        windowSize = minOf(windowSize + 1, WINDOW_MAXIMUM * WINDOW_MULTIPLY)
    }

    fun score(priority: Float): Int {
        val score = windowSize / (packetsInFlight + 1)
        return if (windowSize > WINDOW_STABLE_MAXIMUM * WINDOW_MULTIPLY) {
            (score * priority).toInt()
        } else if (windowSize > WINDOW_STABLE_MINIMUM * WINDOW_MULTIPLY) {
            var factor = (windowSize - WINDOW_STABLE_MINIMUM * WINDOW_MULTIPLY).toFloat()
            factor /= ((WINDOW_STABLE_MAXIMUM - WINDOW_STABLE_MINIMUM) * WINDOW_MULTIPLY).toFloat()
            val scaledPriority = 1 + (priority - 1) * factor
            (score * scaledPriority).toInt()
        } else {
            score
        }
    }

    private fun indexOf(sequenceNumber: Int): Int {
        for (index in packetsInFlight - 1 downTo 0) {
            if (inFlight[index] == sequenceNumber) {
                return index
            }
        }
        return -1
    }

    private fun remove(sequenceNumber: Int): Boolean {
        val index = indexOf(sequenceNumber)
        if (index < 0) {
            return false
        }
        removeAt(index)
        return true
    }

    private fun removeAt(index: Int) {
        packetsInFlight -= 1
        inFlight[index] = inFlight[packetsInFlight]
    }
}
