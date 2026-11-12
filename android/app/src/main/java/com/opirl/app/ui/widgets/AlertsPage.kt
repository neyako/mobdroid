package com.opirl.app.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.chat.missingTwitchScopes
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.GroupBuilder
import com.opirl.app.ui.NavRow
import com.opirl.app.ui.Note
import com.opirl.app.ui.RemovableRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.TwitchLoginDialog
import com.opirl.app.widgets.alerts.AlertComparison
import com.opirl.app.widgets.alerts.AlertCommand
import com.opirl.app.widgets.alerts.AlertKey
import com.opirl.app.widgets.alerts.AlertPermissions
import com.opirl.app.widgets.alerts.AlertThreshold
import com.opirl.app.widgets.alerts.AlertsSettings
import com.opirl.app.widgets.alerts.alert

@Composable
internal fun AlertsPage(scope: WidgetScope) {
    val page = scope.page
    when {
        page == null -> AlertsRoot(scope)
        page == "twitch" -> TwitchAlertsPage(scope)
        page == "kick" -> KickAlertsPage(scope)
        page == "bot" -> ChatBotAlertsPage(scope)
        page == "twitch.cheers" -> ThresholdsPage(scope, twitch = true)
        page == "kick.kicks" -> ThresholdsPage(scope, twitch = false)
        page.startsWith("media:") -> AlertMediaPage(scope, page.removePrefix("media:"))
        else -> AlertEditorPage(scope, page)
    }
}

@Composable
private fun AlertsRoot(scope: WidgetScope) {
    Group {
        row { shapes -> NavRow(shapes, "Twitch") { scope.push("twitch", "Twitch") } }
        row { shapes -> NavRow(shapes, "Kick") { scope.push("kick", "Kick") } }
        row { shapes ->
            val title = stringResource(R.string.alerts_chat_bot)
            NavRow(shapes, title) { scope.push("bot", title) }
        }
    }
    TwitchLoginPrompt(scope)
}

@Composable
private fun TwitchLoginPrompt(scope: WidgetScope) {
    val logins by scope.engine.logins.logins.collectAsStateWithLifecycle()
    var loggingIn by remember { mutableStateOf(false) }
    val account = logins.twitch
    if (account == null || missingTwitchScopes(account).isNotEmpty()) {
        Note(stringResource(if (account == null) R.string.alerts_twitch_login_note else R.string.alerts_twitch_scopes_note))
        Group {
            row { shapes ->
                ActionRow(
                    shapes,
                    stringResource(if (account == null) R.string.log_in else R.string.alerts_log_in_again),
                    Icons.Rounded.Add,
                ) { loggingIn = true }
            }
        }
    }
    if (loggingIn) {
        TwitchLoginDialog(
            scope.engine.http,
            onAccount = { added ->
                scope.engine.logins.update { it.copy(twitch = added) }
                loggingIn = false
            },
            onClose = { loggingIn = false },
        )
    }
}

@Composable
private fun TwitchAlertsPage(scope: WidgetScope) {
    Group {
        alertRow(scope, AlertKey.FOLLOWS, R.string.alerts_follows)
        alertRow(scope, AlertKey.SUBSCRIPTIONS, R.string.alerts_subscriptions)
        alertRow(scope, AlertKey.RAIDS, R.string.alerts_raids)
        row { shapes ->
            val title = stringResource(R.string.alerts_cheers)
            NavRow(shapes, title) { scope.push("twitch.cheers", title) }
        }
        alertRow(scope, AlertKey.REDEMPTIONS, R.string.alerts_rewards)
    }
    TwitchLoginPrompt(scope)
}

@Composable
private fun KickAlertsPage(scope: WidgetScope) {
    Group {
        alertRow(scope, AlertKey.KICK_SUBSCRIPTIONS, R.string.alerts_subscriptions)
        alertRow(scope, AlertKey.KICK_GIFTED, R.string.alerts_gift_subscriptions)
        alertRow(scope, AlertKey.KICK_HOSTS, R.string.alerts_hosts)
        alertRow(scope, AlertKey.KICK_REWARDS, R.string.alerts_rewards)
        row { shapes ->
            val title = stringResource(R.string.alerts_kicks)
            NavRow(shapes, title) { scope.push("kick.kicks", title) }
        }
    }
}

