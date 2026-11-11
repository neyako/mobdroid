package com.opirl.app.engine.chat.twitch

import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MS = 1_700_000_000_000L

internal fun capturedPrivmsg(
    text: String,
    emotes: String = "",
    badges: String = "",
    color: String = "#1E90FF",
    displayName: String = "Foo_Bar",
    mod: String = "0",
    subscriber: String = "0",
    nick: String = "foo_bar",
): String =
    "@badge-info=;badges=$badges;client-nonce=0f3c5e7a9b1d2c4e6f8a0b2c4d6e8f10;color=$color;display-name=$displayName;" +
        "emotes=$emotes;first-msg=0;flags=;id=6b3a2b71-b932-4ef3-8dcd-2dbe26f787fc;mod=$mod;returning-chatter=0;" +
        "room-id=12345678;subscriber=$subscriber;tmi-sent-ts=1794281924514;turbo=0;user-id=87654321;user-type= " +
        ":$nick!$nick@$nick.tmi.twitch.tv PRIVMSG #somechan :$text"

private fun event(line: String): TwitchEvent? = parseIrcLine(line)?.let { twitchEvent(it, NOW_MS) }

private fun message(line: String): ChatMessage = (event(line) as TwitchEvent.Message).message

private fun emote(name: String, id: String) =
    ChatSegment.Emote(name, "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0")

private fun text(value: String) = ChatSegment.Text(value)

class TwitchIrcTest {
    @Test
    fun plainMessageKeepsIdentityColorRolesAndTimestamp() {
        val line = "@badge-info=subscriber/8;badges=subscriber/6,bits-charity/1;" +
            "client-nonce=0f3c5e7a9b1d2c4e6f8a0b2c4d6e8f10;color=#1E90FF;display-name=Foo_Bar;emotes=;first-msg=0;flags=;" +
            "id=6b3a2b71-b932-4ef3-8dcd-2dbe26f787fc;mod=0;returning-chatter=0;room-id=12345678;subscriber=1;" +
            "tmi-sent-ts=1794281924514;turbo=0;user-id=87654321;user-type= " +
            ":foo_bar!foo_bar@foo_bar.tmi.twitch.tv PRIVMSG #somechan :that was close"
        val expected = ChatMessage(
            platform = ChatPlatform.TWITCH,
            id = "6b3a2b71-b932-4ef3-8dcd-2dbe26f787fc",
            userId = "87654321",
            user = "Foo_Bar",
            userColor = 0x1E90FF,
            segments = listOf(text("that was close")),
            timestampMs = 1794281924514,
            isSubscriber = true,
        )
        assertEquals(expected, message(line))
        assertEquals("that was close", message(line).text)
    }

    @Test
    fun emotesBecomeSegmentsInMessageOrder() {
        val line = capturedPrivmsg("Kappa Keepo Kappa", emotes = "25:0-4,12-16/1902:6-10")
        val expected = listOf(emote("Kappa", "25"), text(" "), emote("Keepo", "1902"), text(" "), emote("Kappa", "25"))
        assertEquals(expected, message(line).segments)
    }

    @Test
    fun emoteIdsWithTheV2PrefixAreKept() {
        val id = "emotesv2_bea88a2caa044e409c1b32ee0850b825"
        val line = capturedPrivmsg("YappingDuck", emotes = "$id:0-10")
        assertEquals(listOf(emote("YappingDuck", id)), message(line).segments)
    }

    @Test
    fun emotePositionsCountCodePointsNotUtf16Units() {
        val beforeEmote = capturedPrivmsg("🍺 OhMyDog MAN WHAT", emotes = "81103:2-8")
        assertEquals(
            listOf(text("🍺 "), emote("OhMyDog", "81103"), text(" MAN WHAT")),
            message(beforeEmote).segments,
        )
        val betweenEmotes = capturedPrivmsg("Kappa 😀 Keepo 😀", emotes = "25:0-4/1902:8-12")
        assertEquals(
            listOf(emote("Kappa", "25"), text(" 😀 "), emote("Keepo", "1902"), text(" 😀")),
            message(betweenEmotes).segments,
        )
    }

    @Test
    fun actionMessageDropsTheWrapperAndCountsEmotesFromTheStrippedText() {
        val action = message(capturedPrivmsg("\u0001ACTION waves Kappa\u0001", emotes = "25:6-10"))
        assertTrue(action.isAction)
        assertEquals(listOf(text("waves "), emote("Kappa", "25")), action.segments)
        assertFalse(message(capturedPrivmsg("waves")).isAction)
    }

    @Test
    fun colorIsRgbOrNull() {
        val cases = mapOf(
            "#FF0000" to 0xFF0000,
            "#00ff7f" to 0x00FF7F,
            "" to null,
            "red" to null,
            "#12345" to null,
            "#12345G" to null,
        )
        for ((tag, expected) in cases) {
            assertEquals(expected, message(capturedPrivmsg("hi", color = tag)).userColor, "color=$tag")
        }
    }

    @Test
    fun missingDisplayNameFallsBackToTheLogin() {
        assertEquals("foo_bar", message(capturedPrivmsg("hi", displayName = "")).user)
    }

