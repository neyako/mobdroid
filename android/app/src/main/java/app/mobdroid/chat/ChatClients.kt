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
    if (profile.twitchChannel.isNotBlank()) {
        val login = logins.twitch?.let { TwitchLogin(it.login, it.accessToken, TWITCH_CLIENT_ID, it.userId) }
        add(ChatPlatform.TWITCH to TwitchChatClient(profile.twitchChannel, login, listener(ChatPlatform.TWITCH), http))
    }
    if (profile.youtubeChannel.isNotBlank()) {
        add(ChatPlatform.YOUTUBE to YouTubeChatClient(profile.youtubeChannel, listener(ChatPlatform.YOUTUBE), http))
    }
    if (profile.kickChannel.isNotBlank()) {
        val login = logins.kick?.let { KickLogin(it.sessionToken) }
        add(ChatPlatform.KICK to KickChatClient(profile.kickChannel, login, listener(ChatPlatform.KICK), http))
    }
}
