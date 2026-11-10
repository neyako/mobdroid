package app.mobdroid.engine.mp4

import app.mobdroid.engine.flv.Av1Config
import app.mobdroid.engine.flv.ByteWriter
import app.mobdroid.engine.flv.OBU_SEQUENCE_HEADER
import app.mobdroid.engine.flv.OBU_TEMPORAL_DELIMITER
import app.mobdroid.engine.flv.annexBToLengthPrefixed
import app.mobdroid.engine.flv.containsObuOfType
import app.mobdroid.engine.flv.forEachObu
import app.mobdroid.engine.flv.writeObuWithSizeField
import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.concurrent.withLock

class Mp4Summary(val durationMs: Long, val bytes: Long)

class Mp4Recorder(
    private val input: FileChannel,
    private val output: FileChannel,
    private val backlogLimitBytes: Int = 32 shl 20,
    private val onError: (String) -> Unit,
) : MediaSink {
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private var pendingVideo = ArrayList<EncodedFrame>()
    private var pendingAudio = ArrayList<EncodedFrame>()
    private var pendingBytes = 0
    private var stopping = false

    @Volatile
    private var videoFormat: VideoFormat? = null

    @Volatile
    private var audioFormat: AudioFormat? = null

    @Volatile
    var bytesWritten = 0L
        private set

    @Volatile
    private var summary: Mp4Summary? = null
    private val thread = Thread(::run, "Mp4Recorder")

    private val head = ByteWriter(4096)
    private val formatRecords = ByteWriter(256)
    private val videoRecords = ByteWriter(1024)
    private val audioRecords = ByteWriter(1024)
    private val videoData = ByteWriter(1 shl 20)
    private val audioData = ByteWriter(1 shl 15)
    private val crc = CRC32()
    private var position = 0L
    private var writtenVideoFormat: VideoFormat? = null
    private var writtenAudioFormat: AudioFormat? = null
    private var codec: VideoCodec? = null
    private var av1SequenceHeader: ByteArray? = null
    private var started = false
    private var startUs = 0L
    private var blockStartUs = NO_TIME
    private var blockHasVideo = false
    private val earlyAudio = ArrayDeque<EncodedFrame>()

    fun start() = thread.start()

    fun stop(): Mp4Summary? {
        lock.withLock {
            stopping = true
            wake.signal()
        }
        thread.join()
        return summary
    }

    override fun setVideoFormat(format: VideoFormat) {
        videoFormat = format
    }

    override fun setAudioFormat(format: AudioFormat) {
        audioFormat = format
    }

    override fun writeVideo(frame: EncodedFrame) = enqueue(frame, true)

    override fun writeAudio(frame: EncodedFrame) = enqueue(frame, false)

    private fun enqueue(frame: EncodedFrame, video: Boolean) {
        lock.withLock {
            if (stopping) return
            if (pendingBytes + frame.data.size <= backlogLimitBytes) {
                (if (video) pendingVideo else pendingAudio).add(frame)
                pendingBytes += frame.data.size
                wake.signal()
                return
            }
            stopping = true
            pendingVideo.clear()
            pendingAudio.clear()
            wake.signal()
        }
        onError("Storage is too slow")
    }

    private fun run() {
        var video = ArrayList<EncodedFrame>()
        var audio = ArrayList<EncodedFrame>()
        try {
            writeFileHeader(head)
            write(head)
            head.clear()
            var last = false
            while (!last) {
                lock.withLock {
                    while (pendingVideo.isEmpty() && pendingAudio.isEmpty() && !stopping) wake.await()
                    video = pendingVideo.also { pendingVideo = video }
                    audio = pendingAudio.also { pendingAudio = audio }
                    pendingBytes = 0
                    last = stopping
                }
                addFormats()
                var v = 0
                var a = 0
                while (v < video.size || a < audio.size) {
                    if (a >= audio.size || (v < video.size && video[v].ptsUs <= audio[a].ptsUs)) {
                        addVideo(video[v++])
                    } else {
                        addAudio(audio[a++])
                    }
                }
                video.clear()
                audio.clear()
                if (last || videoData.size + audioData.size >= MAX_BLOCK_BYTES) flushBlock()
            }
            summary = finishMp4(input, output)
        } catch (e: Exception) {
            lock.withLock {
                stopping = true
                pendingVideo.clear()
                pendingAudio.clear()
            }
            summary = runCatching { finishMp4(input, output) }.getOrNull()
            onError(e.message ?: e.toString())
        }
    }

    private fun addFormats() {
        val video = videoFormat
        if (video != null && video !== writtenVideoFormat) {
            writtenVideoFormat = video
            codec = video.codec
            av1SequenceHeader = if (video.codec == VideoCodec.AV1) Av1Config.from(video.config).sequenceHeaderObu else null
            writeVideoFormatRecord(formatRecords, video)
        }
        val audio = audioFormat
        if (audio != null && audio !== writtenAudioFormat) {
            writtenAudioFormat = audio
            writeAudioFormatRecord(formatRecords, audio)
        }
    }

    private fun addVideo(frame: EncodedFrame) {
        val codec = codec ?: return
        if (!started) {
            if (!frame.isKeyFrame) return
            started = true
            startUs = frame.ptsUs
            while (earlyAudio.isNotEmpty()) addAudio(earlyAudio.removeFirst())
        }
        if (blockHasVideo && frame.ptsUs - blockStartUs >= BLOCK_US) flushBlock()
        val before = videoData.size
        val data = frame.data
        if (codec == VideoCodec.AV1) {
            val sequenceHeader = av1SequenceHeader
            if (frame.isKeyFrame && sequenceHeader != null && !containsObuOfType(data, OBU_SEQUENCE_HEADER)) {
                videoData.put(sequenceHeader)
            }
            forEachObu(data, 0, data.size) { type, start, payloadStart, end ->
                if (type != OBU_TEMPORAL_DELIMITER) writeObuWithSizeField(videoData, data, start, payloadStart, end)
            }
        } else {
            annexBToLengthPrefixed(data, 0, data.size, videoData)
        }
        if (videoData.size == before) return
        writeVideoRecord(videoRecords, videoData.size - before, frame.ptsUs, frame.isKeyFrame)
        if (!blockHasVideo) {
            blockHasVideo = true
            blockStartUs = frame.ptsUs
        }
    }

    private fun addAudio(frame: EncodedFrame) {
        if (writtenAudioFormat == null) return
        if (!started) {
            if (earlyAudio.size == EARLY_AUDIO_FRAMES) earlyAudio.removeFirst()
            earlyAudio.addLast(frame)
            return
        }
        if (frame.ptsUs < startUs - AUDIO_LEAD_US) return
        if (!blockHasVideo && blockStartUs != NO_TIME && frame.ptsUs - blockStartUs >= BLOCK_US) flushBlock()
        audioData.put(frame.data)
        writeAudioRecord(audioRecords, frame.data.size, frame.ptsUs)
        if (blockStartUs == NO_TIME) blockStartUs = frame.ptsUs
    }

    private fun flushBlock() {
        val recordsLength = formatRecords.size + videoRecords.size + audioRecords.size
        if (recordsLength == 0) return
        crc.reset()
        crc.update(formatRecords.array, 0, formatRecords.size)
        crc.update(videoRecords.array, 0, videoRecords.size)
        crc.update(audioRecords.array, 0, audioRecords.size)
        head.u32(BLOCK_MAGIC)
        head.u32(recordsLength)
        head.u32(videoData.size + audioData.size)
        head.u32(crc.value.toInt())
        head.put(formatRecords.array, 0, formatRecords.size)
        head.put(videoRecords.array, 0, videoRecords.size)
        head.put(audioRecords.array, 0, audioRecords.size)
        write(head)
        write(videoData)
        write(audioData)
        head.clear()
        formatRecords.clear()
        videoRecords.clear()
        audioRecords.clear()
        videoData.clear()
        audioData.clear()
        blockStartUs = NO_TIME
        blockHasVideo = false
        bytesWritten = position
    }

    private fun write(data: ByteWriter) {
        val buffer = ByteBuffer.wrap(data.array, 0, data.size)
        while (buffer.hasRemaining()) position += output.write(buffer, position)
    }

    private companion object {
        const val BLOCK_US = 1_000_000L
        const val MAX_BLOCK_BYTES = 8 shl 20
        const val AUDIO_LEAD_US = 25_000L
        const val EARLY_AUDIO_FRAMES = 48
        const val NO_TIME = Long.MIN_VALUE
    }
}