private fun GroupBuilder.alertRow(scope: WidgetScope, key: String, title: Int) {
    row { shapes ->
        val label = stringResource(title)
        NavRow(shapes, label, stringResource(if (scope.widget.alerts.alert(key)?.enabled == true) R.string.on else R.string.off)) {
            scope.push(key, label)
        }
    }
}

@Composable
private fun ThresholdsPage(scope: WidgetScope, twitch: Boolean) {
    val alerts = scope.widget.alerts
    val items = if (twitch) alerts.twitch.cheers else alerts.kick.kicks
    val update = { transform: (List<AlertThreshold>) -> List<AlertThreshold> ->
        scope.update { widget ->
            val current = widget.alerts
            widget.copy(
                alerts = if (twitch) {
                    current.copy(twitch = current.twitch.copy(cheers = transform(current.twitch.cheers)))
                } else {
                    current.copy(kick = current.kick.copy(kicks = transform(current.kick.kicks)))
                },
            )
        }
    }
    Group {
        items.forEach { item ->
            row { shapes ->
                val title = thresholdTitle(item, twitch)
                RemovableRow(
                    shapes,
                    title,
                    stringResource(if (item.alert.enabled) R.string.on else R.string.off),
                    { scope.push(if (twitch) AlertKey.cheer(item.id) else AlertKey.kicks(item.id), title) },
                ) { update { list -> list.filterNot { it.id == item.id } } }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.alerts_add_item), Icons.Rounded.Add) { update { it + AlertThreshold() } }
        }
    }
    Note(stringResource(R.string.alerts_first_match_note))
}

@Composable
internal fun thresholdTitle(item: AlertThreshold, twitch: Boolean): String {
    val amount = "%,d".format(item.amount)
    val equal = item.comparison == AlertComparison.EQUAL
    return stringResource(
        when {
            twitch && equal -> R.string.alerts_cheer_equal
            twitch -> R.string.alerts_cheer_at_least
            equal -> R.string.alerts_kicks_equal
            else -> R.string.alerts_kicks_at_least
        },
        amount,
    )
}

@Composable
private fun ChatBotAlertsPage(scope: WidgetScope) {
    val bot = scope.widget.alerts.chatBot
    val update = { transform: (AlertsSettings) -> AlertsSettings -> scope.update { it.copy(alerts = transform(it.alerts)) } }
    Group {
        bot.commands.forEach { command ->
            row { shapes ->
                RemovableRow(
                    shapes,
                    command.name,
                    stringResource(if (command.alert.enabled) R.string.on else R.string.off),
                    { scope.push(AlertKey.command(command.id), command.name) },
                ) { update { it.copy(chatBot = it.chatBot.copy(commands = it.chatBot.commands.filterNot { c -> c.id == command.id })) } }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.alerts_add_command), Icons.Rounded.Add) {
                update { it.copy(chatBot = it.chatBot.copy(commands = it.chatBot.commands + AlertCommand())) }
            }
        }
    }
    Note(stringResource(R.string.alerts_commands_note))
    val permissions = bot.permissions
    val updatePermissions = { transform: (AlertPermissions) -> AlertPermissions ->
        update { it.copy(chatBot = it.chatBot.copy(permissions = transform(it.chatBot.permissions))) }
    }
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.alerts_moderators), permissions.moderators) { on ->
                updatePermissions { it.copy(moderators = on) }
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.alerts_subscribers), permissions.subscribers) { on ->
                updatePermissions { it.copy(subscribers = on) }
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.alerts_everyone), permissions.others) { on ->
                updatePermissions { it.copy(others = on) }
            }
        }
        row { shapes ->
            SliderRow(
                shapes,
                stringResource(R.string.alerts_cooldown),
                permissions.cooldown,
                0..300,
                if (permissions.cooldown == 0) stringResource(R.string.off) else "${permissions.cooldown} s",
                5,
            ) { seconds -> updatePermissions { it.copy(cooldown = seconds) } }
        }
    }
    Note(stringResource(R.string.alerts_permissions_note))
}
