package com.opirl.app.chat

import com.opirl.app.engine.chat.ChatClient
import com.opirl.app.engine.chat.ChatListener
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.kick.KickChatClient
import com.opirl.app.engine.chat.kick.KickLogin
import com.opirl.app.engine.chat.twitch.TwitchChatClient
import com.opirl.app.engine.chat.twitch.TwitchLogin
import com.opirl.app.engine.chat.youtube.YouTubeChatClient
import com.opirl.app.settings.Logins
import com.opirl.app.settings.StreamProfile
import okhttp3.OkHttpClient

fun createChatClients(
    profile: StreamProfile,
    logins: Logins,
    http: OkHttpClient,
    listener: (ChatPlatform) -> ChatListener,
): List<Pair<ChatPlatform, ChatClient>> = buildList {
    val create = { platform: ChatPlatform, channel: String, client: (ChatListener) -> ChatClient ->
        if (channel.isNotBlank()) runCatching { client(listener(platform)) }.onSuccess { add(platform to it) }
    }
    create(ChatPlatform.TWITCH, profile.twitchChannel) {
        val login = logins.twitch?.let { TwitchLogin(it.login, it.accessToken, TWITCH_CLIENT_ID, it.userId) }
        TwitchChatClient(profile.twitchChannel, login, it, http)
    }
    create(ChatPlatform.YOUTUBE, profile.youtubeChannel) { YouTubeChatClient(profile.youtubeChannel, it, http) }
    create(ChatPlatform.KICK, profile.kickChannel) {
        KickChatClient(profile.kickChannel, logins.kick?.let { KickLogin(it.sessionToken) }, it, http)
    }
}