    @Test
    fun minimalLineStillBecomesAMessageStampedWithTheLocalClock() {
        val line = "@display-name=Foo_Bar :foo_bar!foo_bar@foo_bar.tmi.twitch.tv PRIVMSG #somechan :hi"
        val expected = ChatMessage(ChatPlatform.TWITCH, null, null, "Foo_Bar", null, listOf(text("hi")), NOW_MS)
        assertEquals(expected, message(line))
    }

    @Test
    fun badgesAndFlagsSetTheRoles() {
        class Case(val badges: String, val mod: String, val subscriber: String, val roles: String)

        val cases = listOf(
            Case("broadcaster/1", "0", "0", "broadcaster"),
            Case("moderator/1,bot-badge/1", "1", "0", "moderator"),
            Case("subscriber/12,bits/100", "0", "1", "subscriber"),
            Case("founder/0", "0", "1", "subscriber"),
            Case("broadcaster/1,subscriber/0", "0", "0", "broadcaster,subscriber"),
            Case("vip/1,twitch-recap-2023/1", "0", "0", ""),
            Case("", "0", "0", ""),
        )
        for (case in cases) {
            val parsed = message(capturedPrivmsg("hi", badges = case.badges, mod = case.mod, subscriber = case.subscriber))
            val roles = listOfNotNull(
                "broadcaster".takeIf { parsed.isBroadcaster },
                "moderator".takeIf { parsed.isModerator },
                "subscriber".takeIf { parsed.isSubscriber },
            ).joinToString(",")
            assertEquals(case.roles, roles, case.badges)
        }
    }

    @Test
    fun escapedTagValuesAreDecoded() {
        val cases = listOf(
            """a\sb\:c\\d\ne\rf""" to "a b;c\\d\ne\rf",
            """trailing\""" to "trailing",
            """unknown\qescape""" to "unknownqescape",
        )
        for ((escaped, expected) in cases) {
            assertEquals(expected, message(capturedPrivmsg("hi", displayName = escaped)).user, escaped)
        }
    }

    @Test
    fun clearMsgNamesTheDeletedMessage() {
        val line = "@login=foo_bar;room-id=;target-msg-id=5f4e8d2a-1c3b-4a6e-9f0d-7b2c8e1a3d5f;" +
            "tmi-sent-ts=1794281930123 :tmi.twitch.tv CLEARMSG #somechan :that was close"
        assertEquals(TwitchEvent.MessageDeleted("5f4e8d2a-1c3b-4a6e-9f0d-7b2c8e1a3d5f"), event(line))
    }

    @Test
    fun clearChatNamesTheBannedUserButAChatWipeIsIgnored() {
        val timeout = "@ban-duration=604800;room-id=12345678;target-user-id=24681357;tmi-sent-ts=1794282254831 " +
            ":tmi.twitch.tv CLEARCHAT #somechan :some_user"
        val wipe = "@room-id=12345678;tmi-sent-ts=1794282254831 :tmi.twitch.tv CLEARCHAT #somechan"
        assertEquals(TwitchEvent.UserMessagesDeleted("24681357"), event(timeout))
        assertNull(event(wipe))
    }

    @Test
    fun serverCommandsBecomeEvents() {
        val roomState = "@emote-only=0;followers-only=10;r9k=0;room-id=12345678;slow=0;subs-only=0 " +
            ":tmi.twitch.tv ROOMSTATE #somechan"
        val cases = listOf(
            "PING :tmi.twitch.tv" to TwitchEvent.Ping("tmi.twitch.tv"),
            ":tmi.twitch.tv RECONNECT" to TwitchEvent.Reconnect,
            roomState to TwitchEvent.Joined("12345678"),
            ":justinfan48214.tmi.twitch.tv 366 justinfan48214 #somechan :End of /NAMES list" to TwitchEvent.Joined(null),
            ":tmi.twitch.tv 001 justinfan48214 :Welcome, GLHF!" to null,
            ":tmi.twitch.tv CAP * ACK :twitch.tv/tags twitch.tv/commands" to null,
            "@msg-id=raid;room-id=12345678 :tmi.twitch.tv USERNOTICE #somechan" to null,
        )
        for ((line, expected) in cases) {
            assertEquals(expected, event(line), line)
        }
    }

    @Test
    fun brokenEmotePositionsAreSkippedWithoutLosingTheMessage() {
        val kappa = emote("Kappa", "25")
        val cases = listOf(
            "25:50-54" to listOf(text("Kappa Keepo")),
            "25:3-1" to listOf(text("Kappa Keepo")),
            "25:a-b" to listOf(text("Kappa Keepo")),
            "25:0-4,2-6" to listOf(kappa, text(" Keepo")),
            "no-colon" to listOf(text("Kappa Keepo")),
            "bad!id:0-4" to listOf(text("Kappa Keepo")),
            "25:0-4/1902:6-99" to listOf(kappa, text(" Keepo")),
        )
        for ((tag, expected) in cases) {
            assertEquals(expected, message(capturedPrivmsg("Kappa Keepo", emotes = tag)).segments, tag)
        }
    }

    @Test
    fun linesWithoutACommandAreIgnored() {
        for (line in listOf("", "@only=tags", ":only.a.source", "@only=tags :source")) {
            assertNull(parseIrcLine(line), line)
        }
    }
}
