package com.opirl.app.engine.chat.emotes

import com.opirl.app.engine.chat.ApiClient
import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import com.opirl.app.engine.chat.obj
import com.opirl.app.engine.chat.objects
import com.opirl.app.engine.chat.parseJsonArray
import com.opirl.app.engine.chat.parseJsonObject
import com.opirl.app.engine.chat.string
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

enum class EmoteProvider { BTTV, FFZ, SEVENTV }

private val word = Regex("\\S+")

fun applyEmotes(segments: List<ChatSegment>, emotes: Map<String, String>): List<ChatSegment> {
    if (emotes.isEmpty()) return segments
    val result = ArrayList<ChatSegment>(segments.size)
    for (segment in segments) {
        if (segment !is ChatSegment.Text || word.findAll(segment.text).none { it.value in emotes }) {
            result += segment
            continue
        }
        var start = 0
        for (match in word.findAll(segment.text)) {
            val url = emotes[match.value] ?: continue
            if (match.range.first > start) result += ChatSegment.Text(segment.text.substring(start, match.range.first))
            result += ChatSegment.Emote(match.value, url)
            start = match.range.last + 1
        }
        if (start < segment.text.length) result += ChatSegment.Text(segment.text.substring(start))
    }
    return result
}

internal fun parseBttvEmotes(emotes: List<JsonObject>): Map<String, String> = emotes.mapNotNull { emote ->
    val id = emote.string("id") ?: return@mapNotNull null
    (emote.string("code") ?: return@mapNotNull null) to "https://cdn.betterttv.net/emote/$id/2x"
}.toMap()

internal fun parseFfzEmotes(emotes: List<JsonObject>): Map<String, String> = emotes.mapNotNull { emote ->
    val images = emote.obj("images") ?: return@mapNotNull null
    val url = images.string("2x") ?: images.string("1x") ?: images.string("4x") ?: return@mapNotNull null
    (emote.string("code") ?: return@mapNotNull null) to url
}.toMap()

internal fun parseSeventvEmotes(set: JsonObject?): Map<String, String> = set?.objects("emotes").orEmpty().mapNotNull { emote ->
    val host = emote.obj("data")?.obj("host") ?: return@mapNotNull null
    val files = host.objects("files").filter { it.string("name")?.endsWith(".webp") == true }
    val file = files.firstOrNull { it.string("name") == "2x.webp" } ?: files.firstOrNull() ?: return@mapNotNull null
    val name = file.string("static_name") ?: file.string("name")
    (emote.string("name") ?: return@mapNotNull null) to "https:${host.string("url") ?: return@mapNotNull null}/$name"
}.toMap()

class EmoteFetcher internal constructor(http: OkHttpClient, bttvUrl: String, seventvUrl: String) {
    constructor(http: OkHttpClient) : this(http, "https://api.betterttv.net/3/cached", "https://7tv.io/v3")

    private val bttv = ApiClient(http, "BTTV", bttvUrl)
    private val seventv = ApiClient(http, "7TV", seventvUrl)

    fun global(provider: EmoteProvider): Map<String, String> = when (provider) {
        EmoteProvider.BTTV -> parseBttvEmotes(array(bttv.bytes("GET", "emotes", "global")))
        EmoteProvider.FFZ -> parseFfzEmotes(array(bttv.bytes("GET", "frankerfacez", "emotes", "global")))
        EmoteProvider.SEVENTV -> parseSeventvEmotes(seventv.call("GET", "emote-sets", "global"))
    }

    fun channel(provider: EmoteProvider, platform: ChatPlatform, channelId: String): Map<String, String> = try {
        val name = platform.name.lowercase()
        when (provider) {
            EmoteProvider.BTTV -> bttv.call("GET", "users", name, channelId).let { channel ->
                parseBttvEmotes(channel?.objects("sharedEmotes").orEmpty() + channel?.objects("channelEmotes").orEmpty())
            }
            EmoteProvider.FFZ -> parseFfzEmotes(array(bttv.bytes("GET", "frankerfacez", "users", name, channelId)))
            EmoteProvider.SEVENTV -> parseSeventvEmotes(seventv.call("GET", "users", name, channelId)?.obj("emote_set"))
        }
    } catch (e: ApiException) {
        if (e.code == 404) emptyMap() else throw e
    }

    private fun array(bytes: ByteArray): List<JsonObject> = parseJsonArray(String(bytes))?.filterIsInstance<JsonObject>().orEmpty()
}
