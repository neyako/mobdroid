package com.opirl.app.chat

import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.StreamApi
import com.opirl.app.engine.chat.kick.KickApi
import com.opirl.app.engine.chat.kick.KickLogin
import com.opirl.app.engine.chat.tts.ElevenLabs
import com.opirl.app.engine.chat.tts.TtsMonster
import com.opirl.app.engine.chat.tts.VoiceService
import com.opirl.app.engine.chat.twitch.TwitchApi
import com.opirl.app.engine.chat.twitch.TwitchLogin
import com.opirl.app.engine.chat.youtube.YouTubeApi
import com.opirl.app.settings.Logins
import com.opirl.app.settings.SpeechProvider
import com.opirl.app.settings.StreamProfile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

fun managedPlatforms(profile: StreamProfile, logins: Logins): List<ChatPlatform> = buildList {
    val twitch = logins.twitch
    if (twitch != null && (profile.twitchChannel.isBlank() || profile.twitchChannel.equals(twitch.login, ignoreCase = true))) {
        add(ChatPlatform.TWITCH)
    }
    if (logins.youtube) add(ChatPlatform.YOUTUBE)
    if (logins.kick != null && profile.kickChannel.isNotBlank()) add(ChatPlatform.KICK)
}

fun voiceService(http: OkHttpClient, provider: SpeechProvider, logins: Logins): VoiceService? = when (provider) {
    SpeechProvider.SYSTEM -> null
    SpeechProvider.ELEVENLABS -> logins.elevenLabsKey.trim().takeIf { it.isNotEmpty() }?.let { ElevenLabs(http, it) }
    SpeechProvider.TTS_MONSTER -> logins.ttsMonsterToken.trim().takeIf { it.isNotEmpty() }?.let { TtsMonster(http, it) }
}

class Platforms(
    private val http: OkHttpClient,
    private val profile: () -> StreamProfile,
    private val logins: () -> Logins,
    private val youtube: YouTubeAuth,
) {
    private var kick: Pair<List<String>, KickApi>? = null

    fun available(): List<ChatPlatform> = managedPlatforms(profile(), logins())

    suspend fun <T> call(platform: ChatPlatform, block: (StreamApi) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block(api(platform)))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private suspend fun api(platform: ChatPlatform): StreamApi {
        if (platform !in available()) throw ApiException("Not logged in")
        return when (platform) {
            ChatPlatform.TWITCH -> logins().twitch!!.let { TwitchApi(http, TwitchLogin(it.login, it.accessToken, TWITCH_CLIENT_ID, it.userId)) }
            ChatPlatform.KICK -> kick(logins().kick!!.sessionToken, profile().kickChannel)
            ChatPlatform.YOUTUBE -> YouTubeApi(http, youtube.authorize()?.token ?: throw ApiException("YouTube login expired"))
        }
    }

    @Synchronized
    private fun kick(token: String, channel: String): KickApi {
        val key = listOf(token, channel)
        kick?.takeIf { it.first == key }?.let { return it.second }
        return KickApi(http, KickLogin(token), channel).also { kick = key to it }
    }
}
