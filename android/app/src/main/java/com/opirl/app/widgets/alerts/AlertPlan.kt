package com.opirl.app.widgets.alerts

import com.opirl.app.engine.alerts.AlertEvent
import java.util.Locale

const val ALERT_DELAY_AFTER_MS = 3000L

class AlertPlan(val alert: AlertSettings, val user: String, val message: String)

fun AlertComparison.matches(value: Int, amount: Int): Boolean = when (this) {
    AlertComparison.EQUAL -> value == amount
    AlertComparison.GREATER_EQUAL -> value >= amount
}

private fun List<AlertThreshold>.firstMatch(value: Int): AlertSettings? =
    firstOrNull { it.alert.enabled && it.comparison.matches(value, it.amount) }?.alert

private fun count(value: Int): String = String.format(Locale.US, "%,d", value)

private fun plan(alert: AlertSettings?, user: String, message: String): AlertPlan? =
    alert?.takeIf { it.enabled }?.let { AlertPlan(it, user, message) }

fun resolveAlert(settings: AlertsSettings, event: AlertEvent): AlertPlan? {
    val twitch = settings.twitch
    val kick = settings.kick
    return when (event) {
        is AlertEvent.TwitchFollow -> plan(twitch.follows, event.user, "just followed!")
        is AlertEvent.TwitchSubscribe -> plan(
            twitch.subscriptions,
            event.user,
            if (event.prime) "just subscribed with Prime!" else "just subscribed tier ${event.tier}!",
        )
        is AlertEvent.TwitchSubscriptionGift -> plan(
            twitch.subscriptions,
            event.user ?: "Anonymous",
            "just gifted ${event.total} tier ${event.tier} subscriptions!",
        )
        is AlertEvent.TwitchResubscribe -> {
            val text = if (event.streak != null) {
                "just resubscribed tier ${event.tier} for ${event.months} months, ${event.streak} in a row!"
            } else {
                "just resubscribed tier ${event.tier} for ${event.months} months!"
            }
            plan(twitch.subscriptions, event.user, if (event.message.isEmpty()) text else "$text ${event.message}")
        }
        is AlertEvent.TwitchSubscriptionUpgrade -> plan(
            twitch.subscriptions,
            event.user,
            if (event.tier != null) {
                "just converted their Prime subscription to tier ${event.tier}!"
            } else {
                "just continued their gift subscription!"
            },
        )
        is AlertEvent.TwitchRaid -> plan(twitch.raids, event.user, "raided with a party of ${event.viewers}!")
        is AlertEvent.TwitchRedemption -> plan(twitch.redemptions, event.user, "redeemed ${event.reward}!")
        is AlertEvent.TwitchCheer -> plan(
            twitch.cheers.firstMatch(event.bits),
            event.user ?: "Anonymous",
            "cheered ${count(event.bits)} bits! ${event.message}".trimEnd(),
        )
        is AlertEvent.KickSubscription -> plan(
            kick.subscriptions,
            event.user,
            "just subscribed! They've been subscribed for ${event.months} months!",
        )
        is AlertEvent.KickGiftedSubscriptions -> plan(
            kick.giftedSubscriptions,
            event.gifter,
            "just gifted ${event.count} subscription(s)! They've gifted ${event.total} in total!",
        )
        is AlertEvent.KickHost -> plan(kick.hosts, event.user, "is now hosting with ${event.viewers} viewers!")
        is AlertEvent.KickReward -> plan(
            kick.rewards,
            event.user,
            if (event.input.isEmpty()) "redeemed ${event.title}" else "redeemed ${event.title}: ${event.input}",
        )
        is AlertEvent.KickKicks -> plan(
            kick.kicks.firstMatch(event.amount),
            event.user,
            "sent ${event.gift} ${count(event.amount)} Kicks!",
        )
        is AlertEvent.ChatCommand -> plan(
            settings.chatBot.commands.firstOrNull { it.name == event.command && it.alert.enabled }?.alert,
            event.user,
            event.command,
        )
    }
}

fun spokenAlertText(plan: AlertPlan): String = "${plan.user} ${plan.message}"

fun alertWords(plan: AlertPlan): String = plan.message.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
