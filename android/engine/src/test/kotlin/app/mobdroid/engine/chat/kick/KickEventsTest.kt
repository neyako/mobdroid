package app.mobdroid.engine.chat.kick

import app.mobdroid.engine.chat.ChatMessage
import app.mobdroid.engine.chat.ChatPlatform
import app.mobdroid.engine.chat.ChatSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NOW = 1_700_000_000_000L

class KickEventsTest {
    private fun message(frame: String, nowMs: Long = NOW): ChatMessage =
        assertIs<KickEvent.Message>(parseKickFrame(frame, nowMs)).message

    private fun chatFrame(data: String): String =
        buildJsonObject {
            put("event", "App\\Events\\ChatMessageEvent")
            put("data", data)
        }.toString()

    @Test
    fun readsAPlainMessageOutOfTheDoubleEncodedFrame() {
        assertEquals(
            ChatMessage(
                platform = ChatPlatform.KICK,
                id = "6f1d2c4e-0b7a-4c55-9d1e-2a8f3b7c9e10",
                userId = "4821",
                user = "PixelFox",
                userColor = 0xFEA0CF,
                segments = listOf(ChatSegment.Text("that last play was clean")),
                timestampMs = 1_794_281_522_000,
            ),
            message(KickFrames.PLAIN),
        )
        assertEquals(message(KickFrames.PLAIN), message(KickFrames.OBJECT_DATA))
    }

    @Test
    fun keepsEmotesAndTextInMessageOrder() {
        val police = ChatSegment.Emote("POLICE", "https://files.kick.com/emotes/37230/fullsize")
        val parsed = message(KickFrames.EMOTES)
        assertEquals(listOf(police, ChatSegment.Text(" CLEAN RUN "), police), parsed.segments)
        assertEquals("POLICE CLEAN RUN POLICE", parsed.text)
    }

    @Test
    fun splitsInlineEmoteTags() {
        val a = ChatSegment.Emote("A", "https://files.kick.com/emotes/1/fullsize")
        val b = ChatSegment.Emote("B_2", "https://files.kick.com/emotes/22/fullsize")
        val cases: Map<String, List<ChatSegment>> = mapOf(
            "" to emptyList(),
            "plain text" to listOf(ChatSegment.Text("plain text")),
            "[emote:1:A]" to listOf(a),
            "hi [emote:1:A][emote:22:B_2] bye" to listOf(ChatSegment.Text("hi "), a, b, ChatSegment.Text(" bye")),
            "[emote:1:A] [emote:22:B_2]" to listOf(a, ChatSegment.Text(" "), b),
            "[emote:x:A] [emote:1:] [emote:1:A" to listOf(ChatSegment.Text("[emote:x:A] [emote:1:] [emote:1:A")),
        )
        for ((content, expected) in cases) {
            assertEquals(expected, parseKickEmotes(content), content)
        }
    }

    @Test
    fun mapsBadgesToRoles() {
        val moderatorAndSubscriber = message(KickFrames.EMOTES)
        assertTrue(moderatorAndSubscriber.isModerator && moderatorAndSubscriber.isSubscriber)
        assertFalse(moderatorAndSubscriber.isBroadcaster)
        val broadcaster = message(KickFrames.BROADCASTER)
        assertTrue(broadcaster.isBroadcaster)
        assertFalse(broadcaster.isModerator || broadcaster.isSubscriber)
        val replier = message(KickFrames.REPLY)
        assertEquals("about five hours", replier.text)
        assertTrue(replier.isSubscriber)
    }

    @Test
    fun colorIsNullWhenMissingOrNotSixHexDigits() {
        assertNull(message(KickFrames.NO_COLOR).userColor)
        assertNull(message(chatFrame("""{"id":"1","content":"hi","sender":{"id":1,"username":"x"}}""")).userColor)
        for (text in listOf("", "#", "red", "#12345", "#1234567", "#GGGGGG", "-12345", "+12345")) {
            assertNull(parseKickColor(text), text)
        }
        assertEquals(0xFEA0CF, parseKickColor("#FEA0CF"))
        assertEquals(0x00F1FF, parseKickColor("00f1ff"))
    }

    @Test
    fun usesTheEventTimeAndFallsBackToTheClock() {
        assertEquals(1_794_281_522_000, message(KickFrames.PLAIN).timestampMs)
        assertEquals(NOW, message(KickFrames.PLAIN.replace("created_at", "created_on")).timestampMs)
        assertEquals(NOW, message(KickFrames.PLAIN.replace("2026-11-10T03:32:02+00:00", "yesterday")).timestampMs)
    }

    @Test
    fun readsDeletedMessagesAndBannedUsers() {
        assertEquals(
            KickEvent.MessageDeleted("0c4cdb14-56e5-49fe-94da-fd68ff0cbb2c"),
            parseKickFrame(KickFrames.DELETED, NOW),
        )
        assertEquals(KickEvent.UserBanned("4821"), parseKickFrame(KickFrames.BANNED, NOW))
    }

    @Test
    fun recognizesThePusherControlFrames() {
        assertEquals(KickEvent.ConnectionEstablished, parseKickFrame(KickFrames.ESTABLISHED, NOW))
        assertEquals(KickEvent.Subscribed("chatrooms.100200.v2"), parseKickFrame(KickFrames.SUBSCRIBED, NOW))
        assertEquals(KickEvent.Ping, parseKickFrame(KickFrames.PING, NOW))
    }

    @Test
    fun ignoresFramesItCannotUse() {
        val unknown = """{"event":"App\\Events\\PinnedMessageCreatedEvent","data":"{}","channel":"chatrooms.1.v2"}"""
        val unusable = listOf(
            unknown,
            "not json",
            "[]",
            """{"data":"{}"}""",
            chatFrame("not json"),
            chatFrame("""{"id":"1","content":"hi","sender":{"id":1}}"""),
            chatFrame("""{"id":"1","sender":{"id":1,"username":"x"}}"""),
            chatFrame("""{"id":"1","content":"hi"}"""),
        )
        for (frame in unusable) {
            assertNull(parseKickFrame(frame, NOW), frame)
        }
    }

    @Test
    fun readsTheChatroomIdNotTheChannelIdFromALookup() {
        val lookup =
            """{"id":32807,"slug":"somechannel","chatroom":{"id":32806,"channel_id":32807,"slow_mode":false}}"""
        assertEquals(32806L, parseKickChatroomId(lookup))
        val unusable = listOf(
            """{"message":"No query results for model [App\\Models\\Channel]."}""",
            """{"chatroom":{"id":0}}""",
            """{"chatroom":{"id":"abc"}}""",
            "<html>Just a moment...</html>",
        )
        for (body in unusable) {
            assertNull(parseKickChatroomId(body), body)
        }
    }

    @Test
    fun readsTheMessageOfAnErrorBody() {
        val rejected = """{"message":"User is not authenticated."}"""
        assertEquals("User is not authenticated.", parseKickErrorMessage(rejected))
        for (body in listOf("""{"message":""}""", """{"status":{}}""", "<html>Forbidden</html>")) {
            assertNull(parseKickErrorMessage(body), body)
        }
    }
}
