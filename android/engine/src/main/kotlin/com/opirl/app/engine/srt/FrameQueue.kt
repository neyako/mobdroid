package com.opirl.app.engine.srt

import com.opirl.app.engine.media.EncodedFrame

internal class VideoItem(val frame: EncodedFrame)

internal class AudioItem(val frame: EncodedFrame)

internal class FrameQueue(private val maximumBytes: Long) {
    private val lock = Any()
    private val items = ArrayDeque<Any>()
    private var bytes = 0L
    private var open = false
    private var closed = false
    private var waitingForKeyFrame = true
    private var drainScheduled = false

    @Volatile var droppedVideoFrames = 0L
        private set

    @Volatile var droppedAudioFrames = 0L
        private set

    @Volatile var queuedBytes = 0L
        private set

    fun open() {
        synchronized(lock) {
            if (!closed) {
                open = true
                waitingForKeyFrame = true
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            open = false
            items.clear()
            bytes = 0
            queuedBytes = 0
        }
    }

    fun addFormat(format: Any): Boolean = synchronized(lock) {
        if (closed) {
            return false
        }
        items.addLast(format)
        scheduleDrain()
    }

    fun addVideo(frame: EncodedFrame): Boolean = synchronized(lock) {
        if (!open) {
            return false
        }
        if (waitingForKeyFrame) {
            if (!frame.isKeyFrame) {
                droppedVideoFrames += 1
                return false
            }
            waitingForKeyFrame = false
        }
        if (bytes + frame.data.size > maximumBytes) {
            dropQueuedVideo()
            if (!frame.isKeyFrame) {
                waitingForKeyFrame = true
                droppedVideoFrames += 1
                return false
            }
        }
        append(VideoItem(frame), frame.data.size)
    }

    fun addAudio(frame: EncodedFrame): Boolean = synchronized(lock) {
        if (!open) {
            return false
        }
        if (bytes + frame.data.size > maximumBytes) {
            if (dropQueuedVideo() > 0) {
                waitingForKeyFrame = true
            }
            if (bytes + frame.data.size > maximumBytes) {
                droppedAudioFrames += 1
                return false
            }
        }
        append(AudioItem(frame), frame.data.size)
    }

    fun poll(): Any? = synchronized(lock) {
        val item = items.removeFirstOrNull()
        if (item == null) {
            drainScheduled = false
            return null
        }
        when (item) {
            is VideoItem -> bytes -= item.frame.data.size
            is AudioItem -> bytes -= item.frame.data.size
        }
        queuedBytes = bytes
        item
    }

    private fun append(item: Any, size: Int): Boolean {
        items.addLast(item)
        bytes += size
        queuedBytes = bytes
        return scheduleDrain()
    }

    private fun scheduleDrain(): Boolean {
        if (drainScheduled) {
            return false
        }
        drainScheduled = true
        return true
    }

    private fun dropQueuedVideo(): Int {
        var removed = 0
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is VideoItem) {
                bytes -= item.frame.data.size
                removed += 1
                iterator.remove()
            }
        }
        droppedVideoFrames += removed
        queuedBytes = bytes
        return removed
    }
}
