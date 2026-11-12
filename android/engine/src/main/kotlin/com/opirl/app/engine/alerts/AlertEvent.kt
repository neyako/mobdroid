package com.opirl.app.engine.alerts

sealed interface AlertEvent {
    data class TwitchFollow(val user: String) : AlertEvent

    data class TwitchSubscribe(val user: String, val tier: Int, val prime: Boolean) : AlertEvent

    data class TwitchSubscriptionGift(val user: String?, val total: Int, val tier: Int) : AlertEvent

    data class TwitchResubscribe(
        val user: String,
        val tier: Int,
        val months: Int,
        val streak: Int?,
        val message: String,
    ) : AlertEvent

    data class TwitchSubscriptionUpgrade(val user: String, val tier: Int?) : AlertEvent

    data class TwitchRaid(val user: String, val viewers: Int) : AlertEvent

    data class TwitchRedemption(val user: String, val reward: String) : AlertEvent

    data class TwitchCheer(val user: String?, val bits: Int, val message: String) : AlertEvent

    data class KickSubscription(val user: String, val months: Int) : AlertEvent

    data class KickGiftedSubscriptions(val gifter: String, val count: Int, val total: Int) : AlertEvent

    data class KickHost(val user: String, val viewers: Int) : AlertEvent

    data class KickReward(val user: String, val title: String, val input: String) : AlertEvent

    data class KickKicks(val user: String, val gift: String, val amount: Int) : AlertEvent

    data class ChatCommand(val user: String, val command: String) : AlertEvent
}

interface AlertListener {
    fun onAlert(event: AlertEvent)

    fun onConnected()

    fun onDisconnected(reason: String)

    fun onUnauthorized()
}
