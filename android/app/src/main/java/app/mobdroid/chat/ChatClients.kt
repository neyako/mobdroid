package app.mobdroid.chat

import app.mobdroid.engine.chat.ChatClient
import app.mobdroid.engine.chat.ChatListener
import app.mobdroid.engine.chat.ChatPlatform
import app.mobdroid.engine.chat.kick.KickChatClient
import app.mobdroid.engine.chat.kick.KickLogin
import app.mobdroid.engine.chat.twitch.TwitchChatClient
import app.mobdroid.engine.chat.twitch.TwitchLogin
import app.mobdroid.engine.chat.youtube.YouTubeChatClient
import app.mobdroid.settings.Logins
import app.mobdroid.settings.StreamProfile
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
