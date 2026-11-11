package com.opirl.app.engine.chat.kick

import com.opirl.app.engine.chat.ApiClient
import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.Poll
import com.opirl.app.engine.chat.StreamApi
import com.opirl.app.engine.chat.StreamCategory
import com.opirl.app.engine.chat.StreamInfo
import com.opirl.app.engine.chat.int
import com.opirl.app.engine.chat.obj
import com.opirl.app.engine.chat.objects
import com.opirl.app.engine.chat.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

private const val SEARCH_KEY = "nXIMW0iEN6sMujFYjFuhdrSwVow3pDQu"

internal class KickChannel(val slug: String, val chatroomId: Int, val userId: Int)

internal fun parseKickChannel(channel: JsonObject): KickChannel? = KickChannel(
    channel.string("slug") ?: return null,
    channel.obj("chatroom")?.int("id") ?: return null,
    channel.int("user_id") ?: return null,
)

class KickApi internal constructor(
    http: OkHttpClient,
    login: KickLogin,
    private val channelName: String,
    baseUrl: String,
    searchUrl: String,
) : StreamApi {
    constructor(http: OkHttpClient, login: KickLogin, channel: String) :
        this(http, login, channel, "https://kick.com", "https://search.kick.com")

    private val api = ApiClient(http, "Kick", baseUrl, mapOf("Authorization" to "Bearer ${login.accessToken}"))
    private val search = ApiClient(http, "Kick", searchUrl, mapOf("X-Typesense-Api-Key" to SEARCH_KEY))
    private val channel by lazy {
        var failure: ApiException? = null
        for (name in channelName.trim().let { listOf(it, it.replace('_', '-')).distinct() }) {
            try {
                api.call("GET", "api", "v1", "channels", name)?.let(::parseKickChannel)?.let { return@lazy it }
            } catch (e: ApiException) {
                failure = e
            }
        }
        throw failure ?: ApiException("Kick channel not found")
    }

    override fun streamInfo(): StreamInfo {
        val info = v2("GET", "stream-info") ?: throw ApiException("Kick: no stream info")
        return StreamInfo(info.string("stream_title").orEmpty(), info.obj("category")?.string("name").orEmpty())
    }

    override fun setTitle(title: String) {
        v2("PATCH", "stream-info", body = buildJsonObject { put("stream_title", title) })
    }

    override fun searchCategories(query: String): List<StreamCategory> = search.call(
        "GET",
        "collections",
        "subcategory_index",
        "documents",
        "search",
        query = listOf("q" to query, "query_by" to "name"),
    )?.objects("hits").orEmpty().mapNotNull { hit ->
        val document = hit.obj("document") ?: return@mapNotNull null
        StreamCategory(document.string("id") ?: return@mapNotNull null, document.string("name").orEmpty())
    }

    override fun setCategory(category: StreamCategory) {
        val id = category.id.toIntOrNull() ?: throw ApiException("Kick: unknown category")
        v2("PATCH", "stream-info", body = buildJsonObject { put("category_id", id) })
    }

    override fun createPoll(title: String, choices: List<String>, durationSeconds: Int) {
        val body = buildJsonObject {
            put("title", title)
            put("options", buildJsonArray { choices.forEach { add(JsonPrimitive(it)) } })
            put("duration", durationSeconds)
            put("result_display_duration", 15)
        }
        v2("POST", "polls", body = body)
    }

    override fun endPoll(poll: Poll?) {
        v2("DELETE", "polls")
    }

    override fun deleteMessage(messageId: String) {
        api.call("DELETE", "api", "v2", "chatrooms", channel.chatroomId.toString(), "messages", messageId)
    }

    override fun ban(userId: String?, user: String, durationSeconds: Int?) {
        val body = buildJsonObject {
            put("banned_username", user)
            put("permanent", durationSeconds == null)
            if (durationSeconds != null) put("duration", maxOf(durationSeconds / 60, 1))
        }
        v2("POST", "bans", body = body)
    }

    override fun unban(user: String) {
        v2("DELETE", "bans", user)
    }

    override fun setModerator(user: String, on: Boolean) = role("moderators", user, on)

    override fun setVip(user: String, on: Boolean) = role("vips", user, on)

    override fun setSlowMode(seconds: Int?) = chatroom {
        put("slow_mode", seconds != null)
        if (seconds != null) put("message_interval", seconds)
    }

    override fun setFollowersOnly(on: Boolean) = chatroom {
        put("followers_mode", on)
        if (on) put("following_min_duration", 0)
    }

    override fun setEmoteOnly(on: Boolean) = chatroom { put("emotes_mode", on) }

    override fun setSubscribersOnly(on: Boolean) = chatroom { put("subscribers_mode", on) }

    private fun v2(method: String, vararg path: String, body: JsonObject? = null): JsonObject? =
        api.call(method, "api", "v2", "channels", channel.slug, *path, body = body)

    private fun chatroom(build: JsonObjectBuilder.() -> Unit) {
        v2("PUT", "chatroom", body = buildJsonObject(build))
    }

    private fun role(kind: String, user: String, on: Boolean) {
        val base = arrayOf("api", "internal", "v1", "channels", channel.slug, "community", kind)
        if (on) {
            api.call("POST", *base, body = buildJsonObject { put("username", user) })
        } else {
            api.call("DELETE", *base, user)
        }
    }
}
