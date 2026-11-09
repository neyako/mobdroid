package app.mobdroid.engine.rtmp

import app.mobdroid.engine.flv.ByteWriter
import java.io.EOFException
import java.io.InputStream

internal const val MSG_SET_CHUNK_SIZE = 1
internal const val MSG_ABORT = 2
internal const val MSG_ACK = 3
internal const val MSG_USER_CONTROL = 4
internal const val MSG_WINDOW_ACK_SIZE = 5
internal const val MSG_SET_PEER_BANDWIDTH = 6
internal const val MSG_AUDIO = 8
internal const val MSG_VIDEO = 9
internal const val MSG_COMMAND_AMF3 = 17
internal const val MSG_DATA_AMF0 = 18
internal const val MSG_COMMAND_AMF0 = 20

internal const val CSID_CONTROL = 2
internal const val CSID_COMMAND = 3
internal const val CSID_AUDIO = 4
internal const val CSID_DATA = 5
internal const val CSID_VIDEO = 6

private const val EXTENDED_TIMESTAMP = 0xFFFFFF
private const val DEFAULT_CHUNK_SIZE = 128
private const val MAX_INCOMING_MESSAGE = 8 * 1024 * 1024
private val EMPTY = ByteArray(0)

internal class RtmpChunkWriter {
    var chunkSize = DEFAULT_CHUNK_SIZE

    private class Previous {
        var valid = false
        var timestamp = 0L
        var length = 0
        var type = 0
        var streamId = 0
    }

    private val previous = Array(8) { Previous() }

    fun write(
        out: ByteWriter,
        csid: Int,
        type: Int,
        streamId: Int,
        timestampMs: Long,
        body: ByteArray,
        offset: Int,
        length: Int,
        compress: Boolean,
    ) {
        val timestamp = timestampMs and 0xFFFFFFFFL
        val state = previous[csid]
        val extended = timestamp >= EXTENDED_TIMESTAMP
        val format =
            when {
                !compress || !state.valid || state.streamId != streamId || timestamp < state.timestamp || extended -> 0
                state.type == type && state.length == length -> 2
                else -> 1
            }
        out.u8((format shl 6) or csid)
        val delta = timestamp - state.timestamp
        out.u24(if (format == 0) minOf(timestamp, EXTENDED_TIMESTAMP.toLong()).toInt() else delta.toInt())
        if (format <= 1) {
            out.u24(length)
            out.u8(type)
        }
        if (format == 0) {
            out.u32le(streamId)
            if (extended) {
                out.u32(timestamp.toInt())
            }
        }
        var sent = minOf(length, chunkSize)
        out.put(body, offset, sent)
        while (sent < length) {
            out.u8(0xC0 or csid)
            if (extended) {
                out.u32(timestamp.toInt())
            }
            val count = minOf(length - sent, chunkSize)
            out.put(body, offset + sent, count)
            sent += count
        }
        state.valid = true
        state.timestamp = timestamp
        state.length = length
        state.type = type
        state.streamId = streamId
    }
}

internal class RtmpMessage(val type: Int, val streamId: Int, val timestamp: Long, val body: ByteArray)

internal class RtmpChunkReader(private val input: InputStream) {
    var chunkSize = DEFAULT_CHUNK_SIZE
    var bytesRead = 0L
        private set

    private class Stream {
        var timestamp = 0L
        var delta = 0L
        var length = 0
        var type = 0
        var streamId = 0
        var extended = false
        var body = EMPTY
        var received = 0
    }

    private val streams = HashMap<Int, Stream>()

    fun discard(csid: Int) {
        streams[csid]?.received = 0
    }

    fun read(): RtmpMessage {
        while (true) {
            val first = readByte()
            val format = first ushr 6
            var csid = first and 0x3F
            if (csid == 0) {
                csid = 64 + readByte()
            } else if (csid == 1) {
                csid = 64 + readByte() + (readByte() shl 8)
            }
            val stream = streams.getOrPut(csid) { Stream() }
            if (format != 3) {
                stream.received = 0
            }
            var field = 0
            when (format) {
                0 -> {
                    field = readInt(3)
                    stream.length = readInt(3)
                    stream.type = readByte()
                    stream.streamId = readInt(4, littleEndian = true)
                }
                1 -> {
                    field = readInt(3)
                    stream.length = readInt(3)
                    stream.type = readByte()
                }
                2 -> field = readInt(3)
            }
            if (format != 3) {
                stream.extended = field == EXTENDED_TIMESTAMP
            }
            var value = field.toLong()
            if (stream.extended) {
                value = readInt(4).toLong() and 0xFFFFFFFFL
            }
            if (stream.received == 0) {
                require(stream.length in 0..MAX_INCOMING_MESSAGE) { "RTMP message too large" }
                if (stream.body.size != stream.length) {
                    stream.body = ByteArray(stream.length)
                }
                when (format) {
                    0 -> {
                        stream.timestamp = value
                        stream.delta = 0
                    }
                    3 -> stream.timestamp = (stream.timestamp + stream.delta) and 0xFFFFFFFFL
                    else -> {
                        stream.delta = value
                        stream.timestamp = (stream.timestamp + value) and 0xFFFFFFFFL
                    }
                }
            }
            val count = minOf(stream.length - stream.received, chunkSize)
            readFully(stream.body, stream.received, count)
            stream.received += count
            if (stream.received == stream.length) {
                stream.received = 0
                val body = stream.body
                stream.body = EMPTY
                return RtmpMessage(stream.type, stream.streamId, stream.timestamp, body)
            }
        }
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) {
            throw EOFException("Connection closed by server")
        }
        bytesRead++
        return value
    }

    private fun readInt(size: Int, littleEndian: Boolean = false): Int {
        var value = 0
        for (i in 0 until size) {
            val byte = readByte()
            value = if (littleEndian) value or (byte shl (8 * i)) else (value shl 8) or byte
        }
        return value
    }

    private fun readFully(target: ByteArray, offset: Int, count: Int) {
        var done = 0
        while (done < count) {
            val n = input.read(target, offset + done, count - done)
            if (n < 0) {
                throw EOFException("Connection closed by server")
            }
            done += n
        }
        bytesRead += count
    }
}
