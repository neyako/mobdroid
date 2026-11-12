package com.opirl.app.engine.chat.kick

import com.opirl.app.engine.alerts.AlertEvent
import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private val emoteRegex = Regex("""\[emote:(\d+):([^\]]+)\]""")

internal sealed interface KickEvent {
    data class Message(val message: ChatMessage) : KickEvent

    data class MessageDeleted(val messageId: String) : KickEvent

    data class UserBanned(val userId: String) : KickEvent

    data class Alert(val event: AlertEvent) : KickEvent

    data class Subscribed(val channel: String) : KickEvent

    data object ConnectionEstablished : KickEvent

    data object Ping : KickEvent
}

internal fun parseKickFrame(text: String, nowMs: Long): KickEvent? {
    val frame = parseObject(text) ?: return null
    return when (frame.string("event")) {
        "pusher:ping" -> KickEvent.Ping
        "pusher:connection_established" -> KickEvent.ConnectionEstablished
        "pusher_internal:subscription_succeeded" -> frame.string("channel")?.let { KickEvent.Subscribed(it) }
        "App\\Events\\ChatMessageEvent" -> frame.data()?.let { parseMessage(it, nowMs) }
        "App\\Events\\MessageDeletedEvent" ->
            frame.data()?.obj("message")?.string("id")?.let { KickEvent.MessageDeleted(it) }
        "App\\Events\\UserBannedEvent" ->
            frame.data()?.obj("user")?.string("id")?.let { KickEvent.UserBanned(it) }
        "App\\Events\\SubscriptionEvent" -> frame.data()?.let(::parseSubscription)
        "GiftedSubscriptionsEvent" -> frame.data()?.let(::parseGiftedSubscriptions)
        "RewardRedeemedEvent" -> frame.data()?.let(::parseReward)
        "App\\Events\\StreamHostEvent" -> frame.data()?.let(::parseHost)
        "KicksGifted" -> frame.data()?.let(::parseKicks)
        else -> null
    }
}

internal fun parseKickEmotes(content: String): List<ChatSegment> {
    if (content.isEmpty()) {
        return emptyList()
    }
    var match = emoteRegex.find(content) ?: return listOf(ChatSegment.Text(content))
    val segments = ArrayList<ChatSegment>()
    var start = 0
    while (true) {
        if (match.range.first > start) {
            segments += ChatSegment.Text(content.substring(start, match.range.first))
        }
        segments += ChatSegment.Emote(
            name = match.groupValues[2],
            url = "https://files.kick.com/emotes/${match.groupValues[1]}/fullsize",
        )
        start = match.range.last + 1
        match = match.next() ?: break
    }
    if (start < content.length) {
        segments += ChatSegment.Text(content.substring(start))
    }
    return segments
}

internal fun parseKickColor(text: String): Int? {
    val hex = text.removePrefix("#")
    if (hex.length != 6 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return null
    }
    return hex.toInt(16)
}

internal fun parseKickChatroomId(body: String): Long? =
    (parseObject(body)?.obj("chatroom")?.get("id") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

internal fun parseKickChannelId(body: String): Long? =
    (parseObject(body)?.obj("chatroom")?.get("channel_id") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

internal fun parseKickUserId(body: String): Long? = (parseObject(body)?.get("user_id") as? JsonPrimitive)?.longOrNull

internal fun parseKickErrorMessage(body: String): String? =
    parseObject(body)?.string("message")?.takeIf { it.isNotBlank() }

private fun parseSubscription(data: JsonObject): KickEvent? {
    val user = data.string("username") ?: return null
    return KickEvent.Alert(AlertEvent.KickSubscription(user, data.int("months") ?: return null))
}

private fun parseGiftedSubscriptions(data: JsonObject): KickEvent? {
    val gifter = data.string("gifter_username") ?: return null
    val gifted = (data["gifted_usernames"] as? JsonArray) ?: return null
    return KickEvent.Alert(AlertEvent.KickGiftedSubscriptions(gifter, gifted.size, data.int("gifter_total") ?: return null))
}

private fun parseReward(data: JsonObject): KickEvent? {
    val user = data.string("username") ?: return null
    val title = data.string("reward_title") ?: return null
    return KickEvent.Alert(AlertEvent.KickReward(user, title, data.string("user_input").orEmpty()))
}

private fun parseHost(data: JsonObject): KickEvent? {
    val user = data.string("host_username") ?: return null
    return KickEvent.Alert(AlertEvent.KickHost(user, data.int("number_viewers") ?: return null))
}

private fun parseKicks(data: JsonObject): KickEvent? {
    val user = data.obj("sender")?.string("username") ?: return null
    val gift = data.obj("gift") ?: return null
    return KickEvent.Alert(AlertEvent.KickKicks(user, gift.string("name") ?: return null, gift.int("amount") ?: return null))
}

private fun parseMessage(data: JsonObject, nowMs: Long): KickEvent? {
    val content = data.string("content") ?: return null
    val sender = data.obj("sender") ?: return null
    val user = sender.string("username") ?: return null
    val identity = sender.obj("identity")
    val badges = (identity?.get("badges") as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.string("type") }
        .orEmpty()
    return KickEvent.Message(
        ChatMessage(
            platform = ChatPlatform.KICK,
            id = data.string("id"),
            userId = sender.string("id"),
            user = user,
            userColor = identity?.string("color")?.let(::parseKickColor),
            segments = parseKickEmotes(content),
            timestampMs = data.string("created_at")?.let(::parseKickTime) ?: nowMs,
            isBroadcaster = "broadcaster" in badges,
            isModerator = "moderator" in badges,
            isSubscriber = "subscriber" in badges,
        ),
    )
}

private fun parseKickTime(text: String): Long? =
    try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

private fun parseObject(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

private fun JsonObject.data(): JsonObject? =
    when (val data = this["data"]) {
        is JsonObject -> data
        is JsonPrimitive -> if (data.isString) parseObject(data.content) else null
        else -> null
    }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
