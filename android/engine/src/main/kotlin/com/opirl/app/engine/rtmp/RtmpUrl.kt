package com.opirl.app.engine.rtmp

internal class RtmpUrl(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val app: String,
    val tcUrl: String,
    val streamKey: String,
) {
    companion object {
        private val pattern = Regex("^(rtmps?)://([^/?#]*)(.*)$", RegexOption.IGNORE_CASE)

        fun parse(url: String, streamKey: String): RtmpUrl {
            val match = requireNotNull(pattern.matchEntire(url.trim())) { "Malformed RTMP URL" }
            val secure = match.groupValues[1].equals("rtmps", ignoreCase = true)
            val authority = match.groupValues[2].substringAfterLast('@')
            var rest = match.groupValues[3]
            var key = streamKey
            if (key.isEmpty()) {
                val split = rest.lastIndexOf('/')
                require(split >= 0 && split < rest.length - 1) { "RTMP stream key missing" }
                key = rest.substring(split + 1)
                rest = rest.substring(0, split)
            }
            val host: String
            var port = if (secure) 443 else 1935
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                require(close > 0) { "Malformed RTMP URL" }
                host = authority.substring(1, close)
                authority.substring(close + 1).removePrefix(":").takeIf { it.isNotEmpty() }?.let { port = parsePort(it) }
            } else {
                host = authority.substringBeforeLast(':')
                if (authority.contains(':')) {
                    port = parsePort(authority.substringAfterLast(':'))
                }
            }
            require(host.isNotEmpty()) { "Malformed RTMP URL" }
            val query = rest.indexOf('?')
            val path = (if (query >= 0) rest.substring(0, query) else rest).trimEnd('/')
            val queryPart = if (query >= 0) rest.substring(query) else ""
            val scheme = if (secure) "rtmps" else "rtmp"
            return RtmpUrl(
                secure,
                host,
                port,
                path.trimStart('/') + queryPart,
                "$scheme://$authority$path$queryPart",
                key,
            )
        }

        private fun parsePort(text: String): Int {
            val port = text.toIntOrNull()
            require(port != null && port in 1..65535) { "Malformed RTMP URL" }
            return port
        }
    }
}