fun finishMp4(input: FileChannel, output: FileChannel): Mp4Summary? {
    val length = input.size()
    val header = ByteBuffer.allocate(BLOCK_HEADER_SIZE)
    if (!read(input, header, 0, FILE_HEADER_SIZE.coerceAtMost(BLOCK_HEADER_SIZE))) return null
    if (header.getInt(0) != FILE_HEADER_SIZE || header.getInt(4) != BOX_FTYP) return null
    if (!read(input, header, FILE_HEADER_SIZE.toLong(), MDAT_HEADER_SIZE)) return null
    if (header.getInt(0) != 1 || header.getInt(4) != BOX_MDAT) return null
    val index = Mp4Index()
    val crc = CRC32()
    var records = ByteBuffer.allocate(1 shl 16)
    var position = (FILE_HEADER_SIZE + MDAT_HEADER_SIZE).toLong()
    while (read(input, header, position, BLOCK_HEADER_SIZE) && header.getInt(0) == BLOCK_MAGIC) {
        val recordsLength = header.getInt(4)
        val dataLength = header.getInt(8).toLong() and 0xFFFFFFFFL
        if (recordsLength !in 1..MAX_RECORDS_LENGTH) break
        val dataOffset = position + BLOCK_HEADER_SIZE + recordsLength
        if (dataOffset + dataLength > length) break
        if (records.capacity() < recordsLength) records = ByteBuffer.allocate(recordsLength)
        if (!read(input, records, position + BLOCK_HEADER_SIZE, recordsLength)) break
        crc.reset()
        crc.update(records.array(), 0, recordsLength)
        if (crc.value.toInt() != header.getInt(12)) break
        if (!index.addBlock(records.array(), recordsLength, dataOffset, dataLength)) break
        position = dataOffset + dataLength
    }
    if (index.video.count == 0 || index.videoFormat == null) return null
    output.truncate(position)
    val moov = buildMoov(index, System.currentTimeMillis())
    val buffer = ByteBuffer.wrap(moov.bytes.array, 0, moov.bytes.size)
    var at = position
    while (buffer.hasRemaining()) at += output.write(buffer, at)
    header.clear()
    header.putLong(position - FILE_HEADER_SIZE).flip()
    var sizeAt = FILE_HEADER_SIZE + 8L
    while (header.hasRemaining()) sizeAt += output.write(header, sizeAt)
    output.force(false)
    return Mp4Summary(moov.durationMs, at)
}

private fun read(channel: FileChannel, buffer: ByteBuffer, position: Long, length: Int): Boolean {
    buffer.clear().limit(length)
    var at = position
    while (buffer.hasRemaining()) {
        val n = channel.read(buffer, at)
        if (n < 0) return false
        at += n
    }
    return true
}
