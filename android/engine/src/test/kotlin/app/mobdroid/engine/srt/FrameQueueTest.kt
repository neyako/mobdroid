package app.mobdroid.engine.srt

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun frame(size: Int, key: Boolean = false, pts: Long = 0) = EncodedFrame(ByteArray(size), pts, key)

private fun FrameQueue.drain(): List<Any> {
    val items = ArrayList<Any>()
    while (true) {
        items.add(poll() ?: return items)
    }
}

private fun List<Any>.describe(): List<String> = map {
    when (it) {
        is VideoItem -> "V${it.frame.ptsUs}" + if (it.frame.isKeyFrame) "k" else ""
        is AudioItem -> "A${it.frame.ptsUs}"
        else -> "F"
    }
}

class FrameQueueTest {
    @Test
    fun dropsFramesUntilOpened() {
        val queue = FrameQueue(1000)
        assertTrue(!queue.addVideo(frame(10, true)))
        assertTrue(!queue.addAudio(frame(10)))
        assertNull(queue.poll())
        assertEquals(0, queue.droppedVideoFrames)
    }

    @Test
    fun formatsAreKeptBeforeOpen() {
        val queue = FrameQueue(1000)
        val format = AudioFormat(48000, 2, ByteArray(2))
        assertTrue(queue.addFormat(format))
        assertSame(format, queue.poll())
    }

    @Test
    fun firstVideoFrameAfterOpenMustBeKeyFrame() {
        val queue = FrameQueue(1000)
        queue.open()
        queue.addVideo(frame(10, false, 1))
        queue.addVideo(frame(10, true, 2))
        queue.addVideo(frame(10, false, 3))
        assertEquals(listOf("V2k", "V3"), queue.drain().describe())
        assertEquals(1, queue.droppedVideoFrames)
    }

    @Test
    fun wakesDrainerOncePerBurst() {
        val queue = FrameQueue(1000)
        queue.open()
        assertTrue(queue.addVideo(frame(10, true)))
        assertTrue(!queue.addAudio(frame(10)))
        queue.drain()
        assertTrue(queue.addAudio(frame(10)))
    }

    @Test
    fun overflowDropsQueuedVideoAndWaitsForNextKeyFrameButKeepsAudio() {
        val queue = FrameQueue(100)
        queue.open()
        queue.addVideo(frame(40, true, 1))
        queue.addAudio(frame(5, pts = 2))
        queue.addVideo(frame(40, false, 3))
        queue.addVideo(frame(40, false, 4))
        queue.addAudio(frame(5, pts = 5))
        queue.addVideo(frame(40, false, 6))
        queue.addVideo(frame(40, true, 7))
        queue.addVideo(frame(40, false, 8))
        assertEquals(listOf("A2", "A5", "V7k", "V8"), queue.drain().describe())
        assertEquals(4, queue.droppedVideoFrames)
        assertEquals(0, queue.droppedAudioFrames)
        assertEquals(0L, queue.queuedBytes)
    }

    @Test
    fun keyFrameThatOverflowsReplacesQueuedVideo() {
        val queue = FrameQueue(100)
        queue.open()
        queue.addVideo(frame(60, true, 1))
        queue.addVideo(frame(60, true, 2))
        assertEquals(listOf("V2k"), queue.drain().describe())
        assertEquals(1, queue.droppedVideoFrames)
    }

    @Test
    fun audioOverflowDropsVideoBeforeAudio() {
        val queue = FrameQueue(100)
        queue.open()
        queue.addVideo(frame(90, true, 1))
        queue.addAudio(frame(20, pts = 2))
        queue.addVideo(frame(10, false, 3))
        assertEquals(listOf("A2"), queue.drain().describe())
        assertEquals(2, queue.droppedVideoFrames)
        assertEquals(0, queue.droppedAudioFrames)
    }

    @Test
    fun audioIsDroppedLastWhenNothingElseCanBeDropped() {
        val queue = FrameQueue(30)
        queue.open()
        queue.addAudio(frame(20, pts = 1))
        queue.addAudio(frame(20, pts = 2))
        assertEquals(listOf("A1"), queue.drain().describe())
        assertEquals(1, queue.droppedAudioFrames)
    }

    @Test
    fun closeDiscardsEverythingAndRefusesMore() {
        val queue = FrameQueue(1000)
        queue.open()
        queue.addVideo(frame(10, true))
        queue.close()
        assertNull(queue.poll())
        assertTrue(!queue.addVideo(frame(10, true)))
        assertTrue(!queue.addFormat(AudioFormat(48000, 2, ByteArray(2))))
        assertEquals(0L, queue.queuedBytes)
    }
}
