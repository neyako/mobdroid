package com.opirl.app.engine.chat.twitch

import com.opirl.app.engine.chat.ApiClient
import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.Poll
import com.opirl.app.engine.chat.PollChoice
import com.opirl.app.engine.chat.StreamApi
import com.opirl.app.engine.chat.StreamCategory
import com.opirl.app.engine.chat.StreamInfo
import com.opirl.app.engine.chat.int
import com.opirl.app.engine.chat.objects
import com.opirl.app.engine.chat.string
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

val TWITCH_SCOPES = listOf(
    "user:read:chat",
    "user:write:chat",
    "channel:manage:broadcast",
    "channel:manage:polls",
    "channel:manage:moderators",
    "channel:manage:vips",
    "moderator:manage:banned_users",
    "moderator:manage:chat_messages",
    "moderator:manage:chat_settings",
)

class TwitchApi internal constructor(http: OkHttpClient, private val login: TwitchLogin, baseUrl: String) : StreamApi {
    constructor(http: OkHttpClient, login: TwitchLogin) : this(http, login, "https://api.twitch.tv/helix")

    private val api = ApiClient(
        http,
        "Twitch",
        baseUrl,
        mapOf("Authorization" to "Bearer ${login.accessToken}", "Client-Id" to login.clientId),
    )
    private val broadcaster = "broadcaster_id" to login.userId
    private val moderator = "moderator_id" to login.userId

    override fun streamInfo(): StreamInfo {
        val channel = api.call("GET", "channels", query = listOf(broadcaster))?.objects("data")?.firstOrNull()
            ?: throw ApiException("Twitch: no channel")
        return StreamInfo(channel.string("title").orEmpty(), channel.string("game_name").orEmpty())
    }

    override fun setTitle(title: String) {
        api.call("PATCH", "channels", query = listOf(broadcaster), body = buildJsonObject { put("title", title) })
    }

    override fun searchCategories(query: String): List<StreamCategory> =
        api.call("GET", "search", "categories", query = listOf("query" to query, "first" to "10"))
            ?.objects("data")
            .orEmpty()
            .mapNotNull { StreamCategory(it.string("id") ?: return@mapNotNull null, it.string("name").orEmpty()) }

    override fun setCategory(category: StreamCategory) {
        api.call("PATCH", "channels", query = listOf(broadcaster), body = buildJsonObject { put("game_id", category.id) })
    }

    override fun createMarker() {
        api.call("POST", "streams", "markers", body = buildJsonObject { put("user_id", login.userId) })
    }

    override fun poll(): Poll? = api.call("GET", "polls", query = listOf(broadcaster))
        ?.objects("data")
        .orEmpty()
        .firstOrNull { it.string("status") == "ACTIVE" }
        ?.let { poll ->
            Poll(
                poll.string("id") ?: return null,
                poll.string("title").orEmpty(),
                poll.objects("choices").map { PollChoice(it.string("title").orEmpty(), it.int("votes") ?: 0) },
            )
        }

    override fun createPoll(title: String, choices: List<String>, durationSeconds: Int) {
        val body = buildJsonObject {
            put("broadcaster_id", login.userId)
            put("title", title)
            put("choices", buildJsonArray { choices.forEach { add(buildJsonObject { put("title", it) }) } })
            put("duration", durationSeconds)
        }
        api.call("POST", "polls", body = body)
    }

    override fun endPoll(poll: Poll?) {
        val body = buildJsonObject {
            put("broadcaster_id", login.userId)
            put("id", poll?.id ?: throw ApiException("Twitch: no poll"))
            put("status", "TERMINATED")
        }
        api.call("PATCH", "polls", body = body)
    }

    override fun deleteMessage(messageId: String) {
        api.call("DELETE", "moderation", "chat", query = listOf(broadcaster, moderator, "message_id" to messageId))
    }

    override fun ban(userId: String?, user: String, durationSeconds: Int?) {
        val body = buildJsonObject {
            putJsonObject("data") {
                put("user_id", userId ?: userId(user))
                if (durationSeconds != null) put("duration", durationSeconds)
            }
        }
        api.call("POST", "moderation", "bans", query = listOf(broadcaster, moderator), body = body)
    }

    override fun unban(user: String) {
        api.call("DELETE", "moderation", "bans", query = listOf(broadcaster, moderator, "user_id" to userId(user)))
    }

    override fun setModerator(user: String, on: Boolean) {
        api.call(if (on) "POST" else "DELETE", "moderation", "moderators", query = listOf(broadcaster, "user_id" to userId(user)))
    }

    override fun setVip(user: String, on: Boolean) {
        api.call(if (on) "POST" else "DELETE", "channels", "vips", query = listOf(broadcaster, "user_id" to userId(user)))
    }

    override fun setSlowMode(seconds: Int?) = chatSettings {
        put("slow_mode", seconds != null)
        if (seconds != null) put("slow_mode_wait_time", seconds)
    }

    override fun setFollowersOnly(on: Boolean) = chatSettings { put("follower_mode", on) }

    override fun setEmoteOnly(on: Boolean) = chatSettings { put("emote_mode", on) }

    override fun setSubscribersOnly(on: Boolean) = chatSettings { put("subscriber_mode", on) }

    private fun chatSettings(build: JsonObjectBuilder.() -> Unit) {
        api.call("PATCH", "chat", "settings", query = listOf(broadcaster, moderator), body = buildJsonObject(build))
    }

    private fun userId(user: String): String =
        api.call("GET", "users", query = listOf("login" to user.trim().removePrefix("@").lowercase()))
            ?.objects("data")
            ?.firstOrNull()
            ?.string("id")
            ?: throw ApiException("User not found")
}
