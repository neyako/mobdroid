package com.opirl.app.widgets.alerts

import com.opirl.app.R
import com.opirl.app.chat.TWITCH_CLIENT_ID
import com.opirl.app.engine.alerts.AlertEvent
import com.opirl.app.engine.alerts.AlertListener
import com.opirl.app.engine.alerts.TwitchEventSubClient
import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.settings.Logins
import com.opirl.app.settings.TwitchAccount
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.WidgetRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlertsSource(
    private val runtime: WidgetRuntime,
    logins: StateFlow<Logins>,
    kick: Flow<AlertEvent>,
    private val onError: (String) -> Unit,
) {
    private var twitch: TwitchEventSubClient? = null

    init {
        val scope = runtime.scope
        val wanted = combine(
            runtime.active,
            runtime.store.settings.map { settings -> settings.widgets.any { it.type == WidgetType.ALERTS } }.distinctUntilChanged(),
        ) { active, exists -> active && exists }.distinctUntilChanged()
        scope.launch {
            combine(wanted, logins.map { it.twitch }.distinctUntilChanged()) { on, account -> account.takeIf { on } }
                .distinctUntilChanged()
                .collect { account -> withContext(Dispatchers.IO) { restart(account) } }
        }
        scope.launch { wanted.collect { if (!it) alertsAudio(runtime).release() } }
        scope.launch { kick.collect { if (runtime.active.value) deliverAlert(runtime, it) } }
        scope.launch { watchChat() }
    }

    private fun restart(account: TwitchAccount?) {
        twitch?.stop()
        twitch = null
        if (account == null) return
        val listener = object : AlertListener {
            override fun onAlert(event: AlertEvent) = deliverAlert(runtime, event)

            override fun onConnected() = Unit

            override fun onDisconnected(reason: String) = Unit

            override fun onUnauthorized() = onError(runtime.context.getString(R.string.alerts_twitch_expired))
        }
        twitch = TwitchEventSubClient(account.userId, account.accessToken, TWITCH_CLIENT_ID, account.scopes.toSet(), listener, runtime.http)
            .also { it.start() }
    }

    private suspend fun watchChat() {
        var seen = runtime.chat.value.lastOrNull()?.key ?: -1L
        runtime.chat.collect { lines ->
            val fresh = lines.filter { it.key > seen }
            if (fresh.isEmpty()) return@collect
            seen = fresh.last().key
            if (!runtime.active.value) return@collect
            val widgets = placedAlertWidgets(runtime.store.settings.value)
            for (line in fresh) {
                val command = parseAlertCommand(line.message.text) ?: continue
                for (widget in widgets) handleCommand(widget.id, widget.alerts.chatBot.permissions, line.message, command)
            }
        }
    }

    private fun handleCommand(widgetId: String, permissions: AlertPermissions, message: ChatMessage, command: String) {
        val state = alertsState(runtime, widgetId)
        val sender = ChatSender(message.isBroadcaster, message.isModerator, message.isSubscriber)
        val check = synchronized(state) {
            checkChatCommand(permissions, sender, System.currentTimeMillis(), state.lastCommandMs).also { state.lastCommandMs = it.lastExecutedMs }
        }
        if (!check.allowed) return
        state.sequence.enqueue(AlertEvent.ChatCommand(message.user, command))
        runtime.invalidate()
    }
}
