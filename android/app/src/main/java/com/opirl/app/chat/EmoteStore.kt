package com.opirl.app.chat

import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.emotes.EmoteFetcher
import com.opirl.app.engine.chat.emotes.EmoteProvider
import com.opirl.app.engine.chat.emotes.applyEmotes
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class EmoteStore(private val scope: CoroutineScope, http: OkHttpClient) {
    private val fetcher = EmoteFetcher(http)
    private val global = HashMap<EmoteProvider, Map<String, String>>()
    private val channel = HashMap<Triple<EmoteProvider, ChatPlatform, String>, Map<String, String>>()
    private var providers = emptySet<EmoteProvider>()
    private var channels = emptyMap<ChatPlatform, String>()
    private var job: Job? = null

    @Volatile
    private var shared = emptyMap<String, String>()

    @Volatile
    private var perPlatform = emptyMap<ChatPlatform, Map<String, String>>()

    @Synchronized
    fun start(enabled: Set<EmoteProvider>) {
        providers = enabled
        channels = emptyMap()
        refresh()
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @Synchronized
    fun setChannel(platform: ChatPlatform, id: String) {
        if (channels[platform] == id) return
        channels = channels + (platform to id)
        refresh()
    }

    fun apply(message: ChatMessage): ChatMessage {
        val emotes = perPlatform[message.platform] ?: shared
        val segments = applyEmotes(message.segments, emotes)
        return if (segments === message.segments) message else message.copy(segments = segments)
    }

    private fun refresh() {
        val wanted = providers
        val ids = channels
        publish(wanted, ids)
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            var wait = 30_000L
            while (true) {
                var failed = false
                for (provider in wanted) {
                    failed = !load(global, provider) { fetcher.global(provider) } || failed
                    for ((platform, id) in ids) {
                        val key = Triple(provider, platform, id)
                        failed = !load(channel, key) { fetcher.channel(provider, platform, id) } || failed
                    }
                }
                synchronized(this@EmoteStore) { if (providers == wanted && channels == ids) publish(wanted, ids) }
                if (!failed) return@launch
                delay(wait)
                wait = minOf(wait * 2, 3_600_000L)
            }
        }
    }

    private fun <K> load(cache: HashMap<K, Map<String, String>>, key: K, fetch: () -> Map<String, String>): Boolean {
        if (synchronized(this) { key in cache }) return true
        val emotes = try {
            fetch()
        } catch (_: IOException) {
            return false
        }
        synchronized(this) { cache[key] = emotes }
        return true
    }

    private fun publish(wanted: Set<EmoteProvider>, ids: Map<ChatPlatform, String>) {
        val all = HashMap<String, String>()
        wanted.forEach { all += global[it].orEmpty() }
        shared = all
        perPlatform = ids.mapValues { (platform, id) ->
            HashMap(all).also { emotes -> wanted.forEach { emotes += channel[Triple(it, platform, id)].orEmpty() } }
        }
    }
}
