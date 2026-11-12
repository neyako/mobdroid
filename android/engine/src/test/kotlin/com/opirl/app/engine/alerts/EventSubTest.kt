package com.opirl.app.engine.alerts

import com.opirl.app.engine.alerts.EventSubFixtures.chat
import com.opirl.app.engine.alerts.EventSubFixtures.chatWithMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun event(message: String): AlertEvent? = assertIs<EventSubMessage.Notification>(parseEventSub(message)).event

class EventSubTest {
    @Test
    fun readsTheSessionControlMessages() {
        assertEquals(
            EventSubMessage.Welcome("AQoQILE98gtqShGmLD7AM6yJThAB", 10),
            parseEventSub(EventSubFixtures.welcome()),
        )
        assertEquals(EventSubMessage.Welcome("abc", 30), parseEventSub(EventSubFixtures.welcome("abc", 30)))
        assertEquals(EventSubMessage.Welcome("abc", 10), parseEventSub(EventSubFixtures.welcome("abc", null)))
        assertEquals(EventSubMessage.Keepalive, parseEventSub(EventSubFixtures.KEEPALIVE))
        assertEquals(
            EventSubMessage.Reconnect("wss://eventsub.wss.twitch.tv?challenge=abc"),
            parseEventSub(EventSubFixtures.reconnect("wss://eventsub.wss.twitch.tv?challenge=abc")),
        )
        assertEquals(
            EventSubMessage.Revocation("channel.follow", "authorization_revoked"),
            parseEventSub(EventSubFixtures.revocation("authorization_revoked")),
        )
    }

    @Test
    fun readsFollowsRaidsCheersAndRedemptions() {
        assertEquals(AlertEvent.TwitchFollow("Cool_User"), event(EventSubFixtures.FOLLOW))
        assertEquals(AlertEvent.TwitchRaid("Cool_User", 9001), event(EventSubFixtures.RAID))
        assertEquals(AlertEvent.TwitchCheer("Cool_User", 1000, "pogchamp"), event(EventSubFixtures.CHEER))
        assertEquals(AlertEvent.TwitchCheer(null, 50, ""), event(EventSubFixtures.ANONYMOUS_CHEER))
        assertEquals(AlertEvent.TwitchRedemption("Cooler_User", "Hydrate"), event(EventSubFixtures.REDEMPTION))
    }

    @Test
    fun keepsTheMessageIdForDeduplication() {
        assertEquals("message-channel.follow", assertIs<EventSubMessage.Notification>(parseEventSub(EventSubFixtures.FOLLOW)).id)
    }

    @Test
    fun readsSubscriptionsFromChatNotifications() {
        assertEquals(
            AlertEvent.TwitchSubscribe("Cool_User", 2, false),
            event(chat("sub", """"sub":{"sub_tier":"2000","is_prime":false,"duration_months":1}""")),
        )
        assertEquals(
            AlertEvent.TwitchSubscribe("Cool_User", 1, true),
            event(chat("sub", """"sub":{"sub_tier":"1000","is_prime":true,"duration_months":1}""")),
        )
        assertEquals(
            AlertEvent.TwitchResubscribe("Cool_User", 3, 14, 6, "love it"),
            event(
                chatWithMessage(
                    "resub",
                    """"resub":{"cumulative_months":14,"duration_months":1,"streak_months":6,"sub_tier":"3000","is_prime":false,"is_gift":false}""",
                    "love it",
                ),
            ),
        )
        assertEquals(
            AlertEvent.TwitchResubscribe("Cool_User", 1, 2, null, ""),
            event(chat("resub", """"resub":{"cumulative_months":2,"duration_months":1,"streak_months":null,"sub_tier":"1000","is_prime":false,"is_gift":false}""")),
        )
    }

    @Test
    fun readsGiftsAndUpgrades() {
        val single = """"sub_gift":{"duration_months":1,"cumulative_total":3,"recipient_user_name":"Lucky","sub_tier":"1000","community_gift_id":null}"""
        assertEquals(AlertEvent.TwitchSubscriptionGift("Cool_User", 1, 1), event(chat("sub_gift", single)))
        val part = """"sub_gift":{"duration_months":1,"sub_tier":"1000","community_gift_id":"6a3a8d0a-1d4c-4d6f-8a5a-6b4f1e4c1f2b"}"""
        assertNull(event(chat("sub_gift", part)))
        val community = """"community_sub_gift":{"id":"6a3a8d0a-1d4c-4d6f-8a5a-6b4f1e4c1f2b","total":5,"sub_tier":"2000","cumulative_total":null}"""
        assertEquals(AlertEvent.TwitchSubscriptionGift("Cool_User", 5, 2), event(chat("community_sub_gift", community)))
        val anonymous = chat("community_sub_gift", community, chatter = """"chatter_user_name":null,"chatter_is_anonymous":true""")
        assertEquals(AlertEvent.TwitchSubscriptionGift(null, 5, 2), event(anonymous))
        assertEquals(
            AlertEvent.TwitchSubscriptionUpgrade("Cool_User", 2),
            event(chat("prime_paid_upgrade", """"prime_paid_upgrade":{"sub_tier":"2000"}""")),
        )
        assertEquals(
            AlertEvent.TwitchSubscriptionUpgrade("Cool_User", null),
            event(chat("gift_paid_upgrade", """"gift_paid_upgrade":{"gifter_is_anonymous":false,"gifter_user_name":"Santa"}""")),
        )
    }

