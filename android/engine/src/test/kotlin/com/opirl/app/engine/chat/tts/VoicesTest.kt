package com.opirl.app.engine.chat.tts

import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ApiServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoicesTest {
    private val server = ApiServer()

    @AfterTest
    fun close() = server.close()

    @Test
    fun elevenLabsListsVoicesAndMakesSpeech() {
        val api = ElevenLabs(server.http, "key-1", server.url("/v1"))
        server.reply("""{"voices":[{"voice_id":"v1","name":"Rachel","verified_languages":[]},{"name":"no id"}]}""")
        assertEquals(listOf(Voice("v1", "Rachel")), api.voices())
        val list = server.take()
        assertEquals("GET /v1/voices", "${list.method} ${list.target}")
        assertEquals("key-1", list.headers["xi-api-key"])
        server.reply("mp3-bytes")
        assertContentEquals("mp3-bytes".toByteArray(), api.speech("v1", "xin chào"))
        val speech = server.take()
        assertEquals("POST /v1/text-to-speech/v1", "${speech.method} ${speech.target}")
        assertEquals("audio/mpeg", speech.headers["Accept"])
        assertEquals("""{"text":"xin chào"}""", speech.body?.utf8())
    }

    @Test
    fun ttsMonsterListsVoicesAndDownloadsTheSpeech() {
        val api = TtsMonster(server.http, "token-1", server.url())
        server.reply("""{"voices":[{"voice_id":"v1","name":"Alpha","language":"en"}],"customVoices":[{"voice_id":"c1","name":"Mine"}]}""")
        assertEquals(listOf(Voice("c1", "Mine"), Voice("v1", "Alpha")), api.voices())
        val list = server.take()
        assertEquals("POST /voices", "${list.method} ${list.target}")
        assertEquals("token-1", list.headers["Authorization"])
        server.reply("""{"status":200,"url":"${server.url("/files/a.wav")}"}""")
        server.reply("wav-bytes")
        assertContentEquals("wav-bytes".toByteArray(), api.speech("c1", "hello"))
        assertEquals("""POST /generate {"voice_id":"c1","message":"hello"}""", server.request())
        assertEquals("GET /files/a.wav", server.request())
    }

    @Test
    fun reportsWhyItFailed() {
        server.reply("""{"detail":{"status":"invalid_api_key"}}""", 401)
        assertEquals("ElevenLabs API key was not accepted", assertFailsWith<ApiException> { ElevenLabs(server.http, "bad", server.url()).voices() }.message)
        server.reply("""{"detail":{"type":"authentication_error","code":"invalid_api_key","message":"API key must start with 'sk_'."}}""", 400)
        assertEquals(
            "ElevenLabs: API key must start with 'sk_'.",
            assertFailsWith<ApiException> { ElevenLabs(server.http, "bad", server.url()).voices() }.message,
        )
        server.reply("""{"error":"Error processing request"}""", 500)
        assertEquals(
            "TTS Monster: Error processing request",
            assertFailsWith<ApiException> { TtsMonster(server.http, "bad", server.url()).voices() }.message,
        )
        server.reply("""{"status":200}""")
        assertEquals("TTS Monster: no audio", assertFailsWith<ApiException> { TtsMonster(server.http, "t", server.url()).speech("v", "x") }.message)
    }
}
