package com.opirl.app.engine.chat.twitch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val MAX_MESSAGE_CODE_POINTS = 500

internal fun truncateMessage(text: String): String {
    if (text.length <= MAX_MESSAGE_CODE_POINTS || text.codePointCount(0, text.length) <= MAX_MESSAGE_CODE_POINTS) {
        return text
    }
    return text.substring(0, text.offsetByCodePoints(0, MAX_MESSAGE_CODE_POINTS))
}

internal fun twitchSendBody(broadcasterId: String, senderId: String, message: String): String =
    buildJsonObject {
        put("broadcaster_id", broadcasterId)
        put("sender_id", senderId)
        put("message", message)
    }.toString()

internal fun twitchSendError(code: Int, body: String): String? {
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
    if (code in 200..299) {
        val entry = (root?.get("data") as? JsonArray)?.firstOrNull() as? JsonObject
        if (entry == null || (entry["is_sent"] as? JsonPrimitive)?.booleanOrNull != false) {
            return null
        }
        val reason = ((entry["drop_reason"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        return if (reason.isNullOrBlank()) "Twitch did not send the message" else reason
    }
    if (code == 401) {
        return "Twitch login expired"
    }
    val message = (root?.get("message") as? JsonPrimitive)?.contentOrNull
    return if (message.isNullOrBlank()) "Twitch error $code" else "Twitch: $message"
}
