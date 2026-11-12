package com.opirl.app.engine.whip

import com.opirl.app.engine.flv.FfmpegTools
import com.opirl.app.engine.flv.GeneratedAudio
import com.opirl.app.engine.flv.GeneratedVideo
import com.opirl.app.engine.media.AudioCodec
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.net.PublisherListener
import com.opirl.app.engine.srt.freeUdpPort
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class WhipListener : PublisherListener {
    val connected = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val connects = AtomicInteger()
    val disconnects = CopyOnWriteArrayList<String>()

    override fun onConnected() {
        connects.incrementAndGet()
        connected.countDown()
    }

    override fun onDisconnected(reason: String) {
        disconnects.add(reason)
        disconnected.countDown()
    }
}

fun whipThreads(): List<Thread> =
    Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("whip-") && it.name != "whip-test-feed" }

fun describeWhipThreads(): String =
    whipThreads().joinToString("\n") { thread -> thread.name + ": " + thread.stackTrace.take(6).joinToString(" < ") }

fun oggPackets(bytes: ByteArray): List<ByteArray> {
    val packets = ArrayList<ByteArray>()
    var pending = ByteArray(0)
    var at = 0
    while (at + 27 <= bytes.size) {
        check(bytes[at] == 'O'.code.toByte() && bytes[at + 1] == 'g'.code.toByte()) { "not an Ogg page at $at" }
        val segments = bytes[at + 26].toInt() and 0xFF
        var data = at + 27 + segments
        for (index in 0 until segments) {
            val size = bytes[at + 27 + index].toInt() and 0xFF
            pending += bytes.copyOfRange(data, data + size)
            data += size
            if (size < 255) {
                packets.add(pending)
                pending = ByteArray(0)
            }
        }
        at = data
    }
    return packets
}

private var cachedOpus: GeneratedAudio? = null

@Synchronized
fun opusAudio(): GeneratedAudio = cachedOpus ?: run {
    val output = FfmpegTools.file("opus.ogg")
    val result = FfmpegTools.run(
        listOf(
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "2", "-ac", "2", "-c:a", "libopus", "-b:a", "96k", "-frame_duration", "20", "-f", "ogg", output.path,
        ),
        60,
    )
    check(result.exitCode == 0) { "ffmpeg failed: ${result.output}" }
    val packets = oggPackets(output.readBytes()).drop(2)
    GeneratedAudio(
        AudioFormat(48_000, 2, ByteArray(0), AudioCodec.OPUS),
        packets.mapIndexed { index, packet -> EncodedFrame(packet, index * 20_000L, true) },
    ).also { cachedOpus = it }
}

class LoopingFeed(private val sink: MediaSink, private val video: GeneratedVideo, private val audio: GeneratedAudio?) {
    @Volatile private var running = true
    private val thread = Thread(::run, "whip-test-feed").apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.join(2000)
    }

    private fun run() {
        sink.setVideoFormat(video.format)
        audio?.let { sink.setAudioFormat(it.format) }
        val videoStep = video.frames[1].ptsUs - video.frames[0].ptsUs
        val audioStep = 20_000L
        val start = System.nanoTime()
        var v = 0L
        var a = 0L
        while (running) {
            val videoPts = v * videoStep
            val audioPts = a * audioStep
            val takeVideo = audio == null || videoPts <= audioPts
            val pts = if (takeVideo) videoPts else audioPts
            val wait = start + pts * 1000 - System.nanoTime()
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            if (takeVideo) {
                val frame = video.frames[(v % video.frames.size).toInt()]
                sink.writeVideo(EncodedFrame(frame.data, 1_000_000_000L + pts, frame.isKeyFrame))
                v++
            } else {
                val frame = audio!!.frames[(a % audio.frames.size).toInt()]
                sink.writeAudio(EncodedFrame(frame.data, 1_000_000_000L + pts, true))
                a++
            }
        }
    }
}

class MediaMtx(private val binary: File) : AutoCloseable {
    private val directory = java.nio.file.Files.createTempDirectory("mediamtx").toFile()
    val httpPort = FfmpegTools.freePort()
    val rtspPort = FfmpegTools.freePort()
    val udpPort = freeUdpPort()
    val log = File(directory, "mediamtx.log")
    private val process: Process

    init {
        val config = File(directory, "mediamtx.yml")
        config.writeText(
            """
            logLevel: debug
            rtsp: yes
            rtspAddress: 127.0.0.1:$rtspPort
            rtspTransports: [tcp]
            rtmp: no
            hls: no
            srt: no
            moq: no
            webrtc: yes
            webrtcAddress: 127.0.0.1:$httpPort
            webrtcLocalUDPAddress: 127.0.0.1:$udpPort
            webrtcIPsFromInterfaces: no
            webrtcAdditionalHosts: [127.0.0.1]
            paths:
              all_others:
            """.trimIndent(),
        )
        process = ProcessBuilder(binary.path, config.path).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!log.readText().contains("[WebRTC] started")) {
            check(process.isAlive && System.nanoTime() < deadline) { "MediaMTX did not start: ${log.readText()}" }
            Thread.sleep(50)
        }
    }

    fun whipUrl(path: String) = "whip://127.0.0.1:$httpPort/$path/whip"

    fun record(path: String, seconds: Int, output: File): Process? {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val reader = FfmpegTools.start(
                listOf(
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-rtsp_transport", "tcp",
                    "-i", "rtsp://127.0.0.1:$rtspPort/$path", "-t", "$seconds", "-c", "copy", output.path,
                ),
                File(directory, "reader.log"),
            )
            Thread.sleep(700)
            if (reader.isAlive) {
                return reader
            }
            Thread.sleep(300)
        }
        return null
    }

    fun kill() {
        process.destroyForcibly().waitFor()
    }

    override fun close() {
        kill()
        directory.deleteRecursively()
    }

    companion object {
        fun binary(): File? = System.getenv("MEDIAMTX")?.let(::File)?.takeIf { it.canExecute() }
    }
}
