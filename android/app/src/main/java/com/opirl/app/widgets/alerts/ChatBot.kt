package com.opirl.app.widgets.alerts

import com.opirl.app.engine.chat.bot.BOT_PREFIX

private val whitespace = Regex("\\s+")

fun parseAlertCommand(text: String): String? {
    val words = ArrayDeque(text.trim().split(whitespace).filter { it.isNotEmpty() })
    if (words.removeFirstOrNull()?.lowercase() != BOT_PREFIX) return null
    if (words.removeFirstOrNull()?.lowercase() != "alert") return null
    val first = words.removeFirstOrNull() ?: return null
    if (!first.startsWith("\"")) return first
    val parts = mutableListOf(first.removePrefix("\""))
    while (!parts.last().endsWith("\"")) parts += words.removeFirstOrNull() ?: return null
    parts[parts.lastIndex] = parts.last().removeSuffix("\"")
    return parts.joinToString(" ")
}

class ChatSender(val broadcaster: Boolean, val moderator: Boolean, val subscriber: Boolean)

class ChatCommandCheck(val allowed: Boolean, val lastExecutedMs: Long?)

fun checkChatCommand(permissions: AlertPermissions, sender: ChatSender, nowMs: Long, lastExecutedMs: Long?): ChatCommandCheck {
    if (sender.broadcaster || (sender.moderator && permissions.moderators)) return ChatCommandCheck(true, nowMs)
    val permitted = (sender.subscriber && permissions.subscribers) || permissions.others
    val coolingDown = permissions.cooldown > 0 && lastExecutedMs != null && nowMs - lastExecutedMs <= permissions.cooldown * 1000L
    return if (permitted && !coolingDown) ChatCommandCheck(true, nowMs) else ChatCommandCheck(false, lastExecutedMs)
}
