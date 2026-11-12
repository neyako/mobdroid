package com.opirl.app.engine.alerts

internal object EventSubFixtures {
    fun welcome(sessionId: String = "AQoQILE98gtqShGmLD7AM6yJThAB", keepaliveSeconds: Int? = 10): String =
        """{"metadata":{"message_id":"96a3f3b5-5dec-4eed-908e-e11ee657bb1c","message_type":"session_welcome","message_timestamp":"2026-11-10T03:32:02.634234626Z"},"payload":{"session":{"id":"$sessionId","status":"connected","connected_at":"2026-11-10T03:32:02.619Z","keepalive_timeout_seconds":${keepaliveSeconds ?: "null"},"reconnect_url":null}}}"""

    const val KEEPALIVE =
        """{"metadata":{"message_id":"84c1e79a-2a4b-4c13-ba0b-4312293e9308","message_type":"session_keepalive","message_timestamp":"2026-11-10T03:32:12.634Z"},"payload":{}}"""

    fun reconnect(url: String): String =
        """{"metadata":{"message_id":"84c1e79a-2a4b-4c13-ba0b-4312293e9309","message_type":"session_reconnect","message_timestamp":"2026-11-10T03:33:02.634Z"},"payload":{"session":{"id":"AQoQexAmpleSessionId","status":"reconnecting","keepalive_timeout_seconds":null,"reconnect_url":"$url","connected_at":"2026-11-10T03:32:02.619Z"}}}"""

    fun revocation(status: String): String =
        """{"metadata":{"message_id":"84c1e79a-2a4b-4c13-ba0b-4312293e930a","message_type":"revocation","message_timestamp":"2026-11-10T03:34:02.634Z","subscription_type":"channel.follow","subscription_version":"2"},"payload":{"subscription":{"id":"f1c2a387-161a-49f9-a165-0f21d7a4e1c4","status":"$status","type":"channel.follow","version":"2","cost":1,"condition":{"broadcaster_user_id":"1337"},"transport":{"method":"websocket","session_id":"AQoQexAmpleSessionId"},"created_at":"2026-11-10T03:00:00.0Z"}}}"""

    fun notification(type: String, version: Int, event: String, id: String = "message-$type"): String =
        """{"metadata":{"message_id":"$id","message_type":"notification","message_timestamp":"2026-11-10T03:32:30.634Z","subscription_type":"$type","subscription_version":"$version"},"payload":{"subscription":{"id":"f1c2a387-161a-49f9-a165-0f21d7a4e1c4","status":"enabled","type":"$type","version":"$version","cost":1,"condition":{"broadcaster_user_id":"1337"},"transport":{"method":"websocket","session_id":"AQoQexAmpleSessionId"},"created_at":"2026-11-10T03:00:00.0Z"},"event":$event}}"""

    val FOLLOW = notification(
        "channel.follow",
        2,
        """{"user_id":"1234","user_login":"cool_user","user_name":"Cool_User","broadcaster_user_id":"1337","broadcaster_user_login":"cooler_user","broadcaster_user_name":"Cooler_User","followed_at":"2026-11-10T03:32:30.0Z"}""",
    )

    val RAID = notification(
        "channel.raid",
        1,
        """{"from_broadcaster_user_id":"1234","from_broadcaster_user_login":"cool_user","from_broadcaster_user_name":"Cool_User","to_broadcaster_user_id":"1337","to_broadcaster_user_login":"cooler_user","to_broadcaster_user_name":"Cooler_User","viewers":9001}""",
    )

    val CHEER = notification(
        "channel.cheer",
        1,
        """{"is_anonymous":false,"user_id":"1234","user_login":"cool_user","user_name":"Cool_User","broadcaster_user_id":"1337","broadcaster_user_login":"cooler_user","broadcaster_user_name":"Cooler_User","message":"pogchamp","bits":1000}""",
    )

    val ANONYMOUS_CHEER = notification(
        "channel.cheer",
        1,
        """{"is_anonymous":true,"user_id":null,"user_login":null,"user_name":null,"broadcaster_user_id":"1337","broadcaster_user_login":"cooler_user","broadcaster_user_name":"Cooler_User","message":"","bits":50}""",
    )

    val REDEMPTION = notification(
        "channel.channel_points_custom_reward_redemption.add",
        1,
        """{"id":"17fa2df1-ad76-4804-bfa5-a40ef63efe63","broadcaster_user_id":"1337","broadcaster_user_login":"cooler_user","broadcaster_user_name":"Cooler_User","user_id":"9001","user_login":"cooler_user","user_name":"Cooler_User","user_input":"pogchamp","status":"unfulfilled","reward":{"id":"92af127c-7326-4483-a52b-b0da0be61c01","title":"Hydrate","cost":100,"prompt":"Drink some water"},"redeemed_at":"2026-11-10T03:32:30.0Z"}""",
    )

    fun chat(noticeType: String, fields: String, chatter: String = """"chatter_user_name":"Cool_User","chatter_is_anonymous":false""", source: String = """"source_broadcaster_user_id":null"""): String =
        notification(
            "channel.chat.notification",
            1,
            """{"broadcaster_user_id":"1337","broadcaster_user_login":"cooler_user","broadcaster_user_name":"Cooler_User","chatter_user_id":"1234","chatter_user_login":"cool_user",$chatter,"color":"#FF4500","badges":[{"set_id":"subscriber","id":"12","info":"14"}],"system_message":"Cool_User subscribed","message_id":"c1f0d7a4-3b2e-4e5a-9c1b-2f9d7a1b3e44","message":{"text":"","fragments":[]},"notice_type":"$noticeType",$source,$fields}""",
        )

    fun chatWithMessage(noticeType: String, fields: String, text: String): String =
        notification(
            "channel.chat.notification",
            1,
            """{"broadcaster_user_id":"1337","chatter_user_name":"Cool_User","chatter_is_anonymous":false,"color":"#FF4500","badges":[],"message":{"text":"$text","fragments":[{"type":"text","text":"$text","cheermote":null,"emote":null,"mention":null}]},"notice_type":"$noticeType","source_broadcaster_user_id":null,$fields}""",
        )
}
