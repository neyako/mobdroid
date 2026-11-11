package com.opirl.app.engine.rist

internal class RistWeightedSelector {
    private var credits = IntArray(8)

    fun reset() {
        credits.fill(0)
    }

    fun pick(weights: IntArray, usable: BooleanArray, count: Int): Int {
        if (credits.size < count) {
            credits = credits.copyOf(count)
        }
        repeat(2) {
            var best = -1
            var bestCredit = 0
            for (index in 0 until count) {
                if (usable[index] && credits[index] > bestCredit) {
                    best = index
                    bestCredit = credits[index]
                }
            }
            if (best >= 0) {
                credits[best] -= 1
                return best
            }
            for (index in 0 until count) {
                credits[index] = weights[index]
            }
        }
        return -1
    }
}
