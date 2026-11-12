package com.opirl.app.engine.chat.twitch

import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ApiServer
import com.opirl.app.engine.chat.Poll
import com.opirl.app.engine.chat.PollChoice
import com.opirl.app.engine.chat.StreamCategory
import com.opirl.app.engine.chat.StreamInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TwitchApiTest {
    private val server = ApiServer()
    private val api = TwitchApi(server.http, TwitchLogin("streamer", "test-token", "client", "42"), server.url("/helix"))

    @AfterTest
    fun close() = server.close()

    @Test
    fun readsTitleAndCategoryWithTheLogin() {
        server.reply("""{"data":[{"broadcaster_id":"42","title":"Walking Hanoi","game_name":"IRL","game_id":"509672"}]}""")
        assertEquals(StreamInfo("Walking Hanoi", "IRL"), api.streamInfo())
        val request = server.take()
        assertEquals("GET /helix/channels?broadcaster_id=42", "${request.method} ${request.target}")
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals("client", request.headers["Client-Id"])
    }

    @Test
    fun changesTitleAndCategory() {
        server.reply(code = 204, body = "")
        api.setTitle("Night \"market\"")
        assertEquals("""PATCH /helix/channels?broadcaster_id=42 {"title":"Night \"market\""}""", server.request())
        server.reply("""{"data":[{"id":"509672","name":"IRL","box_art_url":"x"},{"name":"no id"}]}""")
        assertEquals(listOf(StreamCategory("509672", "IRL")), api.searchCategories("ir l"))
        assertEquals("GET /helix/search/categories?query=ir%20l&first=10", server.request())
        server.reply(code = 204, body = "")
        api.setCategory(StreamCategory("509672", "IRL"))
        assertEquals("""PATCH /helix/channels?broadcaster_id=42 {"game_id":"509672"}""", server.request())
    }

    @Test
    fun addsAMarker() {
        server.reply("""{"data":[{"id":"1","position_seconds":12}]}""")
        api.createMarker()
        assertEquals("""POST /helix/streams/markers {"user_id":"42"}""", server.request())
    }

    @Test
    fun readsOnlyTheActivePoll() {
        server.reply(
            """{"data":[{"id":"p2","title":"Next?","status":"ACTIVE","choices":[{"id":"a","title":"Pho","votes":3},
            {"id":"b","title":"Banh mi"}]},{"id":"p1","title":"Old","status":"COMPLETED","choices":[]}]}""",
        )
        assertEquals(Poll("p2", "Next?", listOf(PollChoice("Pho", 3), PollChoice("Banh mi", 0))), api.poll())
        assertEquals("GET /helix/polls?broadcaster_id=42", server.request())
        server.reply("""{"data":[{"id":"p1","title":"Old","status":"TERMINATED","choices":[]}]}""")
        assertNull(api.poll())
    }

    @Test
    fun createsAndEndsAPoll() {
        server.reply()
        api.createPoll("Next?", listOf("Pho", "Banh mi"), 120)
        assertEquals(
            """POST /helix/polls {"broadcaster_id":"42","title":"Next?","choices":[{"title":"Pho"},{"title":"Banh mi"}],"duration":120}""",
            server.request(),
        )
        server.reply()
        api.endPoll(Poll("p2", "Next?", emptyList()))
        assertEquals("""PATCH /helix/polls {"broadcaster_id":"42","id":"p2","status":"TERMINATED"}""", server.request())
    }

    @Test
    fun bansAChatterByIdAndAUserByName() {
        server.reply()
        api.ban("777", "Troll", 600)
        assertEquals(
            """POST /helix/moderation/bans?broadcaster_id=42&moderator_id=42 {"data":{"user_id":"777","duration":600}}""",
            server.request(),
        )
        server.reply("""{"data":[{"id":"888","login":"troll2"}]}""")
        server.reply()
        api.ban(null, "@Troll2 ", null)
        assertEquals("GET /helix/users?login=troll2", server.request())
        assertEquals("""POST /helix/moderation/bans?broadcaster_id=42&moderator_id=42 {"data":{"user_id":"888"}}""", server.request())
    }

    @Test
    fun moderatesByName() {
        repeat(4) {
            server.reply("""{"data":[{"id":"888"}]}""")
            server.reply(code = 204, body = "")
        }
        api.unban("troll")
        api.setModerator("troll", true)
        api.setVip("troll", false)
        api.deleteMessage("m-1")
        val requests = List(7) { server.request() }.filterNot { it.startsWith("GET") }
        assertEquals(
            listOf(
                "DELETE /helix/moderation/bans?broadcaster_id=42&moderator_id=42&user_id=888",
                "POST /helix/moderation/moderators?broadcaster_id=42&user_id=888",
                "DELETE /helix/channels/vips?broadcaster_id=42&user_id=888",
                "DELETE /helix/moderation/chat?broadcaster_id=42&moderator_id=42&message_id=m-1",
            ),
            requests,
        )
    }

    @Test
    fun setsChatModes() {
        repeat(5) { server.reply() }
        api.setSlowMode(30)
        api.setSlowMode(null)
        api.setFollowersOnly(true)
        api.setEmoteOnly(false)
        api.setSubscribersOnly(true)
        val target = "PATCH /helix/chat/settings?broadcaster_id=42&moderator_id=42"
        assertEquals(
            listOf(
                """$target {"slow_mode":true,"slow_mode_wait_time":30}""",
                """$target {"slow_mode":false}""",
                """$target {"follower_mode":true}""",
                """$target {"emote_mode":false}""",
                """$target {"subscriber_mode":true}""",
            ),
            List(5) { server.request() },
        )
    }

    @Test
    fun reportsWhyACallFailed() {
        server.reply("""{"error":"Unauthorized","status":401,"message":"Missing scope: channel:manage:polls"}""", 401)
        assertEquals("Twitch login expired", assertFailsWith<ApiException> { api.poll() }.message)
        server.reply("""{"error":"Bad Request","status":400,"message":"The poll title is too long"}""", 400)
        assertEquals("Twitch: The poll title is too long", assertFailsWith<ApiException> { api.createPoll("x", listOf("a"), 15) }.message)
        server.reply("<html>bad gateway</html>", 502)
        assertEquals("Twitch error 502", assertFailsWith<ApiException> { api.createMarker() }.message)
        server.reply("""{"data":[]}""")
        assertEquals("User not found", assertFailsWith<ApiException> { api.unban("nobody") }.message)
        server.close()
        assertEquals("Cannot reach Twitch", assertFailsWith<ApiException> { api.createMarker() }.message)
    }
}
