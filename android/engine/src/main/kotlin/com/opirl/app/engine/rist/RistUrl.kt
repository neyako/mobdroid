package com.opirl.app.engine.rist

import java.net.URLDecoder

internal enum class RistProfile { Simple, Main }

internal const val RIST_DEFAULT_BUFFER_MS = 1000
internal const val RIST_DEFAULT_VIRTUAL_DESTINATION_PORT = 1968
internal const val RIST_DEFAULT_SESSION_TIMEOUT_MS = 2000
internal const val RIST_DEFAULT_BANDWIDTH_KBPS = 100_000
private const val MAXIMUM_BUFFER_MS = 30_000
private const val MINIMUM_SESSION_TIMEOUT_MS = 400
private const val MAXIMUM_CNAME_BYTES = 127

internal class RistUrl(
    val host: String,
    val port: Int,
    val profile: RistProfile,
    val bufferMs: Int,
    val cname: String?,
    val virtualDestinationPort: Int,
    val sessionTimeoutMs: Int,
    val bandwidthKbps: Int,
) {
    companion object {
        fun parse(url: String): RistUrl {
            if (!url.startsWith("rist://", ignoreCase = true)) {
                throw IllegalArgumentException("RIST URL must start with rist://")
            }
            val rest = url.substring("rist://".length)
            val queryStart = rest.indexOf('?')
            val authority = (if (queryStart >= 0) rest.substring(0, queryStart) else rest).substringBefore('/')
            val parameters = parseQuery(if (queryStart >= 0) rest.substring(queryStart + 1) else "")
            val (host, port) = parseAuthority(authority)
            rejectUnsupported(parameters)
            val profile = parseProfile(parameters["profile"])
            if (profile == RistProfile.Simple && port % 2 != 0) {
                throw IllegalArgumentException("RIST simple profile needs an even port")
            }
            val virtualPort = (parameters["stream-id"] ?: parameters["virt-dst-port"])?.let {
                it.toIntOrNull()?.takeIf { value -> value in 2..65534 && value % 2 == 0 }
                    ?: throw IllegalArgumentException("RIST virtual destination port must be even, from 2 to 65534")
            } ?: RIST_DEFAULT_VIRTUAL_DESTINATION_PORT
            return RistUrl(
                host,
                port,
                profile,
                parameters["buffer"]?.let {
                    it.toIntOrNull()?.takeIf { value -> value in 1..MAXIMUM_BUFFER_MS }
                        ?: throw IllegalArgumentException("RIST buffer must be between 1 and $MAXIMUM_BUFFER_MS ms")
                } ?: RIST_DEFAULT_BUFFER_MS,
                parameters["cname"]?.takeIf { it.isNotEmpty() }?.also {
                    if (it.toByteArray(Charsets.UTF_8).size > MAXIMUM_CNAME_BYTES) {
                        throw IllegalArgumentException("RIST cname is longer than $MAXIMUM_CNAME_BYTES bytes")
                    }
                },
                virtualPort,
                parameters["session-timeout"]?.let {
                    it.toIntOrNull()?.takeIf { value -> value > 0 }
                        ?: throw IllegalArgumentException("RIST session timeout must be a positive number of ms")
                }?.coerceAtLeast(MINIMUM_SESSION_TIMEOUT_MS) ?: RIST_DEFAULT_SESSION_TIMEOUT_MS,
                parameters["bandwidth"]?.let {
                    it.toIntOrNull()?.takeIf { value -> value > 0 }
                        ?: throw IllegalArgumentException("RIST bandwidth must be a positive number of kbps")
                } ?: RIST_DEFAULT_BANDWIDTH_KBPS,
            )
        }

        private fun parseProfile(value: String?): RistProfile = when (value?.lowercase()) {
            null, "1", "main" -> RistProfile.Main
            "0", "simple" -> RistProfile.Simple
            "2", "advanced" -> throw IllegalArgumentException("RIST advanced profile is not supported")
            else -> throw IllegalArgumentException("RIST profile must be simple or main")
        }

        private fun rejectUnsupported(parameters: Map<String, String>) {
            val encrypted = !parameters["secret"].isNullOrEmpty() ||
                listOf("aes-type", "encryption").any { name -> parameters[name].let { !it.isNullOrEmpty() && it != "0" } }
            if (encrypted) {
                throw IllegalArgumentException("RIST encryption (secret) is not supported")
            }
            if (!parameters["username"].isNullOrEmpty() || !parameters["password"].isNullOrEmpty()) {
                throw IllegalArgumentException("RIST authentication (username) is not supported")
            }
            if (parameters["compression"].let { !it.isNullOrEmpty() && it != "0" }) {
                throw IllegalArgumentException("RIST compression is not supported")
            }
        }

        private fun parseAuthority(authority: String): Pair<String, Int> {
            val separator = if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                if (end < 0) -1 else authority.indexOf(':', end)
            } else {
                authority.lastIndexOf(':')
            }
            if (separator < 0) {
                throw IllegalArgumentException("RIST URL needs a host and a port")
            }
            val host = authority.substring(0, separator).removePrefix("[").removeSuffix("]")
            val port = authority.substring(separator + 1).toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) {
                throw IllegalArgumentException("RIST URL needs a host and a port")
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
                val name = decode(if (separator < 0) item else item.substring(0, separator))
                parameters[name] = if (separator < 0) "" else decode(item.substring(separator + 1))
            }
            return parameters
        }

        private fun decode(value: String): String =
            if (value.contains('%')) URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8) else value
    }
}
