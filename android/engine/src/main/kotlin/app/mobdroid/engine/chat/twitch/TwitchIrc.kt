package app.mobdroid.engine.chat.twitch

import app.mobdroid.engine.chat.ChatMessage
import app.mobdroid.engine.chat.ChatPlatform
import app.mobdroid.engine.chat.ChatSegment

private const val ACTION_PREFIX = "\u0001ACTION "
private const val ACTION_END = '\u0001'
private const val EMOTE_URL = "https://static-cdn.jtvnw.net/emoticons/v2/"
private const val DEFAULT_PING_PAYLOAD = "tmi.twitch.tv"

internal class IrcLine(
    val tags: Map<String, String>,
    val source: String?,
    val command: String,
    val params: List<String>,
)

internal sealed interface TwitchEvent {
    data class Message(val message: ChatMessage) : TwitchEvent

    data class MessageDeleted(val messageId: String) : TwitchEvent

    data class UserMessagesDeleted(val userId: String) : TwitchEvent

    data class Ping(val payload: String) : TwitchEvent

    data class Joined(val roomId: String?) : TwitchEvent

    data object Reconnect : TwitchEvent
}

private class EmoteRange(val start: Int, val end: Int, val id: String)

internal fun parseIrcLine(line: String): IrcLine? {
    var pos = 0
    var tags: Map<String, String> = emptyMap()
    if (line.startsWith('@')) {
        val end = line.indexOf(' ')
        if (end < 0) {
            return null
        }
        tags = parseTags(line, 1, end)
        pos = skipSpaces(line, end)
    }
    var source: String? = null
    if (pos < line.length && line[pos] == ':') {
        val end = line.indexOf(' ', pos)
        if (end < 0) {
            return null
        }
        source = line.substring(pos + 1, end)
        pos = skipSpaces(line, end)
    }
    if (pos >= line.length) {
        return null
    }
    var commandEnd = line.indexOf(' ', pos)
    if (commandEnd < 0) {
        commandEnd = line.length
    }
    val command = line.substring(pos, commandEnd)
    val params = ArrayList<String>(2)
    pos = skipSpaces(line, commandEnd)
    while (pos < line.length) {
        if (line[pos] == ':') {
            params.add(line.substring(pos + 1))
            break
        }
        var end = line.indexOf(' ', pos)
        if (end < 0) {
            end = line.length
        }
        params.add(line.substring(pos, end))
        pos = skipSpaces(line, end)
    }
    return IrcLine(tags, source, command, params)
}

internal fun twitchEvent(line: IrcLine, nowMs: Long): TwitchEvent? = when (line.command) {
    "PRIVMSG" -> chatMessage(line, nowMs)?.let { TwitchEvent.Message(it) }
    "CLEARMSG" -> line.tags["target-msg-id"]?.let { TwitchEvent.MessageDeleted(it) }
    "CLEARCHAT" -> line.tags["target-user-id"]?.let { TwitchEvent.UserMessagesDeleted(it) }
    "PING" -> TwitchEvent.Ping(line.params.lastOrNull() ?: DEFAULT_PING_PAYLOAD)
    "ROOMSTATE" -> TwitchEvent.Joined(line.tags["room-id"])
    "366" -> TwitchEvent.Joined(null)
    "RECONNECT" -> TwitchEvent.Reconnect
    else -> null
}

private fun chatMessage(line: IrcLine, nowMs: Long): ChatMessage? {
    if (line.params.size < 2) {
        return null
    }
    val tags = line.tags
    val source = line.source
    val bang = source?.indexOf('!') ?: -1
    val login = if (source != null && bang > 0) source.substring(0, bang) else null
    val user = tags["display-name"] ?: login ?: return null
    var text = line.params[1]
    val isAction = text.startsWith(ACTION_PREFIX)
    if (isAction) {
        text = text.substring(ACTION_PREFIX.length)
        if (text.endsWith(ACTION_END)) {
            text = text.substring(0, text.length - 1)
        }
    }
    if (text.isEmpty()) {
        return null
    }
    val badges = tags["badges"]
    return ChatMessage(
        platform = ChatPlatform.TWITCH,
        id = tags["id"],
        userId = tags["user-id"],
        user = user,
        userColor = parseColor(tags["color"]),
        segments = createSegments(text, tags["emotes"]),
        timestampMs = tags["tmi-sent-ts"]?.toLongOrNull() ?: nowMs,
        isAction = isAction,
        isBroadcaster = hasBadge(badges, "broadcaster"),
        isModerator = tags["mod"] == "1" || hasBadge(badges, "moderator"),
        isSubscriber = tags["subscriber"] == "1" || hasBadge(badges, "subscriber"),
    )
}

