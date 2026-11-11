package com.opirl.app.engine.chat.youtube

import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val SHORTLINK = Regex("""shortlinkUrl[^>]*href=["']https://youtu\.be/([^"']+)""")
private val CONTINUATION = Regex(""""continuation":"([^"]+)"""")
private val CONTINUATION_KINDS = listOf("invalidationContinuationData", "timedContinuationData")

internal sealed interface YouTubeTarget {
    data class Video(val id: String) : YouTubeTarget

    data class Channel(val handle: String) : YouTubeTarget
}

internal sealed interface LiveChatEvent {
    data class Message(val message: ChatMessage) : LiveChatEvent

    data class MessageDeleted(val messageId: String) : LiveChatEvent

    data class UserMessagesDeleted(val userId: String) : LiveChatEvent
}

internal class LiveChatPage(val events: List<LiveChatEvent>, val continuation: String?)

internal fun parseTarget(source: String): YouTubeTarget {
    val text = source.trim()
    require(text.isNotEmpty() && text != "@") { "Empty YouTube source" }
    return when {
        text.startsWith('@') -> YouTubeTarget.Channel(text)
        VIDEO_ID.matches(text) -> YouTubeTarget.Video(text)
        else -> YouTubeTarget.Channel("@$text")
    }
}

internal fun findVideoId(html: String): String? = SHORTLINK.find(html)?.groupValues?.get(1)

internal fun findContinuation(html: String): String? {
    val raw = CONTINUATION.find(html)?.groupValues?.get(1) ?: return null
    return if ('\\' in raw) unescapeJson(raw) else raw
}

internal fun nextPollDelayMs(currentMs: Long, messages: Int, timings: YouTubeChatTimings): Long {
    val next = if (messages > 0) currentMs * 5 / messages else timings.maxPollMs
    return next.coerceIn(timings.minPollMs, timings.maxPollMs)
}

internal fun parseLiveChat(body: String, nowMs: Long): LiveChatPage {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: throw SerializationException("Not a JSON object")
    val chat = root.obj("continuationContents")?.obj("liveChatContinuation")
        ?: return LiveChatPage(emptyList(), null)
    val events = ArrayList<LiveChatEvent>()
    chat.array("actions")?.forEach { action ->
        if (action is JsonObject) {
            parseAction(action, nowMs, events)
        }
    }
    val continuation = chat.array("continuations")?.firstNotNullOfOrNull { continuationOf(it as? JsonObject) }
    return LiveChatPage(events, continuation)
}

private fun continuationOf(entry: JsonObject?): String? {
    if (entry == null) {
        return null
    }
    for (kind in CONTINUATION_KINDS) {
        entry.obj(kind)?.string("continuation")?.let { return it }
    }
    return null
}

private fun parseAction(action: JsonObject, nowMs: Long, out: MutableList<LiveChatEvent>) {
    action.obj("addChatItemAction")?.obj("item")?.let { parseItem(it, nowMs) }?.let {
        out += LiveChatEvent.Message(it)
    }
    (action.obj("markChatItemAsDeletedAction") ?: action.obj("removeChatItemAction"))
        ?.string("targetItemId")
        ?.let { out += LiveChatEvent.MessageDeleted(it) }
    (action.obj("markChatItemsByAuthorAsDeletedAction") ?: action.obj("removeChatItemByAuthorAction"))
        ?.string("externalChannelId")
        ?.let { out += LiveChatEvent.UserMessagesDeleted(it) }
}

private fun parseItem(item: JsonObject, nowMs: Long): ChatMessage? {
    for ((kind, value) in item) {
        val renderer = value as? JsonObject ?: continue
        val message = when (kind) {
            "liveChatTextMessageRenderer",
            "liveChatMembershipItemRenderer",
            "liveChatSponsorshipsGiftRedemptionAnnouncementRenderer",
            -> chatMessage(renderer, null, nowMs)
            "liveChatPaidMessageRenderer" -> chatMessage(renderer, purchaseText(renderer, "Super Chat"), nowMs)
            "liveChatPaidStickerRenderer" -> chatMessage(renderer, purchaseText(renderer, "Super Sticker"), nowMs)
            "liveChatSponsorshipsGiftPurchaseAnnouncementRenderer" -> giftPurchase(renderer, nowMs)
            "giftMessageViewModel" -> jewels(renderer, nowMs)
            else -> null
        }
        if (message != null) {
            return message
        }
    }
    return null
}

private fun purchaseText(renderer: JsonObject, product: String): String {
    val amount = renderer.obj("purchaseAmountText")?.string("simpleText")
    return if (amount != null) "sent a $amount $product!" else "sent a $product!"
}

private fun chatMessage(renderer: JsonObject, prefix: String?, nowMs: Long): ChatMessage? {
    val segments = Segments()
    prefix?.let(segments::text)
    segments.gap()
    segments.runs(renderer.obj("headerSubtext"))
    segments.gap()
    segments.runs(renderer.obj("message"))
    return makeMessage(
        id = renderer.string("id"),
        userId = renderer.string("authorExternalChannelId"),
        user = renderer.obj("authorName")?.string("simpleText"),
        badges = renderer.array("authorBadges"),
        timestampUsec = renderer.long("timestampUsec"),
        segments = segments.build(),
        nowMs = nowMs,
    )
}

private fun giftPurchase(renderer: JsonObject, nowMs: Long): ChatMessage? {
    val header = renderer.obj("header")?.obj("liveChatSponsorshipsHeaderRenderer") ?: return null
    val segments = Segments()
    segments.runs(header.obj("primaryText"))
    return makeMessage(
        id = renderer.string("id"),
        userId = header.string("authorExternalChannelId") ?: renderer.string("authorExternalChannelId"),
        user = header.obj("authorName")?.string("simpleText"),
        badges = header.array("authorBadges"),
        timestampUsec = renderer.long("timestampUsec"),
        segments = segments.build(),
        nowMs = nowMs,
    )
}

private fun jewels(viewModel: JsonObject, nowMs: Long): ChatMessage? {
    val segments = Segments()
    viewModel.obj("text")?.string("content")?.let(segments::text)
    return makeMessage(
        id = viewModel.string("id"),
        userId = null,
        user = viewModel.obj("authorName")?.string("content")?.trim(),
        badges = null,
        timestampUsec = null,
        segments = segments.build(),
        nowMs = nowMs,
    )
}

private fun makeMessage(
    id: String?,
    userId: String?,
    user: String?,
    badges: JsonArray?,
    timestampUsec: Long?,
    segments: List<ChatSegment>,
    nowMs: Long,
): ChatMessage? {
    if (user.isNullOrEmpty() || segments.all { it is ChatSegment.Text && it.text.isBlank() }) {
        return null
    }
    var owner = false
    var moderator = false
    var member = false
    badges?.forEach { entry ->
        val badge = (entry as? JsonObject)?.obj("liveChatAuthorBadgeRenderer") ?: return@forEach
        when (badge.obj("icon")?.string("iconType")) {
            "OWNER" -> owner = true
            "MODERATOR" -> moderator = true
            else -> if (badge.containsKey("customThumbnail")) member = true
        }
    }
    return ChatMessage(
        platform = ChatPlatform.YOUTUBE,
        id = id,
        userId = userId,
        user = user,
        userColor = null,
        segments = segments,
        timestampMs = timestampUsec?.div(1000) ?: nowMs,
        isBroadcaster = owner,
        isModerator = moderator,
        isSubscriber = member,
    )
}

private class Segments {
    private val segments = ArrayList<ChatSegment>()
    private val pending = StringBuilder()
    private var spaceBeforeNext = false

    fun gap() {
        if (segments.isNotEmpty() || pending.isNotEmpty()) {
            spaceBeforeNext = true
        }
    }

    fun text(value: String) {
        if (value.isNotEmpty()) {
            closeGap()
            pending.append(value)
        }
    }

    fun emote(name: String, url: String) {
        closeGap()
        flush()
        segments += ChatSegment.Emote(name, url)
    }

    fun runs(message: JsonObject?) {
        if (message == null) {
            return
        }
        val runs = message.array("runs")
        if (runs == null) {
            message.string("simpleText")?.let { text(it) }
            return
        }
        for (run in runs) {
            if (run is JsonObject) {
                run.string("text")?.let { text(it) }
                run.obj("emoji")?.let { emoji(it) }
            }
        }
    }

    fun build(): List<ChatSegment> {
        flush()
        return segments
    }

    private fun emoji(emoji: JsonObject) {
        val shortcut = emoji.array("shortcuts")?.firstNotNullOfOrNull { it.stringOrNull() }
        val emojiId = emoji.string("emojiId")
        val url = emoji.obj("image")?.array("thumbnails")?.let { largestThumbnailUrl(it) }
        if (url != null) {
            emote(shortcut?.trim(':')?.ifEmpty { null } ?: "emoji", url)
        } else {
            text(if (emojiId != null && '/' !in emojiId) emojiId else shortcut.orEmpty())
        }
    }

    private fun closeGap() {
        if (spaceBeforeNext) {
            pending.append(' ')
            spaceBeforeNext = false
        }
    }

    private fun flush() {
        if (pending.isNotEmpty()) {
            segments += ChatSegment.Text(pending.toString())
            pending.setLength(0)
        }
    }
}

private fun largestThumbnailUrl(thumbnails: JsonArray): String? {
    var best: String? = null
    var bestWidth = -1L
    for (thumbnail in thumbnails) {
        if (thumbnail !is JsonObject) {
            continue
        }
        val url = thumbnail.string("url") ?: continue
        val width = thumbnail.long("width") ?: 0L
        if (width > bestWidth) {
            best = if (url.startsWith("//")) "https:$url" else url
            bestWidth = width
        }
    }
    return best
}

private fun unescapeJson(raw: String): String =
    try {
        Json.parseToJsonElement("\"$raw\"").jsonPrimitive.content
    } catch (_: IllegalArgumentException) {
        raw
    }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.string(key: String): String? = this[key].stringOrNull()

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
