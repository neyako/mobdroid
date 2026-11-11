package com.opirl.app.engine.chat.youtube

import com.opirl.app.engine.chat.ApiClient
import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.StreamApi
import com.opirl.app.engine.chat.StreamInfo
import com.opirl.app.engine.chat.obj
import com.opirl.app.engine.chat.objects
import com.opirl.app.engine.chat.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"

class YouTubeApi internal constructor(http: OkHttpClient, accessToken: String, baseUrl: String) : StreamApi {
    constructor(http: OkHttpClient, accessToken: String) : this(http, accessToken, "https://youtube.googleapis.com/youtube/v3")

    private val api = ApiClient(http, "YouTube", baseUrl, mapOf("Authorization" to "Bearer $accessToken"))

    override fun streamInfo(): StreamInfo = StreamInfo(broadcast().obj("snippet")?.string("title").orEmpty(), "")

    override fun setTitle(title: String) {
        val broadcast = broadcast()
        val snippet = broadcast.obj("snippet")
        val body = buildJsonObject {
            put("id", broadcast.string("id"))
            putJsonObject("snippet") {
                put("title", title)
                for (kept in listOf("description", "scheduledStartTime", "scheduledEndTime")) {
                    snippet?.string(kept)?.let { put(kept, it) }
                }
            }
        }
        api.call("PUT", "liveBroadcasts", query = listOf("part" to "snippet"), body = body)
    }

    override fun ban(userId: String?, user: String, durationSeconds: Int?) {
        val body = buildJsonObject {
            putJsonObject("snippet") {
                put("liveChatId", broadcast().obj("snippet")?.string("liveChatId") ?: throw ApiException("YouTube: no live chat"))
                put("type", if (durationSeconds == null) "permanent" else "temporary")
                if (durationSeconds != null) put("banDurationSeconds", durationSeconds)
                putJsonObject("bannedUserDetails") { put("channelId", userId ?: throw ApiException("User not found")) }
            }
        }
        api.call("POST", "liveChat", "bans", query = listOf("part" to "snippet"), body = body)
    }

    private fun broadcast(): JsonObject = listOf("active", "upcoming").firstNotNullOfOrNull { status ->
        api.call("GET", "liveBroadcasts", query = listOf("part" to "snippet", "broadcastStatus" to status))
            ?.objects("items")
            ?.firstOrNull()
    } ?: throw ApiException("YouTube: no broadcast")
}
