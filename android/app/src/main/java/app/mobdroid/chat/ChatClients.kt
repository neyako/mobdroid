package app.mobdroid.chat

import app.mobdroid.engine.chat.ChatClient
import app.mobdroid.engine.chat.ChatListener
import app.mobdroid.engine.chat.ChatPlatform
import app.mobdroid.settings.StreamProfile
import okhttp3.OkHttpClient

fun createChatClients(
    profile: StreamProfile,
    http: OkHttpClient,
    listener: (ChatPlatform) -> ChatListener,
): List<Pair<ChatPlatform, ChatClient>> = emptyList()
