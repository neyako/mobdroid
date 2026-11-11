package com.opirl.app.engine.srt

import java.io.ByteArrayOutputStream

internal class SrtUrl(
    val bonded: Boolean,
    val host: String,
    val port: Int,
    val streamId: String?,
    val latencyMs: Int,
) {
    companion object {
        fun parse(url: String): SrtUrl {
            val bonded = when {
                url.startsWith("srtla://", ignoreCase = true) -> true
                url.startsWith("srt://", ignoreCase = true) -> false
                else -> throw IllegalArgumentException("SRT URL must start with srt:// or srtla://")
            }
            val rest = url.substring(url.indexOf("://") + 3)
            val queryStart = rest.indexOf('?')
            val authority = (if (queryStart >= 0) rest.substring(0, queryStart) else rest).substringBefore('/')
            val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""
            val (host, port) = parseAuthority(authority)
            val parameters = parseQuery(query)
            if (!parameters["passphrase"].isNullOrEmpty()) {
                throw IllegalArgumentException("SRT encryption (passphrase) is not supported")
            }
            val mode = parameters["mode"]
            if (mode != null && !mode.equals("caller", ignoreCase = true)) {
                throw IllegalArgumentException("Only SRT caller mode is supported")
            }
            val latencyMs = parameters["latency"]?.let {
                it.toIntOrNull()?.takeIf { value -> value in 0..65535 }
                    ?: throw IllegalArgumentException("SRT latency must be between 0 and 65535 ms")
            } ?: DEFAULT_SRT_LATENCY_MS
            return SrtUrl(bonded, host, port, parameters["streamid"]?.takeIf { it.isNotEmpty() }, latencyMs)
        }

        private fun parseAuthority(authority: String): Pair<String, Int> {
            val separator = if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                if (end < 0) -1 else authority.indexOf(':', end)
            } else {
                authority.lastIndexOf(':')
            }
            if (separator < 0) {
                throw IllegalArgumentException("SRT URL needs a host and a port")
            }
            val host = authority.substring(0, separator).removePrefix("[").removeSuffix("]")
            val port = authority.substring(separator + 1).toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) {
                throw IllegalArgumentException("SRT URL needs a host and a port")
            }
            return host to port
        }

        private fun parseQuery(query: String): Map<String, String> {
            val parameters = HashMap<String, String>()
            for (item in query.split('&')) {
                if (item.isEmpty()) {
                    continue
                }
                val separator = item.indexOf('=')
                val name = percentDecode(if (separator < 0) item else item.substring(0, separator))
                val value = if (separator < 0) "" else percentDecode(item.substring(separator + 1))
                parameters[name] = value
            }
            return parameters
        }

        private fun percentDecode(value: String): String {
            if (!value.contains('%')) {
                return value
            }
            val bytes = ByteArrayOutputStream(value.length)
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val high = if (codePoint == '%'.code && index + 2 < value.length) hexValue(value[index + 1]) else -1
                val low = if (high >= 0) hexValue(value[index + 2]) else -1
                if (low >= 0) {
                    bytes.write(high * 16 + low)
                    index += 3
                } else {
                    val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                    bytes.write(encoded, 0, encoded.size)
                    index += Character.charCount(codePoint)
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }

        private fun hexValue(character: Char): Int = Character.digit(character, 16)
    }
}
