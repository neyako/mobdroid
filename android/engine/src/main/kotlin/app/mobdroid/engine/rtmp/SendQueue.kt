package app.mobdroid.engine.rtmp

import app.mobdroid.engine.media.EncodedFrame
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class Taken {
    var frame: EncodedFrame? = null
    var isVideo = false
    var controlType = -1
    var controlBody: ByteArray? = null

    val isControl: Boolean
        get() = controlType >= 0
}

internal class SendQueue(
    private val maxBacklogNanos: Long,
    private val maxBytes: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Control(val type: Int, val body: ByteArray)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private var frames = arrayOfNulls<EncodedFrame>(64)
    private var enqueuedAt = LongArray(64)
    private var video = BooleanArray(64)
    private var head = 0
    private var count = 0
    private var bytes = 0L
    private var dropped = 0L
    private var dropUntilKeyFrame = false
    private var closed = false
    private val controls = ArrayDeque<Control>()

    val queuedBytes: Long
        get() = lock.withLock { bytes }

    val droppedVideoFrames: Long
        get() = lock.withLock { dropped }

    fun addVideo(frame: EncodedFrame): Boolean =
        lock.withLock {
            if (closed) {
                return false
            }
            if (dropUntilKeyFrame && !frame.isKeyFrame) {
                dropped++
                return false
            }
            val now = clock()
            if (isOverflowing(now, frame.data.size)) {
                if (!frame.isKeyFrame) {
                    dropUntilKeyFrame = true
                    dropped++
                    return false
                }
                removeBefore(count, now - maxBacklogNanos, countAsDropped = true)
            }
            dropUntilKeyFrame = false
            push(frame, true, now)
            true
        }

    fun addAudio(frame: EncodedFrame): Boolean =
        lock.withLock {
            if (closed || bytes + frame.data.size > maxBytes) {
                return false
            }
            push(frame, false, clock())
            true
        }

    fun postControl(type: Int, body: ByteArray) {
        lock.withLock {
            if (!closed) {
                controls.addLast(Control(type, body))
                notEmpty.signal()
            }
        }
    }

    fun trimToLatestKeyFrame() {
        lock.withLock {
            var limit = count
            for (i in count - 1 downTo 0) {
                val index = (head + i) % frames.size
                if (video[index] && frames[index]!!.isKeyFrame) {
                    limit = i
                    break
                }
            }
            removeBefore(limit, Long.MIN_VALUE, countAsDropped = false)
        }
    }

    fun take(into: Taken): Boolean {
        lock.lockInterruptibly()
        try {
            while (true) {
                if (closed) {
                    return false
                }
                val control = controls.removeFirstOrNull()
                if (control != null) {
                    into.frame = null
                    into.controlType = control.type
                    into.controlBody = control.body
                    return true
                }
                if (count > 0) {
                    val frame = frames[head]!!
                    into.frame = frame
                    into.isVideo = video[head]
                    into.controlType = -1
                    into.controlBody = null
                    frames[head] = null
                    head = (head + 1) % frames.size
                    count--
                    bytes -= frame.data.size
                    return true
                }
                notEmpty.await()
            }
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            frames.fill(null)
            count = 0
            bytes = 0
            controls.clear()
            notEmpty.signalAll()
        }
    }

    private fun isOverflowing(now: Long, incoming: Int): Boolean =
        (count > 0 && now - enqueuedAt[head] > maxBacklogNanos) || bytes + incoming > maxBytes

    private fun push(frame: EncodedFrame, isVideo: Boolean, now: Long) {
        if (count == frames.size) {
            grow()
        }
        val index = (head + count) % frames.size
        frames[index] = frame
        enqueuedAt[index] = now
        video[index] = isVideo
        count++
        bytes += frame.data.size
        notEmpty.signal()
    }

    private fun grow() {
        val size = frames.size
        val newFrames = arrayOfNulls<EncodedFrame>(size * 2)
        val newTimes = LongArray(size * 2)
        val newVideo = BooleanArray(size * 2)
        for (i in 0 until count) {
            val index = (head + i) % size
            newFrames[i] = frames[index]
            newTimes[i] = enqueuedAt[index]
            newVideo[i] = video[index]
        }
        frames = newFrames
        enqueuedAt = newTimes
        video = newVideo
        head = 0
    }

    private fun removeBefore(videoLimit: Int, audioEnqueuedBefore: Long, countAsDropped: Boolean) {
        val size = frames.size
        var kept = 0
        for (i in 0 until count) {
            val source = (head + i) % size
            val frame = frames[source]!!
            if (video[source] && i < videoLimit) {
                if (countAsDropped) {
                    dropped++
                }
                bytes -= frame.data.size
                continue
            }
            if (!video[source] && enqueuedAt[source] < audioEnqueuedBefore) {
                bytes -= frame.data.size
                continue
            }
            val target = (head + kept) % size
            if (target != source) {
                frames[target] = frame
                enqueuedAt[target] = enqueuedAt[source]
                video[target] = video[source]
            }
            kept++
        }
        for (i in kept until count) {
            frames[(head + i) % size] = null
        }
        count = kept
    }
}
