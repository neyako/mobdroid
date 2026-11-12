package com.opirl.app.engine.chat.kick

import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ApiServer
import com.opirl.app.engine.chat.StreamCategory
import com.opirl.app.engine.chat.StreamInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val CHANNEL = """{"id":668,"user_id":676,"slug":"some-streamer","chatroom":{"id":4242,"channel_id":668}}"""

private const val CATEGORIES =
    """{"facet_counts":[],"found":10,"hits":[{"document":{"category_id":0,"description":"","id":"8549","is_live":true,
    "is_mature":false,"name":"IRL","parent":"","slug":"irl","src":"https://files.kick.com/images/subcategories/8549/banner/x"}},
    {"document":{"name":"no id"}}]}"""

class KickApiTest {
    private val server = ApiServer()
    private val api = KickApi(server.http, KickLogin("test-token"), "Some_Streamer", server.url(), server.url("/search"))

    @AfterTest
    fun close() = server.close()

    @Test
    fun looksTheChannelUpOnceAndReadsTheStreamInfo() {
        server.reply(code = 404)
        server.reply(CHANNEL)
        server.reply("""{"stream_title":"Da Nang walk","category":{"id":15,"name":"IRL"}}""")
        assertEquals(StreamInfo("Da Nang walk", "IRL"), api.streamInfo())
        assertEquals("GET /api/v1/channels/Some_Streamer", server.request())
        assertEquals("GET /api/v1/channels/Some-Streamer", server.request())
        val request = server.take()
        assertEquals("GET /api/v2/channels/some-streamer/stream-info", "${request.method} ${request.target}")
        assertEquals("Bearer test-token", request.headers["Authorization"])
        server.reply()
        api.setTitle("Hue")
        assertEquals("""PATCH /api/v2/channels/some-streamer/stream-info {"stream_title":"Hue"}""", server.request())
    }

    @Test
    fun searchesAndSetsTheCategory() {
        server.reply(CATEGORIES)
        assertEquals(listOf(StreamCategory("8549", "IRL")), api.searchCategories("irl"))
        val request = server.take()
        assertEquals("/search/collections/subcategory_index/documents/search?q=irl&query_by=name", request.target)
        assertEquals("nXIMW0iEN6sMujFYjFuhdrSwVow3pDQu", request.headers["X-Typesense-Api-Key"])
        server.reply(CHANNEL)
        server.reply()
        api.setCategory(StreamCategory("8549", "IRL"))
        server.request()
        assertEquals("""PATCH /api/v2/channels/some-streamer/stream-info {"category_id":8549}""", server.request())
    }

    @Test
    fun moderates() {
        server.reply(CHANNEL)
        repeat(8) { server.reply() }
        api.ban(null, "troll", 600)
        api.ban(null, "troll", null)
        api.unban("troll")
        api.deleteMessage("m-1")
        api.setModerator("friend", true)
        api.setVip("friend", false)
        api.createPoll("Next?", listOf("Pho", "Banh mi"), 120)
        api.endPoll(null)
        server.request()
        assertEquals(
            listOf(
                """POST /api/v2/channels/some-streamer/bans {"banned_username":"troll","permanent":false,"duration":10}""",
                """POST /api/v2/channels/some-streamer/bans {"banned_username":"troll","permanent":true}""",
                "DELETE /api/v2/channels/some-streamer/bans/troll",
                "DELETE /api/v2/chatrooms/4242/messages/m-1",
                """POST /api/internal/v1/channels/some-streamer/community/moderators {"username":"friend"}""",
                "DELETE /api/internal/v1/channels/some-streamer/community/vips/friend",
                """POST /api/v2/channels/some-streamer/polls {"title":"Next?","options":["Pho","Banh mi"],"duration":120,"result_display_duration":15}""",
                "DELETE /api/v2/channels/some-streamer/polls",
            ),
            List(8) { server.request() },
        )
    }

    @Test
    fun setsChatModes() {
        server.reply(CHANNEL)
        repeat(5) { server.reply() }
        api.setSlowMode(30)
        api.setSlowMode(null)
        api.setFollowersOnly(true)
        api.setEmoteOnly(true)
        api.setSubscribersOnly(false)
        server.request()
        val target = "PUT /api/v2/channels/some-streamer/chatroom"
        assertEquals(
            listOf(
                """$target {"slow_mode":true,"message_interval":30}""",
                """$target {"slow_mode":false}""",
                """$target {"followers_mode":true,"following_min_duration":0}""",
                """$target {"emotes_mode":true}""",
                """$target {"subscribers_mode":false}""",
            ),
            List(5) { server.request() },
        )
    }

    @Test
    fun reportsWhyACallFailed() {
        server.reply(code = 404)
        server.reply(code = 404)
        assertEquals("Kick error 404", assertFailsWith<ApiException> { api.streamInfo() }.message)
        server.reply(CHANNEL)
        server.reply("""{"message":"Unauthenticated."}""", 401)
        assertEquals("Kick login expired", assertFailsWith<ApiException> { api.setTitle("x") }.message)
        server.reply("""{"status":{"error":true,"code":400,"message":"Poll already active"}}""", 400)
        assertEquals("Kick: Poll already active", assertFailsWith<ApiException> { api.createPoll("x", listOf("a", "b"), 30) }.message)
    }
}
