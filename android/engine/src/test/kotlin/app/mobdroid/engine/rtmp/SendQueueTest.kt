package app.mobdroid.engine.rtmp

import app.mobdroid.engine.media.EncodedFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendQueueTest {
    private var now = 0L
    private val queue = SendQueue(3_000_000_000L, 1_000_000L) { now }

    private fun frame(size: Int, key: Boolean = false, pts: Long = 0) = EncodedFrame(ByteArray(size), pts, key)

    private fun advanceSeconds(seconds: Double) {
        now += (seconds * 1e9).toLong()
    }

    private fun drain(): List<String> {
        val taken = Taken()
        val items = ArrayList<String>()
        while (queue.queuedBytes > 0) {
            queue.take(taken)
            items.add("${if (taken.isVideo) "v" else "a"}${taken.frame!!.data.size}${if (taken.frame!!.isKeyFrame) "k" else ""}")
        }
        return items
    }

    @Test
    fun framesLeaveInArrivalOrderAndBytesAreAccounted() {
        queue.addVideo(frame(100, key = true))
        queue.addAudio(frame(10))
        queue.addVideo(frame(50))
        assertEquals(160L, queue.queuedBytes)
        assertEquals(listOf("v100k", "a10", "v50"), drain())
        assertEquals(0L, queue.queuedBytes)
    }

    @Test
    fun controlMessagesOvertakeMediaAndAreNotCountedAsBacklog() {
        queue.addVideo(frame(100, key = true))
        queue.postControl(MSG_ACK, byteArrayOf(1, 2, 3, 4))
        val taken = Taken()
        queue.take(taken)
        assertTrue(taken.isControl)
        assertEquals(MSG_ACK, taken.controlType)
        assertNull(taken.frame)
        queue.take(taken)
        assertFalse(taken.isControl)
        assertEquals(100, taken.frame!!.data.size)
    }

    @Test
    fun videoBacklogOlderThanTheLimitDropsFramesUntilTheNextKeyFrameButNeverAudio() {
        queue.addVideo(frame(100, key = true))
        queue.addVideo(frame(100))
        advanceSeconds(3.5)
        assertFalse(queue.addVideo(frame(100)))
        assertFalse(queue.addVideo(frame(100)))
        assertEquals(2L, queue.droppedVideoFrames)
        assertTrue(queue.addAudio(frame(10)))
        advanceSeconds(0.5)
        assertFalse(queue.addVideo(frame(100)))
        assertEquals(3L, queue.droppedVideoFrames)
        assertEquals(210L, queue.queuedBytes)
    }

    @Test
    fun aKeyFrameDuringOverflowFlushesStaleVideoAndKeepsAudio() {
        queue.addVideo(frame(100, key = true))
        queue.addAudio(frame(10))
        queue.addVideo(frame(100))
        advanceSeconds(3.5)
        assertFalse(queue.addVideo(frame(100)))
        assertTrue(queue.addVideo(frame(200, key = true)))
        assertEquals(3L, queue.droppedVideoFrames)
        assertEquals(listOf("a10", "v200k"), drain())
        assertTrue(queue.addVideo(frame(100)))
    }

    @Test
    fun aKeyFrameAfterTheBacklogDrainedIsAcceptedWithoutFlushingAnything() {
        queue.addVideo(frame(100, key = true))
        advanceSeconds(3.5)
        assertFalse(queue.addVideo(frame(100)))
        drain()
        assertTrue(queue.addVideo(frame(100, key = true)))
        assertTrue(queue.addVideo(frame(100)))
        assertEquals(1L, queue.droppedVideoFrames)
        assertEquals(listOf("v100k", "v100"), drain())
    }

    @Test
    fun theByteCapStopsVideoAtOnceAndAudioOnlyWhenTheQueueIsFull() {
        queue.addVideo(frame(600_000, key = true))
        assertFalse(queue.addVideo(frame(600_000)))
        assertEquals(1L, queue.droppedVideoFrames)
        assertTrue(queue.addAudio(frame(300_000)))
        assertFalse(queue.addAudio(frame(300_000)))
        assertEquals(900_000L, queue.queuedBytes)
    }

    @Test
    fun trimKeepsTheLatestKeyFrameAndEverythingAfterItAndAllAudioWithoutCountingDrops() {
        queue.addVideo(frame(1, key = true))
        queue.addAudio(frame(2))
        queue.addVideo(frame(3))
        queue.addVideo(frame(4, key = true))
        queue.addAudio(frame(5))
        queue.addVideo(frame(6))
        queue.trimToLatestKeyFrame()
        assertEquals(0L, queue.droppedVideoFrames)
        assertEquals(listOf("a2", "v4k", "a5", "v6"), drain())
    }

    @Test
    fun trimWithoutAKeyFrameRemovesAllVideo() {
        queue.addVideo(frame(1))
        queue.addAudio(frame(2))
        queue.addVideo(frame(3))
        queue.trimToLatestKeyFrame()
        assertEquals(0L, queue.droppedVideoFrames)
        assertEquals(listOf("a2"), drain())
    }

    @Test
    fun growthPastTheInitialCapacityKeepsOrderAcrossWrapAround() {
        val taken = Taken()
        repeat(40) { queue.addAudio(frame(it + 1)) }
        repeat(30) { queue.take(taken) }
        repeat(100) { queue.addAudio(frame(it + 100)) }
        val sizes = ArrayList<Int>()
        while (queue.queuedBytes > 0) {
            queue.take(taken)
            sizes.add(taken.frame!!.data.size)
        }
        assertEquals((31..40).toList() + (100..199).toList(), sizes)
    }

    @Test
    fun closeWakesAWaitingConsumerAndRejectsNewFrames() {
        val started = CountDownLatch(1)
        var result: Boolean? = null
        val consumer = Thread {
            started.countDown()
            result = queue.take(Taken())
        }
        consumer.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        queue.close()
        consumer.join(5000)
        assertEquals(false, result)
        assertFalse(queue.addVideo(frame(1, key = true)))
        assertFalse(queue.addAudio(frame(1)))
        assertEquals(0L, queue.queuedBytes)
    }
}
