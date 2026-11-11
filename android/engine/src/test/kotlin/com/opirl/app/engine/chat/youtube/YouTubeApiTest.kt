package com.opirl.app.engine.chat.youtube

import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ApiServer
import com.opirl.app.engine.chat.StreamInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val BROADCAST =
    """{"items":[{"id":"vid12345678","snippet":{"title":"Saigon","scheduledStartTime":"2026-11-11T10:00:00Z","liveChatId":"chat-1"}}]}"""

class YouTubeApiTest {
    private val server = ApiServer()
    private val api = YouTubeApi(server.http, "test-token", server.url("/youtube/v3"))

    @AfterTest
    fun close() = server.close()

    @Test
    fun readsTheUpcomingBroadcastWhenNoneIsActive() {
        server.reply("""{"items":[]}""")
        server.reply(BROADCAST)
        assertEquals(StreamInfo("Saigon", ""), api.streamInfo())
        val request = server.take()
        assertEquals("/youtube/v3/liveBroadcasts?part=snippet&broadcastStatus=active", request.target)
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals("GET /youtube/v3/liveBroadcasts?part=snippet&broadcastStatus=upcoming", server.request())
    }

    @Test
    fun changesTheTitleOfTheBroadcast() {
        server.reply(BROADCAST)
        server.reply()
        api.setTitle("Hanoi")
        server.request()
        assertEquals(
            """PUT /youtube/v3/liveBroadcasts?part=snippet {"id":"vid12345678","snippet":{"title":"Hanoi","scheduledStartTime":"2026-11-11T10:00:00Z"}}""",
            server.request(),
        )
    }

    @Test
    fun bansFromTheLiveChat() {
        repeat(2) {
            server.reply(BROADCAST)
            server.reply()
        }
        api.ban("UCtroll", "Troll", 300)
        api.ban("UCtroll", "Troll", null)
        val bans = List(4) { server.request() }.filter { it.startsWith("POST") }
        val target = "POST /youtube/v3/liveChat/bans?part=snippet"
        assertEquals(
            listOf(
                """$target {"snippet":{"liveChatId":"chat-1","type":"temporary","banDurationSeconds":300,"bannedUserDetails":{"channelId":"UCtroll"}}}""",
                """$target {"snippet":{"liveChatId":"chat-1","type":"permanent","bannedUserDetails":{"channelId":"UCtroll"}}}""",
            ),
            bans,
        )
    }

    @Test
    fun reportsWhyACallFailed() {
        server.reply("""{"items":[]}""")
        server.reply("""{"items":[]}""")
        assertEquals("YouTube: no broadcast", assertFailsWith<ApiException> { api.streamInfo() }.message)
        server.reply("""{"error":{"code":403,"message":"The request cannot be completed because you have exceeded your quota."}}""", 403)
        assertEquals(
            "YouTube: The request cannot be completed because you have exceeded your quota.",
            assertFailsWith<ApiException> { api.streamInfo() }.message,
        )
        server.reply("""{"error":{"code":401,"message":"Invalid Credentials"}}""", 401)
        assertEquals("YouTube login expired", assertFailsWith<ApiException> { api.streamInfo() }.message)
    }
}
