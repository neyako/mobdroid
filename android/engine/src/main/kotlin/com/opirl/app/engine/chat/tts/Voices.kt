package com.opirl.app.engine.chat.tts

import com.opirl.app.engine.chat.ApiClient
import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.objects
import com.opirl.app.engine.chat.string
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

data class Voice(val id: String, val name: String)

interface VoiceService {
    fun voices(): List<Voice>

    fun speech(voiceId: String, text: String): ByteArray
}

private fun voices(list: List<JsonObject>): List<Voice> = list.mapNotNull {
    Voice(it.string("voice_id") ?: return@mapNotNull null, it.string("name").orEmpty())
}

class ElevenLabs internal constructor(http: OkHttpClient, apiKey: String, baseUrl: String) : VoiceService {
    constructor(http: OkHttpClient, apiKey: String) : this(http, apiKey, "https://api.elevenlabs.io/v1")

    private val api = ApiClient(http, "ElevenLabs", baseUrl, mapOf("xi-api-key" to apiKey), "ElevenLabs API key was not accepted")

    override fun voices(): List<Voice> = voices(api.call("GET", "voices")?.objects("voices").orEmpty())

    override fun speech(voiceId: String, text: String): ByteArray =
        api.bytes("POST", "text-to-speech", voiceId, body = buildJsonObject { put("text", text) }, accept = "audio/mpeg")
}

class TtsMonster internal constructor(private val http: OkHttpClient, apiToken: String, baseUrl: String) : VoiceService {
    constructor(http: OkHttpClient, apiToken: String) : this(http, apiToken, "https://api.console.tts.monster")

    private val api = ApiClient(http, "TTS Monster", baseUrl, mapOf("Authorization" to apiToken), "TTS Monster API key was not accepted")

    override fun voices(): List<Voice> {
        val response = api.call("POST", "voices")
        return voices(response?.objects("customVoices").orEmpty() + response?.objects("voices").orEmpty())
    }

    override fun speech(voiceId: String, text: String): ByteArray {
        val body = buildJsonObject {
            put("voice_id", voiceId)
            put("message", text)
        }
        val url = api.call("POST", "generate", body = body)?.string("url") ?: throw ApiException("TTS Monster: no audio")
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiException("TTS Monster error ${response.code}")
                response.body.bytes()
            }
        } catch (e: ApiException) {
            throw e
        } catch (_: IOException) {
            throw ApiException("Cannot reach TTS Monster")
        } catch (_: IllegalArgumentException) {
            throw ApiException("TTS Monster: no audio")
        }
    }
}
