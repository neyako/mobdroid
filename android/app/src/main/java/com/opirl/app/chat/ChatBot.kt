package com.opirl.app.chat

import android.os.SystemClock
import com.opirl.app.R
import com.opirl.app.engine.chat.ApiException
import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.bot.BOT_PREFIX
import com.opirl.app.engine.chat.bot.BotAction
import com.opirl.app.engine.chat.bot.BotDecision
import com.opirl.app.engine.chat.bot.BotGate
import com.opirl.app.engine.chat.bot.parseBotCommand
import com.opirl.app.scenes.placement
import com.opirl.app.scenes.withPlacement
import com.opirl.app.settings.SettingsStore
import com.opirl.app.stream.AlertLevel
import com.opirl.app.stream.StreamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val HELP =
    "$BOT_PREFIX commands: tts on|off, say, snapshot, mute, unmute, zoom, torch on|off, scene, " +
        "stream start|stop|title|category, widget <name> enable|disable, send"

class ChatBot(
    private val engine: StreamEngine,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val title: String,
) {
    private val gate = BotGate()

    fun onMessage(message: ChatMessage) {
        val current = settings.settings.value
        if (!current.chatBot) return
        val text = message.text
        if (!text.startsWith(BOT_PREFIX, ignoreCase = true)) return
        val action = parseBotCommand(text) ?: return
        val command = action.command ?: return reply(message, HELP)
        val permission = current.botPermission(command)
        val decision = gate.check(
            command,
            permission.toPermission(),
            message.isBroadcaster,
            message.isModerator,
            message.isSubscriber,
            SystemClock.elapsedRealtime(),
        )
        when (decision) {
            BotDecision.Allowed -> scope.launch(Dispatchers.Main) { run(action, message) }
            BotDecision.Denied -> if (permission.replies) {
                reply(message, "@${message.user} Sorry, you are not allowed to use this chat bot command")
            }
            is BotDecision.Cooldown -> if (permission.replies) {
                reply(message, "@${message.user} Sorry, this chat bot command is on cooldown for ${decision.seconds} seconds")
            }
        }
    }

    private fun reply(message: ChatMessage, text: String) {
        scope.launch { engine.chat.send(message.platform, text) {} }
    }

    private suspend fun run(action: BotAction, message: ChatMessage) {
        engine.showAlert(AlertLevel.INFO, title, "${message.user}: ${message.text}")
        when (action) {
            BotAction.Help -> Unit
            is BotAction.Tts -> settings.update { it.copy(chatSpeech = action.on) }
            is BotAction.Say -> if (settings.settings.value.chatSpeech) {
                engine.speech.say(engine.text(R.string.chat_speech_said, message.user, action.text))
            }
            BotAction.Snapshot -> engine.takeSnapshot()
            is BotAction.Mute -> engine.setMuted(action.muted)
            is BotAction.Zoom -> engine.setZoom(action.x)
            is BotAction.Torch -> engine.setTorch(action.on)
            is BotAction.Scene -> settings.settings.value.scenes.firstOrNull { it.name.equals(action.name, ignoreCase = true) }
                ?.let { engine.selectScene(it.id) }
            is BotAction.Stream -> if (action.live) engine.goLive() else engine.stopLive()
            is BotAction.StreamTitle -> report(engine.platforms.call(message.platform) { it.setTitle(action.title) })
            is BotAction.StreamCategory -> report(
                engine.platforms.call(message.platform) { api ->
                    val found = api.searchCategories(action.name)
                    api.setCategory(
                        found.firstOrNull { it.name.equals(action.name, ignoreCase = true) }
                            ?: found.firstOrNull()
                            ?: throw ApiException("Category not found"),
                    )
                },
            )
            is BotAction.Widget -> settings.update { current ->
                val scene = current.selectedScene
                val widget = current.widgets.firstOrNull { it.name.equals(action.name, ignoreCase = true) }
                if (scene == null || widget == null) {
                    current
                } else {
                    current.withPlacement(scene.id, scene.placement(widget).copy(enabled = action.enabled))
                }
            }
            is BotAction.Send -> reply(message, action.text)
        }
    }

    private fun report(result: Result<Unit>) {
        result.exceptionOrNull()?.let { engine.showAlert(AlertLevel.ERROR, title, it.message) }
    }
}