    @Test
    fun readsTheTierNumber() {
        assertEquals(listOf(1, 2, 3, 1, 1), listOf("1000", "2000", "3000", "4000", null).map { twitchTier(it) })
    }

    @Test
    fun ignoresNoticesThatAreNotAlertsOrComeFromASharedChat() {
        assertNull(event(chat("announcement", """"announcement":{"color":"PRIMARY"}""")))
        assertNull(event(chat("watch_streak", """"watch_streak":{"streak_count":5}""")))
        assertNull(event(chat("shared_chat_sub", """"shared_chat_sub":{"sub_tier":"1000","is_prime":false}""")))
        val shared = chat(
            "sub",
            """"sub":{"sub_tier":"1000","is_prime":false}""",
            source = """"source_broadcaster_user_id":"5555"""",
        )
        assertNull(event(shared))
    }

    @Test
    fun ignoresMessagesItCannotUse() {
        val unusable = listOf(
            "not json",
            "[]",
            "{}",
            """{"metadata":{"message_type":"session_welcome"},"payload":{}}""",
            """{"metadata":{"message_type":"session_reconnect"},"payload":{"session":{"id":"x"}}}""",
            """{"metadata":{"message_type":"mystery"},"payload":{}}""",
            """{"metadata":{"message_type":"notification","subscription_type":"channel.follow"},"payload":{"event":{"user_name":"x"}}}""",
        )
        for (text in unusable) {
            assertNull(parseEventSub(text), text)
        }
        val broken = listOf(
            EventSubFixtures.notification("channel.follow", 2, """{"user_id":"1"}"""),
            EventSubFixtures.notification("channel.raid", 1, """{"viewers":3}"""),
            EventSubFixtures.notification("channel.cheer", 1, """{"user_name":"x"}"""),
            EventSubFixtures.notification("channel.channel_points_custom_reward_redemption.add", 1, """{"user_name":"x"}"""),
            EventSubFixtures.notification("channel.poll.begin", 1, """{"id":"1"}"""),
            chat("resub", """"resub":{"duration_months":1}"""),
            chat("sub", """"resub":{"cumulative_months":1}"""),
        )
        for (text in broken) {
            assertNull(event(text), text)
        }
    }

    @Test
    fun buildsTheSubscriptionsWithTheirVersionsConditionsAndScopes() {
        val all = subscriptions("1337").associate { it.type to Triple(it.version, it.condition.toString(), it.scope) }
        assertEquals(
            mapOf(
                "channel.follow" to Triple(
                    2,
                    """{"broadcaster_user_id":"1337","moderator_user_id":"1337"}""",
                    "moderator:read:followers",
                ),
                "channel.chat.notification" to Triple(
                    1,
                    """{"broadcaster_user_id":"1337","user_id":"1337"}""",
                    "user:read:chat",
                ),
                "channel.channel_points_custom_reward_redemption.add" to Triple(
                    1,
                    """{"broadcaster_user_id":"1337"}""",
                    "channel:read:redemptions",
                ),
                "channel.raid" to Triple(1, """{"to_broadcaster_user_id":"1337"}""", null),
                "channel.cheer" to Triple(1, """{"broadcaster_user_id":"1337"}""", "bits:read"),
            ),
            all,
        )
        val body = Json.parseToJsonElement(subscriptionBody(subscriptions("1337").first(), "AQoQsession")).jsonObject
        assertEquals("channel.follow", body.getValue("type").jsonPrimitive.content)
        assertEquals("2", body.getValue("version").jsonPrimitive.content)
        assertEquals("websocket", body.getValue("transport").jsonObject.getValue("method").jsonPrimitive.content)
        assertEquals("AQoQsession", body.getValue("transport").jsonObject.getValue("session_id").jsonPrimitive.content)
    }
}
