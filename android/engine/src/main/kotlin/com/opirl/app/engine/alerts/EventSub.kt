package com.opirl.app.engine.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val DEFAULT_KEEPALIVE_SECONDS = 10

const val TWITCH_SCOPE_FOLLOWERS = "moderator:read:followers"
const val TWITCH_SCOPE_CHAT = "user:read:chat"
const val TWITCH_SCOPE_REDEMPTIONS = "channel:read:redemptions"
const val TWITCH_SCOPE_BITS = "bits:read"

internal sealed interface EventSubMessage {
    data class Welcome(val sessionId: String, val keepaliveSeconds: Int) : EventSubMessage

    data object Keepalive : EventSubMessage

    data class Reconnect(val url: String) : EventSubMessage

    data class Notification(val id: String, val event: AlertEvent?) : EventSubMessage

    data class Revocation(val type: String, val status: String) : EventSubMessage
}

internal class Subscription(val type: String, val version: Int, val condition: JsonObject, val scope: String?)

internal fun subscriptions(userId: String): List<Subscription> = listOf(
    Subscription(
        "channel.follow",
        2,
        buildJsonObject {
            put("broadcaster_user_id", userId)
            put("moderator_user_id", userId)
        },
        TWITCH_SCOPE_FOLLOWERS,
    ),
    Subscription(
        "channel.chat.notification",
        1,
        buildJsonObject {
            put("broadcaster_user_id", userId)
            put("user_id", userId)
        },
        TWITCH_SCOPE_CHAT,
    ),
    Subscription(
        "channel.channel_points_custom_reward_redemption.add",
        1,
        buildJsonObject { put("broadcaster_user_id", userId) },
        TWITCH_SCOPE_REDEMPTIONS,
    ),
    Subscription("channel.raid", 1, buildJsonObject { put("to_broadcaster_user_id", userId) }, null),
    Subscription("channel.cheer", 1, buildJsonObject { put("broadcaster_user_id", userId) }, TWITCH_SCOPE_BITS),
)

internal fun subscriptionBody(subscription: Subscription, sessionId: String): String =
    buildJsonObject {
        put("type", subscription.type)
        put("version", subscription.version.toString())
        put("condition", subscription.condition)
        put(
            "transport",
            buildJsonObject {
                put("method", "websocket")
                put("session_id", sessionId)
            },
        )
    }.toString()

internal fun parseEventSub(text: String): EventSubMessage? {
    val root = parseObject(text) ?: return null
    val metadata = root.obj("metadata") ?: return null
    val payload = root.obj("payload")
    return when (metadata.string("message_type")) {
        "session_welcome" -> payload?.obj("session")?.let { session ->
            session.string("id")?.let {
                EventSubMessage.Welcome(it, session.int("keepalive_timeout_seconds") ?: DEFAULT_KEEPALIVE_SECONDS)
            }
        }
        "session_keepalive" -> EventSubMessage.Keepalive
        "session_reconnect" -> payload?.obj("session")?.string("reconnect_url")?.let { EventSubMessage.Reconnect(it) }
        "notification" -> metadata.string("message_id")?.let {
            EventSubMessage.Notification(it, alertEvent(metadata.string("subscription_type"), payload?.obj("event")))
        }
        "revocation" -> payload?.obj("subscription")?.let {
            EventSubMessage.Revocation(it.string("type").orEmpty(), it.string("status").orEmpty())
        }
        else -> null
    }
}

internal fun twitchTier(tier: String?): Int = when (tier) {
    "2000" -> 2
    "3000" -> 3
    else -> 1
}

private fun alertEvent(type: String?, event: JsonObject?): AlertEvent? {
    if (event == null) return null
    return when (type) {
        "channel.follow" -> event.string("user_name")?.let { AlertEvent.TwitchFollow(it) }
        "channel.raid" -> event.string("from_broadcaster_user_name")?.let {
            AlertEvent.TwitchRaid(it, event.int("viewers") ?: 0)
        }
        "channel.cheer" -> event.int("bits")?.let {
            AlertEvent.TwitchCheer(
                user = if (event.bool("is_anonymous") == true) null else event.string("user_name"),
                bits = it,
                message = event.string("message").orEmpty(),
            )
        }
        "channel.channel_points_custom_reward_redemption.add" -> {
            val user = event.string("user_name")
            val reward = event.obj("reward")?.string("title")
            if (user == null || reward == null) null else AlertEvent.TwitchRedemption(user, reward)
        }
        "channel.chat.notification" -> chatNotification(event)
        else -> null
    }
}

private fun chatNotification(event: JsonObject): AlertEvent? {
    if (event.string("source_broadcaster_user_id") != null) return null
    val user = event.string("chatter_user_name").orEmpty()
    val anonymous = event.bool("chatter_is_anonymous") == true
    return when (event.string("notice_type")) {
        "sub" -> event.obj("sub")?.let {
            AlertEvent.TwitchSubscribe(user, twitchTier(it.string("sub_tier")), it.bool("is_prime") == true)
        }
        "resub" -> event.obj("resub")?.let {
            val months = it.int("cumulative_months") ?: return null
            AlertEvent.TwitchResubscribe(
                user = user,
                tier = twitchTier(it.string("sub_tier")),
                months = months,
                streak = it.int("streak_months"),
                message = event.obj("message")?.string("text").orEmpty(),
            )
        }
        "sub_gift" -> event.obj("sub_gift")?.takeIf { it.string("community_gift_id") == null }?.let {
            AlertEvent.TwitchSubscriptionGift(if (anonymous) null else user, 1, twitchTier(it.string("sub_tier")))
        }
        "community_sub_gift" -> event.obj("community_sub_gift")?.let {
            AlertEvent.TwitchSubscriptionGift(
                if (anonymous) null else user,
                it.int("total") ?: 1,
                twitchTier(it.string("sub_tier")),
            )
        }
        "prime_paid_upgrade" -> event.obj("prime_paid_upgrade")?.let {
            AlertEvent.TwitchSubscriptionUpgrade(user, twitchTier(it.string("sub_tier")))
        }
        "gift_paid_upgrade" -> AlertEvent.TwitchSubscriptionUpgrade(user, null)
        else -> null
    }
}

private fun parseObject(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
