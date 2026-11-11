package com.opirl.app.engine.net

import com.opirl.app.engine.media.MediaSink

data class PublisherStats(
    val bytesSent: Long = 0,
    val sendBitrateBps: Long = 0,
    val rttMs: Double = 0.0,
    val queuedBytes: Long = 0,
    val packetsInFlight: Int = 0,
    val retransmittedPackets: Long = 0,
    val droppedVideoFrames: Long = 0,
)

interface PublisherListener {
    fun onConnected()

    fun onDisconnected(reason: String)
}

interface Publisher : MediaSink {
    fun start()

    fun stop()

    fun stats(): PublisherStats
}
