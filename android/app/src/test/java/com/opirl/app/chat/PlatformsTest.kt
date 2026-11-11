package com.opirl.app.chat

import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import com.opirl.app.engine.chat.bot.BotCommand
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.BotPermissionSettings
import com.opirl.app.settings.KickAccount
import com.opirl.app.settings.Logins
import com.opirl.app.settings.StreamProfile
import com.opirl.app.settings.TwitchAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformsTest {
    private val logins = Logins(TwitchAccount("Streamer", "42", "token"), KickAccount("streamer", "session"), youtube = true)

    @Test
    fun `only the streamer's own channels are managed`() {
        assertEquals(emptyList(), managedPlatforms(StreamProfile(twitchChannel = "streamer", kickChannel = "streamer"), Logins()))
        assertEquals(
            listOf(ChatPlatform.TWITCH, ChatPlatform.YOUTUBE, ChatPlatform.KICK),
            managedPlatforms(StreamProfile(twitchChannel = "streamer", kickChannel = "streamer"), logins),
        )
        assertEquals(listOf(ChatPlatform.TWITCH, ChatPlatform.YOUTUBE), managedPlatforms(StreamProfile(), logins))
        assertEquals(listOf(ChatPlatform.YOUTUBE), managedPlatforms(StreamProfile(twitchChannel = "someone_else"), logins))
    }

    @Test
    fun `commands and links are not read aloud`() {
        fun message(text: String) = ChatMessage(ChatPlatform.TWITCH, null, null, "viewer", null, listOf(ChatSegment.Text(text)), 0)
        assertEquals("viewer said hello there", spokenText(message("hello https://example.com there"), true))
        assertNull(spokenText(message("!opirl snapshot"), true))
    }

    @Test
    fun `scene and stream commands are owner only until moderators are allowed`() {
        val settings = AppSettings(chatBotPermissions = mapOf(BotCommand.SCENE to BotPermissionSettings(others = true)))
        assertEquals(
            listOf(false, true, true),
            listOf(BotCommand.STREAM, BotCommand.SNAPSHOT, BotCommand.SCENE).map { settings.botPermission(it).moderators },
        )
        assertEquals(true, settings.botPermission(BotCommand.SCENE).others)
    }
}
