package com.opirl.app.engine.rist

import com.opirl.app.engine.abr.RistAdaptiveBitrate

private const val TARGET_BITRATE = 10_000_000L

internal class RistLinkWeight {
    private val rate = RistAdaptiveBitrate(TARGET_BITRATE)

    val weight: Int get() = maxOf(rate.bitrate / (TARGET_BITRATE / 25), 1).toInt()

    fun update(rttMs: Double) {
        rate.update(rttMs)
    }
}
