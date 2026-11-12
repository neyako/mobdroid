package com.opirl.app.engine.moblink

import com.opirl.app.engine.json.case
import com.opirl.app.engine.json.int
import com.opirl.app.engine.json.obj
import com.opirl.app.engine.json.single
import com.opirl.app.engine.json.string
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val MOBLINK_SERVICE_TYPE = "_moblink._tcp"
const val MOBLINK_DEFAULT_PORT = 7777
const val REMOTE_API_VERSION = "0.1"

enum class MoblinkThermalState(val wire: String) {
    WHITE("white"),
    YELLOW("yellow"),
    RED("red"),
}

fun hashPassword(challenge: String, salt: String, password: String): String {
    val hash = { text: String ->
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
    return hash(hash(password + salt) + challenge)
}

private val random = SecureRandom()

fun randomToken(): String = Base64.getEncoder().encodeToString(ByteArray(24).also(random::nextBytes))

internal sealed interface MoblinkToRelay {
    class Hello(val challenge: String, val salt: String) : MoblinkToRelay

    class Identified(val result: String) : MoblinkToRelay

    class StartTunnel(val id: Int, val address: String, val port: Int) : MoblinkToRelay

    class Status(val id: Int) : MoblinkToRelay
}

internal sealed interface MoblinkToStreamer {
    class Identify(val id: String, val name: String, val authentication: String) : MoblinkToStreamer

    class Response(
        val id: Int,
        val result: String,
        val tunnelPort: Int? = null,
        val status: Boolean = false,
        val batteryPercentage: Int? = null,
        val thermalState: MoblinkThermalState? = null,
    ) : MoblinkToStreamer
}

internal fun encode(message: MoblinkToRelay): String = when (message) {
    is MoblinkToRelay.Hello -> case(
        "hello",
        buildJsonObject {
            put("apiVersion", REMOTE_API_VERSION)
            put(
                "authentication",
                buildJsonObject {
                    put("challenge", message.challenge)
                    put("salt", message.salt)
                },
            )
        },
    )
    is MoblinkToRelay.Identified -> case("identified", buildJsonObject { put("result", case(message.result)) })
    is MoblinkToRelay.StartTunnel -> request(
        message.id,
        case(
            "startTunnel",
            buildJsonObject {
                put("address", message.address)
                put("port", message.port)
            },
        ),
    )
    is MoblinkToRelay.Status -> request(message.id, case("status"))
}.toString()

private fun request(id: Int, data: JsonObject) = case(
    "request",
    buildJsonObject {
        put("id", id)
        put("data", data)
    },
)

internal fun decodeToRelay(text: String): MoblinkToRelay? {
    val (name, fields) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.single() ?: return null
    return when (name) {
        "hello" -> {
            val authentication = fields.obj("authentication") ?: return null
            MoblinkToRelay.Hello(authentication.string("challenge") ?: return null, authentication.string("salt") ?: return null)
        }
        "identified" -> MoblinkToRelay.Identified(fields.obj("result")?.single()?.first ?: return null)
        "request" -> {
            val id = fields.int("id") ?: return null
            val (type, data) = fields.obj("data")?.single() ?: return null
            when (type) {
                "startTunnel" -> MoblinkToRelay.StartTunnel(id, data.string("address") ?: return null, data.int("port") ?: return null)
                "status" -> MoblinkToRelay.Status(id)
                else -> null
            }
        }
        else -> null
    }
}

internal fun encode(message: MoblinkToStreamer): String = when (message) {
    is MoblinkToStreamer.Identify -> case(
        "identify",
        buildJsonObject {
            put("id", message.id)
            put("name", message.name)
            put("authentication", message.authentication)
        },
    )
    is MoblinkToStreamer.Response -> case(
        "response",
        buildJsonObject {
            put("id", message.id)
            put("result", case(message.result))
            if (message.tunnelPort != null) {
                put("data", case("startTunnel", buildJsonObject { put("port", message.tunnelPort) }))
            } else if (message.status) {
                put(
                    "data",
                    case(
                        "status",
                        buildJsonObject {
                            message.batteryPercentage?.let { put("batteryPercentage", it) }
                            message.thermalState?.let { put("thermalState", it.wire) }
                        },
                    ),
                )
            }
        },
    )
}.toString()

internal fun decodeToStreamer(text: String): MoblinkToStreamer? {
    val (name, fields) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.single() ?: return null
    return when (name) {
        "identify" -> MoblinkToStreamer.Identify(
            fields.string("id") ?: return null,
            fields.string("name") ?: return null,
            fields.string("authentication") ?: return null,
        )
        "response" -> {
            val data = fields.obj("data")?.single()
            MoblinkToStreamer.Response(
                id = fields.int("id") ?: return null,
                result = fields.obj("result")?.single()?.first ?: return null,
                tunnelPort = data?.takeIf { it.first == "startTunnel" }?.second?.int("port"),
                status = data?.first == "status",
                batteryPercentage = data?.second?.int("batteryPercentage"),
                thermalState = data?.second?.string("thermalState")?.let { wire ->
                    MoblinkThermalState.entries.firstOrNull { it.wire == wire }
                },
            )
        }
        else -> null
    }
}
