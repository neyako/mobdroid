package app.mobdroid.engine.chat.twitch

import kotlin.test.Test
import kotlin.test.assertEquals

class TwitchSendTest {
    private class Case(val code: Int, val body: String, val expected: String?)

    @Test
    fun helixAnswersBecomeShortErrors() {
        val sent = """{"data":[{"message_id":"abc-123","is_sent":true}]}"""
        val duplicate = """{"data":[{"message_id":"","is_sent":false,""" +
            """"drop_reason":{"code":"msg_duplicate","message":"Your message is a duplicate"}}]}"""
        val cases = listOf(
            Case(200, sent, null),
            Case(200, "", null),
            Case(200, duplicate, "Your message is a duplicate"),
            Case(200, """{"data":[{"message_id":"","is_sent":false}]}""", "Twitch did not send the message"),
            Case(401, """{"error":"Unauthorized","status":401,"message":"Invalid OAuth token"}""", "Twitch login expired"),
            Case(
                403,
                """{"error":"Forbidden","status":403,"message":"The sender is not permitted to chat here"}""",
                "Twitch: The sender is not permitted to chat here",
            ),
            Case(429, """{"error":"Too Many Requests","status":429}""", "Twitch error 429"),
            Case(502, "<html>Bad Gateway</html>", "Twitch error 502"),
        )
        for (case in cases) {
            assertEquals(case.expected, twitchSendError(case.code, case.body), "${case.code} ${case.body}")
        }
    }

    @Test
    fun messagesAreCutAtFiveHundredCodePointsWithoutSplittingAnEmoji() {
        val exact = "a".repeat(500)
        assertEquals(exact, truncateMessage(exact))
        assertEquals(exact, truncateMessage(exact + "b"))
        val emojiAtTheEdge = "a".repeat(499) + "😀" + "b"
        assertEquals("a".repeat(499) + "😀", truncateMessage(emojiAtTheEdge))
        assertEquals("😀".repeat(500), truncateMessage("😀".repeat(600)))
    }

    @Test
    fun reconnectDelayDoublesUpToACapAndJitterOnlyAddsAQuarter() {
        val delays = (0..7).map { backoffMs(it, 0.0) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
        assertEquals(30_000L, backoffMs(Int.MAX_VALUE, 0.0))
        assertEquals(1_250L, backoffMs(0, 1.0))
        assertEquals(37_500L, backoffMs(9, 1.0))
    }
}
