package com.opirl.app.widgets.alerts

import com.opirl.app.engine.alerts.AlertEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private fun texts(settings: AlertsSettings, event: AlertEvent): Pair<String, String>? =
    resolveAlert(settings, event)?.let { it.user to it.message }

class AlertPlanTest {
    private val settings = AlertsSettings()

    @Test
    fun writesTheTwitchTexts() {
        val cases = listOf(
            AlertEvent.TwitchFollow("Mark") to ("Mark" to "just followed!"),
            AlertEvent.TwitchSubscribe("Anna", 2, false) to ("Anna" to "just subscribed tier 2!"),
            AlertEvent.TwitchSubscribe("Anna", 1, true) to ("Anna" to "just subscribed with Prime!"),
            AlertEvent.TwitchSubscriptionGift("Pedro", 5, 3) to ("Pedro" to "just gifted 5 tier 3 subscriptions!"),
            AlertEvent.TwitchSubscriptionGift(null, 1, 1) to ("Anonymous" to "just gifted 1 tier 1 subscriptions!"),
            AlertEvent.TwitchResubscribe("Mark", 1, 14, 6, "") to ("Mark" to "just resubscribed tier 1 for 14 months, 6 in a row!"),
            AlertEvent.TwitchResubscribe("Mark", 3, 2, null, "") to ("Mark" to "just resubscribed tier 3 for 2 months!"),
            AlertEvent.TwitchResubscribe("Mark", 3, 2, null, "so good") to ("Mark" to "just resubscribed tier 3 for 2 months! so good"),
            AlertEvent.TwitchSubscriptionUpgrade("Anna", 2) to ("Anna" to "just converted their Prime subscription to tier 2!"),
            AlertEvent.TwitchSubscriptionUpgrade("Anna", null) to ("Anna" to "just continued their gift subscription!"),
            AlertEvent.TwitchRaid("Pedro", 412) to ("Pedro" to "raided with a party of 412!"),
            AlertEvent.TwitchRedemption("Mark", "Hydrate") to ("Mark" to "redeemed Hydrate!"),
            AlertEvent.TwitchCheer("Anna", 1000, "pogchamp") to ("Anna" to "cheered 1,000 bits! pogchamp"),
            AlertEvent.TwitchCheer(null, 50, "") to ("Anonymous" to "cheered 50 bits!"),
        )
        for ((event, expected) in cases) assertEquals(expected, texts(settings, event), event.toString())
    }

    @Test
    fun writesTheKickTexts() {
        val cases = listOf(
            AlertEvent.KickSubscription("Mark", 3) to ("Mark" to "just subscribed! They've been subscribed for 3 months!"),
            AlertEvent.KickGiftedSubscriptions("Anna", 3, 12) to ("Anna" to "just gifted 3 subscription(s)! They've gifted 12 in total!"),
            AlertEvent.KickHost("Pedro", 57) to ("Pedro" to "is now hosting with 57 viewers!"),
            AlertEvent.KickReward("Mark", "Hydrate", "") to ("Mark" to "redeemed Hydrate"),
            AlertEvent.KickReward("Mark", "Hydrate", "drink up") to ("Mark" to "redeemed Hydrate: drink up"),
            AlertEvent.KickKicks("Anna", "Rage Quit", 1234) to ("Anna" to "sent Rage Quit 1,234 Kicks!"),
        )
        for ((event, expected) in cases) assertEquals(expected, texts(settings, event), event.toString())
    }

    @Test
    fun aDisabledAlertPlaysNothing() {
        val off = AlertSettings(enabled = false)
        val twitch = settings.twitch.copy(follows = off, subscriptions = off, raids = off, redemptions = off)
        val kick = settings.kick.copy(subscriptions = off, giftedSubscriptions = off, hosts = off, rewards = off)
        val disabled = settings.copy(twitch = twitch, kick = kick)
        val events = listOf(
            AlertEvent.TwitchFollow("a"),
            AlertEvent.TwitchSubscribe("a", 1, false),
            AlertEvent.TwitchSubscriptionGift("a", 1, 1),
            AlertEvent.TwitchResubscribe("a", 1, 1, null, ""),
            AlertEvent.TwitchSubscriptionUpgrade("a", null),
            AlertEvent.TwitchRaid("a", 1),
            AlertEvent.TwitchRedemption("a", "r"),
            AlertEvent.KickSubscription("a", 1),
            AlertEvent.KickGiftedSubscriptions("a", 1, 1),
            AlertEvent.KickHost("a", 1),
            AlertEvent.KickReward("a", "r", ""),
        )
        for (event in events) assertNull(resolveAlert(disabled, event), event.toString())
        assertEquals("a" to "just followed!", texts(settings, AlertEvent.TwitchFollow("a")))
    }