private fun createSegments(text: String, emotesTag: String?): List<ChatSegment> {
    val emotes = if (emotesTag == null) emptyList() else parseEmotes(emotesTag)
    if (emotes.isEmpty()) {
        return listOf(ChatSegment.Text(text))
    }
    val codePoints = text.codePointCount(0, text.length)
    val segments = ArrayList<ChatSegment>(emotes.size * 2 + 1)
    var charPos = 0
    var codePointPos = 0
    for (emote in emotes) {
        if (emote.end >= codePoints || emote.start < codePointPos) {
            continue
        }
        val start = text.offsetByCodePoints(charPos, emote.start - codePointPos)
        val end = text.offsetByCodePoints(start, emote.end - emote.start + 1)
        if (start > charPos) {
            segments.add(ChatSegment.Text(text.substring(charPos, start)))
        }
        segments.add(ChatSegment.Emote(text.substring(start, end), "$EMOTE_URL${emote.id}/default/dark/3.0"))
        charPos = end
        codePointPos = emote.end + 1
    }
    if (charPos < text.length) {
        segments.add(ChatSegment.Text(text.substring(charPos)))
    }
    return segments
}

private fun parseEmotes(tag: String): List<EmoteRange> {
    val emotes = ArrayList<EmoteRange>()
    for (definition in tag.split('/')) {
        val colon = definition.indexOf(':')
        if (colon <= 0) {
            continue
        }
        val id = definition.substring(0, colon)
        if (!isEmoteId(id)) {
            continue
        }
        for (range in definition.substring(colon + 1).split(',')) {
            val dash = range.indexOf('-')
            if (dash <= 0) {
                continue
            }
            val start = range.substring(0, dash).toIntOrNull() ?: continue
            val end = range.substring(dash + 1).toIntOrNull() ?: continue
            if (start in 0..end) {
                emotes.add(EmoteRange(start, end, id))
            }
        }
    }
    emotes.sortBy { it.start }
    return emotes
}

private fun isEmoteId(id: String): Boolean {
    for (c in id) {
        if (c !in 'a'..'z' && c !in 'A'..'Z' && c !in '0'..'9' && c != '_' && c != '-') {
            return false
        }
    }
    return true
}

private fun parseColor(value: String?): Int? {
    if (value == null || value.length != 7 || value[0] != '#') {
        return null
    }
    var rgb = 0
    for (i in 1..6) {
        val digit = Character.digit(value[i], 16)
        if (digit < 0) {
            return null
        }
        rgb = (rgb shl 4) or digit
    }
    return rgb
}

private fun hasBadge(badges: String?, name: String): Boolean {
    if (badges == null) {
        return false
    }
    var start = 0
    while (start < badges.length) {
        var end = badges.indexOf(',', start)
        if (end < 0) {
            end = badges.length
        }
        val slash = badges.indexOf('/', start)
        val nameEnd = if (slash in start until end) slash else end
        if (nameEnd - start == name.length && badges.startsWith(name, start)) {
            return true
        }
        start = end + 1
    }
    return false
}

private fun parseTags(line: String, from: Int, to: Int): Map<String, String> {
    val tags = HashMap<String, String>(32)
    var start = from
    while (start < to) {
        var end = line.indexOf(';', start)
        if (end < 0 || end > to) {
            end = to
        }
        val equals = line.indexOf('=', start)
        if (equals > start && equals + 1 < end) {
            tags[line.substring(start, equals)] = unescapeTagValue(line, equals + 1, end)
        }
        start = end + 1
    }
    return tags
}

private fun unescapeTagValue(line: String, from: Int, to: Int): String {
    var hasEscape = false
    for (i in from until to) {
        if (line[i] == '\\') {
            hasEscape = true
            break
        }
    }
    if (!hasEscape) {
        return line.substring(from, to)
    }
    val value = StringBuilder(to - from)
    var i = from
    while (i < to) {
        val c = line[i++]
        if (c != '\\') {
            value.append(c)
        } else if (i < to) {
            when (val escaped = line[i++]) {
                ':' -> value.append(';')
                's' -> value.append(' ')
                'r' -> value.append('\r')
                'n' -> value.append('\n')
                else -> value.append(escaped)
            }
        }
    }
    return value.toString()
}

private fun skipSpaces(line: String, from: Int): Int {
    var i = from
    while (i < line.length && line[i] == ' ') {
        i++
    }
    return i
}
