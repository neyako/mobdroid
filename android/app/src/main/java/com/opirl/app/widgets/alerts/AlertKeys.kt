package com.opirl.app.widgets.alerts

import com.opirl.app.engine.alerts.AlertEvent
import kotlin.random.Random

object AlertKey {
    const val FOLLOWS = "twitch.follows"
    const val SUBSCRIPTIONS = "twitch.subscriptions"
    const val RAIDS = "twitch.raids"
    const val REDEMPTIONS = "twitch.redemptions"
    const val KICK_SUBSCRIPTIONS = "kick.subscriptions"
    const val KICK_GIFTED = "kick.gifted"
    const val KICK_HOSTS = "kick.hosts"
    const val KICK_REWARDS = "kick.rewards"
    private const val CHEER = "twitch.cheers."
    private const val KICKS = "kick.kicks."
    private const val COMMAND = "bot."

    fun cheer(id: String) = CHEER + id

    fun kicks(id: String) = KICKS + id

    fun command(id: String) = COMMAND + id

    fun isThreshold(key: String) = key.startsWith(CHEER) || key.startsWith(KICKS)

    fun isCommand(key: String) = key.startsWith(COMMAND)

    fun idOf(key: String) = key.substringAfterLast('.')
}

val ALERT_TEST_NAMES = listOf("Mark", "Natasha", "Pedro", "Anna")

fun AlertsSettings.alert(key: String): AlertSettings? = when (key) {
    AlertKey.FOLLOWS -> twitch.follows
    AlertKey.SUBSCRIPTIONS -> twitch.subscriptions
    AlertKey.RAIDS -> twitch.raids
    AlertKey.REDEMPTIONS -> twitch.redemptions
    AlertKey.KICK_SUBSCRIPTIONS -> kick.subscriptions
    AlertKey.KICK_GIFTED -> kick.giftedSubscriptions
    AlertKey.KICK_HOSTS -> kick.hosts
    AlertKey.KICK_REWARDS -> kick.rewards
    else -> {
        val id = AlertKey.idOf(key)
        when {
            key == AlertKey.cheer(id) -> twitch.cheers.firstOrNull { it.id == id }?.alert
            key == AlertKey.kicks(id) -> kick.kicks.firstOrNull { it.id == id }?.alert
            key == AlertKey.command(id) -> chatBot.commands.firstOrNull { it.id == id }?.alert
            else -> null
        }
    }
}

fun AlertsSettings.withAlert(key: String, alert: AlertSettings): AlertsSettings = when (key) {
    AlertKey.FOLLOWS -> copy(twitch = twitch.copy(follows = alert))
    AlertKey.SUBSCRIPTIONS -> copy(twitch = twitch.copy(subscriptions = alert))
    AlertKey.RAIDS -> copy(twitch = twitch.copy(raids = alert))
    AlertKey.REDEMPTIONS -> copy(twitch = twitch.copy(redemptions = alert))
    AlertKey.KICK_SUBSCRIPTIONS -> copy(kick = kick.copy(subscriptions = alert))
    AlertKey.KICK_GIFTED -> copy(kick = kick.copy(giftedSubscriptions = alert))
    AlertKey.KICK_HOSTS -> copy(kick = kick.copy(hosts = alert))
    AlertKey.KICK_REWARDS -> copy(kick = kick.copy(rewards = alert))
    else -> {
        val id = AlertKey.idOf(key)
        when {
            key == AlertKey.cheer(id) ->
                copy(twitch = twitch.copy(cheers = twitch.cheers.map { if (it.id == id) it.copy(alert = alert) else it }))
            key == AlertKey.kicks(id) ->
                copy(kick = kick.copy(kicks = kick.kicks.map { if (it.id == id) it.copy(alert = alert) else it }))
            key == AlertKey.command(id) ->
                copy(chatBot = chatBot.copy(commands = chatBot.commands.map { if (it.id == id) it.copy(alert = alert) else it }))
            else -> this
        }
    }
}

fun AlertsSettings.threshold(key: String): AlertThreshold? {
    val id = AlertKey.idOf(key)
    return when (key) {
        AlertKey.cheer(id) -> twitch.cheers.firstOrNull { it.id == id }
        AlertKey.kicks(id) -> kick.kicks.firstOrNull { it.id == id }
        else -> null
    }
}

fun AlertsSettings.withThreshold(key: String, transform: (AlertThreshold) -> AlertThreshold): AlertsSettings {
    val id = AlertKey.idOf(key)
    return when (key) {
        AlertKey.cheer(id) -> copy(twitch = twitch.copy(cheers = twitch.cheers.map { if (it.id == id) transform(it) else it }))
        AlertKey.kicks(id) -> copy(kick = kick.copy(kicks = kick.kicks.map { if (it.id == id) transform(it) else it }))
        else -> this
    }
}

fun AlertsSettings.command(key: String): AlertCommand? = chatBot.commands.firstOrNull { AlertKey.command(it.id) == key }

fun AlertsSettings.withCommand(key: String, transform: (AlertCommand) -> AlertCommand): AlertsSettings =
    copy(chatBot = chatBot.copy(commands = chatBot.commands.map { if (AlertKey.command(it.id) == key) transform(it) else it }))

fun AlertsSettings.testEvent(key: String, random: Random = Random): AlertEvent? {
    val name = ALERT_TEST_NAMES.random(random)
    val id = AlertKey.idOf(key)
    return when (key) {
        AlertKey.FOLLOWS -> AlertEvent.TwitchFollow(name)
        AlertKey.SUBSCRIPTIONS -> AlertEvent.TwitchSubscribe(name, 2, false)
        AlertKey.RAIDS -> AlertEvent.TwitchRaid(name, random.nextInt(1, 1000))
        AlertKey.REDEMPTIONS -> AlertEvent.TwitchRedemption(name, "Test reward")
        AlertKey.KICK_SUBSCRIPTIONS -> AlertEvent.KickSubscription(name, random.nextInt(1, 13))
        AlertKey.KICK_GIFTED -> AlertEvent.KickGiftedSubscriptions(name, 2, random.nextInt(1, 51))
        AlertKey.KICK_HOSTS -> AlertEvent.KickHost(name, random.nextInt(1, 1001))
        AlertKey.KICK_REWARDS -> AlertEvent.KickReward(name, "Test reward", "")
        else -> when {
            key == AlertKey.cheer(id) ->
                twitch.cheers.firstOrNull { it.id == id }?.let { AlertEvent.TwitchCheer(name, it.amount, "A test message!") }
            key == AlertKey.kicks(id) ->
                kick.kicks.firstOrNull { it.id == id }?.let { AlertEvent.KickKicks(name, "Kicks", it.amount) }
            key == AlertKey.command(id) ->
                chatBot.commands.firstOrNull { it.id == id }?.let { AlertEvent.ChatCommand(name, it.name) }
            else -> null
        }
    }
}
