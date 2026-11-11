package com.opirl.app.engine.srt

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.PortUnreachableException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ToolResult(val exitCode: Int, val output: String)

fun runTool(timeoutSeconds: Long, vararg command: String): ToolResult {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("${command.first()} did not finish in $timeoutSeconds s: $output")
    }
    return ToolResult(process.exitValue(), output)
}

fun toolWorks(vararg command: String): Boolean = try {
    runTool(10, *command).exitCode == 0
} catch (_: Exception) {
    false
}

fun ffmpegWithSrt(): Boolean =
    toolWorks("ffmpeg", "-version") && toolWorks("ffprobe", "-version") &&
        runTool(10, "ffmpeg", "-hide_banner", "-protocols").output.lines().any { it.trim() == "srt" }

fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

fun scratchDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()

fun generateTransportStream(file: File, seconds: Int = 6) {
    val result = runTool(
        60,
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
        "-t", seconds.toString(),
        "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "30",
        "-b:v", "800k", "-maxrate", "800k", "-bufsize", "400k", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "96k",
        "-f", "mpegts", file.absolutePath,
    )
    check(result.exitCode == 0) { "ffmpeg failed: ${result.output}" }
}

fun countVideoFrames(file: File): Int {
    val result = runTool(
        60,
        "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", file.absolutePath,
    )
    return firstInteger(result.output)
}

fun countAudioPackets(file: File): Int {
    val result = runTool(
        60,
        "ffprobe", "-v", "error", "-count_packets", "-select_streams", "a:0",
        "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", file.absolutePath,
    )
    return firstInteger(result.output)
}

private fun firstInteger(output: String): Int =
    output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() } ?: error("ffprobe failed: $output")

fun decodeErrors(file: File): String =
    runTool(60, "ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-").output.trim()

fun startSrtListener(port: Int, output: File, vararg extraInput: String): Process =
    ProcessBuilder(
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        *extraInput,
        "-i", "srt://127.0.0.1:$port?mode=listener",
        "-c", "copy", "-f", "mpegts", output.absolutePath,
    ).redirectErrorStream(true).start()

fun awaitSrtListener(port: Int, timeoutMs: Long = 15_000) {
    val probe = handshakeResponse(4, SRT_HANDSHAKE_INDUCTION, 0x1234, 0)
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    DatagramSocket().use { socket ->
        socket.soTimeout = 200
        socket.connect(InetAddress.getByName("127.0.0.1"), port)
        val reply = DatagramPacket(ByteArray(2048), 2048)
        while (System.nanoTime() < deadline) {
            try {
                socket.send(DatagramPacket(probe, probe.size))
                socket.receive(reply)
                if (reply.length >= SRT_HEADER_SIZE) {
                    return
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: PortUnreachableException) {
                Thread.sleep(100)
            }
        }
    }
    error("SRT listener on port $port did not answer")
}