    @Test
    fun everySubscriptionKindUsesTheSubscriptionAlert() {
        val chosen = AlertSettings(accentColor = 0xFF123456.toInt())
        val custom = settings.copy(twitch = settings.twitch.copy(subscriptions = chosen))
        for (event in listOf(
            AlertEvent.TwitchSubscribe("a", 1, false),
            AlertEvent.TwitchSubscriptionGift("a", 2, 1),
            AlertEvent.TwitchResubscribe("a", 1, 3, null, ""),
            AlertEvent.TwitchSubscriptionUpgrade("a", 1),
        )) {
            assertSame(chosen, resolveAlert(custom, event)?.alert, event.toString())
        }
    }

    @Test
    fun cheersPlayTheFirstEnabledItemThatMatches() {
        val big = AlertSettings(accentColor = 1)
        val exact = AlertSettings(accentColor = 2)
        val off = AlertSettings(enabled = false)
        val any = AlertSettings(accentColor = 3)
        val items = listOf(
            AlertThreshold("a", 100, AlertComparison.GREATER_EQUAL, big),
            AlertThreshold("b", 50, AlertComparison.EQUAL, exact),
            AlertThreshold("c", 1, AlertComparison.GREATER_EQUAL, off),
            AlertThreshold("d", 10, AlertComparison.GREATER_EQUAL, any),
        )
        val custom = settings.copy(twitch = settings.twitch.copy(cheers = items))
        fun chosen(bits: Int) = resolveAlert(custom, AlertEvent.TwitchCheer("x", bits, ""))?.alert?.accentColor
        assertEquals(1, chosen(100))
        assertEquals(1, chosen(5000))
        assertEquals(2, chosen(50))
        assertEquals(3, chosen(60))
        assertEquals(3, chosen(10))
        assertNull(chosen(9))
    }

    @Test
    fun kicksPlayTheFirstEnabledItemThatMatches() {
        val items = listOf(
            AlertThreshold("a", 10, AlertComparison.EQUAL, AlertSettings(accentColor = 1)),
            AlertThreshold("b", 5, AlertComparison.GREATER_EQUAL, AlertSettings(accentColor = 2)),
        )
        val custom = settings.copy(kick = settings.kick.copy(kicks = items))
        fun chosen(amount: Int) = resolveAlert(custom, AlertEvent.KickKicks("x", "Kicks", amount))?.alert?.accentColor
        assertEquals(1, chosen(10))
        assertEquals(2, chosen(11))
        assertEquals(2, chosen(5))
        assertNull(chosen(4))
    }

    @Test
    fun commandsMatchTheirNameExactlyAndOnlyWhenEnabled() {
        val commands = listOf(
            AlertCommand("1", "hello", AlertSettings(accentColor = 1)),
            AlertCommand("2", "bye", AlertSettings(enabled = false)),
        )
        val custom = settings.copy(chatBot = ChatBotAlerts(commands))
        assertEquals("Mark" to "hello", texts(custom, AlertEvent.ChatCommand("Mark", "hello")))
        assertEquals(1, resolveAlert(custom, AlertEvent.ChatCommand("Mark", "hello"))?.alert?.accentColor)
        assertNull(texts(custom, AlertEvent.ChatCommand("Mark", "bye")))
        assertNull(texts(custom, AlertEvent.ChatCommand("Mark", "Hello")))
        assertNull(texts(custom, AlertEvent.ChatCommand("Mark", "other")))
    }

    @Test
    fun comparesBitsTheWayTheSettingsSay() {
        assertEquals(listOf(true, false, true), listOf(AlertComparison.EQUAL.matches(5, 5), AlertComparison.EQUAL.matches(6, 5), AlertComparison.GREATER_EQUAL.matches(6, 5)))
        assertEquals(false, AlertComparison.GREATER_EQUAL.matches(4, 5))
    }

    @Test
    fun speaksTheNameAndTheMessage() {
        val plan = resolveAlert(settings, AlertEvent.TwitchFollow("Mark"))!!
        assertEquals("Mark just followed!", spokenAlertText(plan))
        val spaced = AlertPlan(AlertSettings(), "Mark", "  a   b\n c ")
        assertEquals("a b c", alertWords(spaced))
    }
}
