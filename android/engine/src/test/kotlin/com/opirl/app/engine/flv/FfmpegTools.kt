package com.opirl.app.engine.flv

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ProcessResult(val exitCode: Int, val output: String)

object FfmpegTools {
    val available: Boolean by lazy {
        runCatching {
            run(listOf("ffmpeg", "-version"), 10).exitCode == 0 && run(listOf("ffprobe", "-version"), 10).exitCode == 0
        }.getOrDefault(false)
    }

    private val workDirectory: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "opirl-engine-test-${ProcessHandle.current().pid()}").apply {
            mkdirs()
            if (System.getenv("KEEP_TEST_FILES") == null) deleteOnExit()
        }
    }

    fun file(name: String): File = File(workDirectory, name).apply {
        if (System.getenv("KEEP_TEST_FILES") == null) deleteOnExit()
    }

    fun run(command: List<String>, timeoutSeconds: Long): ProcessResult {
        val log = File.createTempFile("process", ".log", workDirectory)
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                return ProcessResult(-1, log.readText() + "\n[timeout]")
            }
            return ProcessResult(process.exitValue(), log.readText())
        } finally {
            log.delete()
        }
    }

    fun start(command: List<String>, log: File): Process =
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    fun probeStreams(file: File, countFrames: Boolean = true): List<Map<String, String>> {
        val command = mutableListOf("ffprobe", "-v", "error", "-show_streams", "-of", "flat")
        if (countFrames) {
            command.add("-count_frames")
        }
        command.add(file.path)
        val result = run(command, 60)
        check(result.exitCode == 0) { "ffprobe failed: ${result.output}" }
        val streams = sortedMapOf<Int, MutableMap<String, String>>()
        val line = Regex("""streams\.stream\.(\d+)\.([A-Za-z_0-9.]+)=(.*)""")
        for (text in result.output.lineSequence()) {
            val match = line.matchEntire(text.trim()) ?: continue
            val (index, key, value) = match.destructured
            streams.getOrPut(index.toInt()) { mutableMapOf() }[key] = value.trim('"')
        }
        return streams.values.toList()
    }

    fun probePackets(file: File, streamSelector: String = "0"): List<Map<String, String>> {
        val result = run(
            listOf(
                "ffprobe", "-v", "error", "-select_streams", streamSelector, "-show_packets",
                "-show_entries", "packet=pts,dts,size,flags", "-of", "csv=p=0:s=|", file.path,
            ),
            60,
        )
        check(result.exitCode == 0) { "ffprobe failed: ${result.output}" }
        return result.output.lineSequence().filter { it.isNotBlank() }.map {
            val parts = it.split('|')
            mapOf("pts" to parts[0], "dts" to parts[1], "size" to parts[2], "flags" to parts[3])
        }.toList()
    }
}
