package com.opirl.app.engine.chat.bot

const val BOT_PREFIX = "!opirl"

enum class BotCommand(val moderatorsByDefault: Boolean = true) {
    TTS,
    SNAPSHOT,
    AUDIO,
    ZOOM,
    TORCH,
    SCENE(false),
    STREAM(false),
    WIDGET,
    SEND,
}

sealed interface BotAction {
    val command: BotCommand?

    data object Help : BotAction {
        override val command = null
    }

    data class Tts(val on: Boolean) : BotAction {
        override val command = BotCommand.TTS
    }

    data class Say(val text: String) : BotAction {
        override val command = BotCommand.TTS
    }

    data object Snapshot : BotAction {
        override val command = BotCommand.SNAPSHOT
    }

    data class Mute(val muted: Boolean) : BotAction {
        override val command = BotCommand.AUDIO
    }

    data class Zoom(val x: Float) : BotAction {
        override val command = BotCommand.ZOOM
    }

    data class Torch(val on: Boolean) : BotAction {
        override val command = BotCommand.TORCH
    }

    data class Scene(val name: String) : BotAction {
        override val command = BotCommand.SCENE
    }

    data class Stream(val live: Boolean) : BotAction {
        override val command = BotCommand.STREAM
    }

    data class StreamTitle(val title: String) : BotAction {
        override val command = BotCommand.STREAM
    }

    data class StreamCategory(val name: String) : BotAction {
        override val command = BotCommand.STREAM
    }

    data class Widget(val name: String, val enabled: Boolean) : BotAction {
        override val command = BotCommand.WIDGET
    }

    data class Send(val text: String) : BotAction {
        override val command = BotCommand.SEND
    }
}

private class Words(text: String) {
    private val parts = ArrayDeque(text.trim().split(Regex("\\s+")))

    fun next(): String? {
        val first = parts.removeFirstOrNull() ?: return null
        if (!first.startsWith("\"")) return first
        val words = ArrayList<String>()
        var word = first.substring(1)
        while (true) {
            if (word.endsWith("\"")) {
                words += word.dropLast(1)
                return words.joinToString(" ")
            }
            words += word
            word = parts.removeFirstOrNull() ?: return null
        }
    }

    fun rest(): String? = parts.joinToString(" ").takeIf { it.isNotEmpty() }
}

private fun onOff(word: String?): Boolean? = when (word?.lowercase()) {
    "on" -> true
    "off" -> false
    else -> null
}

fun parseBotCommand(text: String): BotAction? {
    val words = Words(text)
    if (words.next()?.lowercase() != BOT_PREFIX) return null
    return when (words.next()?.lowercase()) {
        "help" -> BotAction.Help
        "tts" -> onOff(words.next())?.let(BotAction::Tts)
        "say" -> words.rest()?.let(BotAction::Say)
        "snapshot" -> BotAction.Snapshot
        "mute" -> BotAction.Mute(true)
        "unmute" -> BotAction.Mute(false)
        "zoom" -> words.rest()?.toFloatOrNull()?.takeIf { it.isFinite() }?.let(BotAction::Zoom)
        "torch" -> onOff(words.next())?.let(BotAction::Torch)
        "scene" -> words.next()?.let(BotAction::Scene)
        "stream" -> when (words.next()?.lowercase()) {
            "start" -> BotAction.Stream(true)
            "stop" -> BotAction.Stream(false)
            "title" -> words.rest()?.let(BotAction::StreamTitle)
            "category" -> words.rest()?.let(BotAction::StreamCategory)
            else -> null
        }
        "widget" -> {
            val name = words.next()
            val enabled = when (words.next()?.lowercase()) {
                "enable" -> true
                "disable" -> false
                else -> null
            }
            if (name == null || enabled == null) null else BotAction.Widget(name, enabled)
        }
        "send" -> words.rest()?.let(BotAction::Send)
        else -> null
    }
}

data class BotPermission(
    val moderators: Boolean = true,
    val subscribers: Boolean = false,
    val others: Boolean = false,
    val cooldownSeconds: Int = 0,
    val replies: Boolean = false,
)

sealed interface BotDecision {
    data object Allowed : BotDecision

    data object Denied : BotDecision

    data class Cooldown(val seconds: Int) : BotDecision
}

class BotGate {
    private val lastRun = HashMap<BotCommand, Long>()

    @Synchronized
    fun check(
        command: BotCommand,
        permission: BotPermission,
        owner: Boolean,
        moderator: Boolean,
        subscriber: Boolean,
        nowMs: Long,
    ): BotDecision {
        if (!owner && !(moderator && permission.moderators)) {
            if (!(subscriber && permission.subscribers) && !permission.others) return BotDecision.Denied
            val left = permission.cooldownSeconds * 1000L - (nowMs - (lastRun[command] ?: Long.MIN_VALUE / 2))
            if (left > 0) return BotDecision.Cooldown(((left + 999) / 1000).toInt())
        }
        lastRun[command] = nowMs
        return BotDecision.Allowed
    }
}
