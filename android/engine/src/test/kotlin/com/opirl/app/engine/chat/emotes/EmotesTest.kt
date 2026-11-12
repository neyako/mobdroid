package com.opirl.app.engine.chat.emotes

import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ApiServer
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private const val BTTV_GLOBAL =
    """[{"id":"54fa8f1401e468494b85b537","code":":tf:","imageType":"png","animated":false,"userId":"5561169bd6b9d206222a8c19","modifier":false},
    {"id":"54fa8fce01e468494b85b53c","code":"CiGrip","imageType":"png","animated":false}]"""

private const val BTTV_CHANNEL =
    """{"id":"5717c985b017b9d058fad265","bots":["xqcbot"],"channelEmotes":[{"id":"c1","code":"Mine"}],"sharedEmotes":[{"id":"s1","code":"Shared"}]}"""

private const val FFZ_GLOBAL =
    """[{"id":9,"user":{"id":2,"name":"dansalvato"},"code":"ZrehplaR","images":{"1x":"https://cdn.betterttv.net/frankerfacez_emote/9/1",
    "2x":"https://cdn.betterttv.net/frankerfacez_emote/9/2","4x":"https://cdn.betterttv.net/frankerfacez_emote/9/4"},"imageType":"png"},
    {"id":6,"code":"OnlySmall","images":{"1x":"https://cdn.betterttv.net/frankerfacez_emote/6/1","2x":null,"4x":null}}]"""

private const val SEVENTV_USER =
    """{"id":"676","platform":"KICK","username":"xqc","emote_set":{"id":"01FE9DRF000009TR6M9N941CYW","emotes":[
    {"id":"01G3WEGZN0000ET2J0MQP5YJ0G","name":"GAMBA","data":{"animated":true,"host":{"url":"//cdn.7tv.app/emote/01G3WEGZN0000ET2J0MQP5YJ0G",
    "files":[{"name":"1x.webp","static_name":"1x_static.webp"},{"name":"2x.webp","static_name":"2x_static.webp"},{"name":"2x.avif"}]}}},
    {"id":"x","name":"Plain","data":{"host":{"url":"//cdn.7tv.app/emote/x","files":[{"name":"1x.avif"},{"name":"1x.webp"}]}}},
    {"id":"y","name":"NoFiles","data":{"host":{"url":"//cdn.7tv.app/emote/y","files":[]}}}]}}"""

class EmotesTest {
    private val server = ApiServer()
    private val fetcher = EmoteFetcher(server.http, server.url("/bttv"), server.url("/7tv"))

    @AfterTest
    fun close() = server.close()

    @Test
    fun replacesWholeWordsAndKeepsTheSpacing() {
        val emotes = mapOf("KEKW" to "u1", "catJAM" to "u2")
        val segments = listOf(
            ChatSegment.Text("lol KEKW  that KEKWait catJAM"),
            ChatSegment.Emote("Kappa", "native"),
            ChatSegment.Text(" catJAM"),
        )
        assertEquals(
            listOf(
                ChatSegment.Text("lol "),
                ChatSegment.Emote("KEKW", "u1"),
                ChatSegment.Text("  that KEKWait "),
                ChatSegment.Emote("catJAM", "u2"),
                ChatSegment.Emote("Kappa", "native"),
                ChatSegment.Text(" "),
                ChatSegment.Emote("catJAM", "u2"),
            ),
            applyEmotes(segments, emotes),
        )
    }

    @Test
    fun leavesAMessageWithoutEmotesAlone() {
        val segments = listOf(ChatSegment.Text("kekw is not KEKW!"))
        assertSame(segments, applyEmotes(segments, emptyMap()))
        assertEquals(segments, applyEmotes(segments, mapOf("KEKW" to "u1")))
    }

    @Test
    fun fetchesTheGlobalSets() {
        server.reply(BTTV_GLOBAL)
        assertEquals(
            mapOf(
                ":tf:" to "https://cdn.betterttv.net/emote/54fa8f1401e468494b85b537/2x",
                "CiGrip" to "https://cdn.betterttv.net/emote/54fa8fce01e468494b85b53c/2x",
            ),
            fetcher.global(EmoteProvider.BTTV),
        )
        assertEquals("GET /bttv/emotes/global", server.request())
        server.reply(FFZ_GLOBAL)
        assertEquals(
            mapOf(
                "ZrehplaR" to "https://cdn.betterttv.net/frankerfacez_emote/9/2",
                "OnlySmall" to "https://cdn.betterttv.net/frankerfacez_emote/6/1",
            ),
            fetcher.global(EmoteProvider.FFZ),
        )
        assertEquals("GET /bttv/frankerfacez/emotes/global", server.request())
        server.reply("""{"id":"global","emotes":[{"name":"RainTime","data":{"host":{"url":"//cdn.7tv.app/emote/r","files":[{"name":"2x.webp"}]}}}]}""")
        assertEquals(mapOf("RainTime" to "https://cdn.7tv.app/emote/r/2x.webp"), fetcher.global(EmoteProvider.SEVENTV))
        assertEquals("GET /7tv/emote-sets/global", server.request())
    }

    @Test
    fun fetchesTheChannelSetsAsStillImages() {
        server.reply(BTTV_CHANNEL)
        assertEquals(
            mapOf("Shared" to "https://cdn.betterttv.net/emote/s1/2x", "Mine" to "https://cdn.betterttv.net/emote/c1/2x"),
            fetcher.channel(EmoteProvider.BTTV, ChatPlatform.TWITCH, "71092938"),
        )
        assertEquals("GET /bttv/users/twitch/71092938", server.request())
        server.reply("[]")
        assertEquals(emptyMap(), fetcher.channel(EmoteProvider.FFZ, ChatPlatform.TWITCH, "71092938"))
        assertEquals("GET /bttv/frankerfacez/users/twitch/71092938", server.request())
        server.reply(SEVENTV_USER)
        assertEquals(
            mapOf(
                "GAMBA" to "https://cdn.7tv.app/emote/01G3WEGZN0000ET2J0MQP5YJ0G/2x_static.webp",
                "Plain" to "https://cdn.7tv.app/emote/x/1x.webp",
            ),
            fetcher.channel(EmoteProvider.SEVENTV, ChatPlatform.KICK, "676"),
        )
        assertEquals("GET /7tv/users/kick/676", server.request())
    }

    @Test
    fun aChannelWithoutEmotesIsEmptyAndAnOutageIsAnError() {
        server.reply("""{"status":"Not Found","error_code":12000,"error":"user not found"}""", 404)
        assertEquals(emptyMap(), fetcher.channel(EmoteProvider.SEVENTV, ChatPlatform.KICK, "668"))
        server.reply("oops", 503)
        assertFailsWith<ApiException> { fetcher.channel(EmoteProvider.BTTV, ChatPlatform.TWITCH, "1") }
    }
}
