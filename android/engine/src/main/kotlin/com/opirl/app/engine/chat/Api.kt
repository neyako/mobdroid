package com.opirl.app.engine.chat

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(message: String, val code: Int = 0) : IOException(message)

data class StreamInfo(val title: String, val category: String)

data class StreamCategory(val id: String, val name: String)

data class PollChoice(val title: String, val votes: Int)

data class Poll(val id: String, val title: String, val choices: List<PollChoice>)

enum class StreamFeature { TITLE, CATEGORY, MARKER, POLL, POLL_RESULTS, DELETE_MESSAGE, BAN, UNBAN, ROLES, CHAT_MODES }

fun streamFeatures(platform: ChatPlatform): Set<StreamFeature> = when (platform) {
    ChatPlatform.TWITCH -> StreamFeature.entries.toSet()
    ChatPlatform.KICK -> StreamFeature.entries.toSet() - StreamFeature.MARKER - StreamFeature.POLL_RESULTS
    ChatPlatform.YOUTUBE -> setOf(StreamFeature.TITLE, StreamFeature.BAN)
}

interface StreamApi {
    fun streamInfo(): StreamInfo

    fun setTitle(title: String)

    fun searchCategories(query: String): List<StreamCategory> = unsupported()

    fun setCategory(category: StreamCategory): Unit = unsupported()

    fun createMarker(): Unit = unsupported()

    fun poll(): Poll? = unsupported()

    fun createPoll(title: String, choices: List<String>, durationSeconds: Int): Unit = unsupported()

    fun endPoll(poll: Poll?): Unit = unsupported()

    fun deleteMessage(messageId: String): Unit = unsupported()

    fun ban(userId: String?, user: String, durationSeconds: Int?)

    fun unban(user: String): Unit = unsupported()

    fun setModerator(user: String, on: Boolean): Unit = unsupported()

    fun setVip(user: String, on: Boolean): Unit = unsupported()

    fun setSlowMode(seconds: Int?): Unit = unsupported()

    fun setFollowersOnly(on: Boolean): Unit = unsupported()

    fun setEmoteOnly(on: Boolean): Unit = unsupported()

    fun setSubscribersOnly(on: Boolean): Unit = unsupported()
}

private fun unsupported(): Nothing = throw ApiException("Not supported")

internal fun parseJsonObject(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
}

internal fun parseJsonArray(text: String): JsonArray? = try {
    Json.parseToJsonElement(text) as? JsonArray
} catch (_: IllegalArgumentException) {
    null
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

internal class ApiClient(
    private val http: OkHttpClient,
    private val name: String,
    private val baseUrl: String,
    private val headers: Map<String, String> = emptyMap(),
    private val unauthorized: String = "$name login expired",
) {
    fun call(
        method: String,
        vararg path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
    ): JsonObject? = parseJsonObject(String(bytes(method, *path, query = query, body = body)))

    fun bytes(
        method: String,
        vararg path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
        accept: String? = null,
    ): ByteArray {
        val url = baseUrl.toHttpUrl().newBuilder()
        path.forEach { url.addPathSegment(it) }
        query.forEach { url.addQueryParameter(it.first, it.second) }
        val payload = when {
            body != null -> body.toString().toRequestBody(jsonMediaType)
            method == "GET" || method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val request = Request.Builder().url(url.build()).method(method, payload)
        headers.forEach { (key, value) -> request.header(key, value) }
        if (accept != null) request.header("Accept", accept)
        val (code, bytes) = try {
            http.newCall(request.build()).execute().use { it.code to it.body.bytes() }
        } catch (_: IOException) {
            throw ApiException("Cannot reach $name")
        }
        if (code in 200..299) return bytes
        if (code == 401) throw ApiException(unauthorized, code)
        val json = parseJsonObject(String(bytes))
        val message = json?.string("message")
            ?: json?.obj("error")?.string("message")
            ?: json?.obj("status")?.string("message")
            ?: json?.obj("detail")?.string("message")
            ?: json?.string("error")
        throw ApiException(if (message.isNullOrBlank()) "$name error $code" else "$name: $message", code)
    }

    private companion object {
        val jsonMediaType = "application/json".toMediaType()
    }
}
