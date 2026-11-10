package app.mobdroid.engine.chat.youtube

import app.mobdroid.engine.chat.ChatMessage
import app.mobdroid.engine.chat.ChatPlatform
import app.mobdroid.engine.chat.ChatSegment
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class YouTubeChatParserTest {
    private val now = 42L

    private fun events(vararg actions: String): List<LiveChatEvent> =
        parseLiveChat(liveChatResponse(actions.toList()), now).events

    private fun messages(vararg actions: String): List<ChatMessage> =
        events(*actions).map { (it as LiveChatEvent.Message).message }

    private fun message(
        id: String,
        segments: List<ChatSegment>,
        user: String = "@viewer",
        userId: String? = "UCviewer00000000000000A1",
        timestampMs: Long = 1794281825437,
        isBroadcaster: Boolean = false,
        isModerator: Boolean = false,
        isSubscriber: Boolean = false,
    ) = ChatMessage(
        platform = ChatPlatform.YOUTUBE,
        id = id,
        userId = userId,
        user = user,
        userColor = null,
        segments = segments,
        timestampMs = timestampMs,
        isBroadcaster = isBroadcaster,
        isModerator = isModerator,
        isSubscriber = isSubscriber,
    )

    @Test
    fun textMessageKeepsAuthorIdAndTimestamp() {
        val parsed = messages(addAction(textMessage("m1", runs = textRuns(textRun("speed Apa kamu punya uang")))))
        assertEquals(listOf(message("m1", listOf(ChatSegment.Text("speed Apa kamu punya uang")))), parsed)
    }

    @Test
    fun emojiRunsBecomeEmotesBetweenMergedText() {
        val runs = textRuns(textRun("ha "), textRun("nahi "), STANDARD_EMOJI, CUSTOM_EMOJI, textRun(" done"))
        val parsed = messages(addAction(textMessage("m1", runs = runs)))
        assertEquals(
            listOf(
                ChatSegment.Text("ha nahi "),
                ChatSegment.Emote("rolling_on_the_floor_laughing", "https://fonts.gstatic.com/s/e/notoemoji/17.0/1f923/72.png"),
                ChatSegment.Emote("yt", "https://yt3.ggpht.com/IkpeJf1gzAN6=w48-h48-c-k-nd"),
                ChatSegment.Text(" done"),
            ),
            parsed.single().segments,
        )
        assertEquals("ha nahi rolling_on_the_floor_laughingyt done", parsed.single().text)
    }

    @Test
    fun emojiWithoutImageIsPlainText() {
        val standard = """{"emoji":{"emojiId":"🤣","shortcuts":[":rolling_on_the_floor_laughing:"],"image":{"thumbnails":[]}}}"""
        val custom = """{"emoji":{"emojiId":"UCkszU2WH9gy1mb0dV-11UJg/abc","shortcuts":[":yt:"],"isCustomEmoji":true}}"""
        val parsed = messages(addAction(textMessage("m1", runs = textRuns(standard, textRun(" "), custom))))
        assertEquals(listOf(ChatSegment.Text("🤣 :yt:")), parsed.single().segments)
    }

    @Test
    fun badgesSetBroadcasterModeratorAndSubscriber() {
        val parsed = messages(
            addAction(textMessage("owner", badges = OWNER_BADGES)),
            addAction(textMessage("mod", badges = MODERATOR_BADGES)),
            addAction(textMessage("member", badges = MEMBER_BADGES)),
            addAction(textMessage("verified", badges = VERIFIED_BADGES)),
            addAction(textMessage("none")),
        )
        assertEquals(
            listOf(
                Triple(true, false, false),
                Triple(false, true, false),
                Triple(false, false, true),
                Triple(false, false, false),
                Triple(false, false, false),
            ),
            parsed.map { Triple(it.isBroadcaster, it.isModerator, it.isSubscriber) },
        )
    }

    @Test
    fun superChatAndStickerShowTheirAmountBeforeTheMessage() {
        val paid = """{"liveChatPaidMessageRenderer":{"id":"p1","timestampUsec":"1794281920000000","authorName":{"simpleText":"@fan"},"purchaseAmountText":{"simpleText":"$5.00"},"message":{"runs":[{"text":"great stream"}]},"headerBackgroundColor":4278239141,"authorExternalChannelId":"UCfan0000000000000000A1","isV2Style":true}}"""
        val paidEmoji = """{"liveChatPaidMessageRenderer":{"id":"p2","authorName":{"simpleText":"@fan"},"purchaseAmountText":{"simpleText":"$5.00"},"message":{"runs":[$CUSTOM_EMOJI]}}}"""
        val sticker = """{"liveChatPaidStickerRenderer":{"id":"p3","authorName":{"simpleText":"@fan"},"purchaseAmountText":{"simpleText":"€2.00"},"sticker":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/sticker"}]}}}"""
        val noAmount = """{"liveChatPaidMessageRenderer":{"id":"p4","authorName":{"simpleText":"@fan"}}}"""
        val parsed = messages(addAction(paid), addAction(paidEmoji), addAction(sticker), addAction(noAmount))
        assertEquals(
            listOf(
                listOf(ChatSegment.Text("sent a $5.00 Super Chat! great stream")),
                listOf(
                    ChatSegment.Text("sent a $5.00 Super Chat! "),
                    ChatSegment.Emote("yt", "https://yt3.ggpht.com/IkpeJf1gzAN6=w48-h48-c-k-nd"),
                ),
                listOf(ChatSegment.Text("sent a €2.00 Super Sticker!")),
                listOf(ChatSegment.Text("sent a Super Chat!")),
            ),
            parsed.map { it.segments },
        )
        assertEquals(1794281920000, parsed[0].timestampMs)
        assertEquals(now, parsed[1].timestampMs)
        assertNull(parsed[1].userId)
    }

    @Test
    fun membershipAndGiftAnnouncementsAreMessages() {
        val membership = """{"liveChatMembershipItemRenderer":{"id":"g1","timestampUsec":"1794281900000000","authorExternalChannelId":"UCmember000000000000000A1","headerSubtext":{"runs":[{"text":"Welcome to "},{"text":"Test Channel"},{"text":"!"}]},"message":{"runs":[{"text":"thanks"}]},"authorName":{"simpleText":"@new_member"},"authorBadges":$MEMBER_BADGES}}"""
        val purchase = """{"liveChatSponsorshipsGiftPurchaseAnnouncementRenderer":{"id":"g2","timestampUsec":"1794281910000000","authorExternalChannelId":"UCgifter000000000000000A1","header":{"liveChatSponsorshipsHeaderRenderer":{"authorName":{"simpleText":"@gifter"},"primaryText":{"runs":[{"text":"Gifted "},{"text":"5","bold":true},{"text":" "},{"text":"Test Channel","bold":true},{"text":" memberships"}]},"authorBadges":$MODERATOR_BADGES}}}}"""
        val redemption = """{"liveChatSponsorshipsGiftRedemptionAnnouncementRenderer":{"id":"g3","timestampUsec":"1794281915000000","authorExternalChannelId":"UClucky0000000000000000A1","authorName":{"simpleText":"@lucky"},"message":{"runs":[{"text":"received a gift membership by "},{"text":"@gifter","bold":true}]}}}"""
        val jewels = """{"giftMessageViewModel":{"text":{"content":"sent Go team!","styleRuns":[{"startIndex":0,"length":13}]},"authorName":{"content":"@gift_fan ","styleRuns":[{"startIndex":0,"length":10}]},"id":"g4","giftImage":{"sources":[{"url":"//www.gstatic.com/youtube/img/gift.png","width":480,"height":480}]},"giftImageA11yLabel":"@gift_fan sent a gift, Go team!"}}"""
        val parsed = messages(addAction(membership), addAction(purchase), addAction(redemption), addAction(jewels))
        assertEquals(
            listOf(
                message(
                    "g1", listOf(ChatSegment.Text("Welcome to Test Channel! thanks")), "@new_member",
                    "UCmember000000000000000A1", 1794281900000, isSubscriber = true,
                ),
                message(
                    "g2", listOf(ChatSegment.Text("Gifted 5 Test Channel memberships")), "@gifter",
                    "UCgifter000000000000000A1", 1794281910000, isModerator = true,
                ),
                message(
                    "g3", listOf(ChatSegment.Text("received a gift membership by @gifter")), "@lucky",
                    "UClucky0000000000000000A1", 1794281915000,
                ),
                message("g4", listOf(ChatSegment.Text("sent Go team!")), "@gift_fan", null, now),
            ),
            parsed,
        )
    }

    @Test
    fun deletionActionsKeepTheirOrderAmongMessages() {
        val parsed = events(
            addAction(textMessage("m1")),
            """{"clickTrackingParams":"CAEQ","markChatItemAsDeletedAction":{"deletedStateMessage":{"runs":[{"text":"[message deleted]","italics":true}]},"targetItemId":"m1"}}""",
            """{"markChatItemsByAuthorAsDeletedAction":{"deletedStateMessage":{"runs":[{"text":"[message retracted]","italics":true}]},"externalChannelId":"UCbanned000000000000000A1"}}""",
            """{"removeChatItemAction":{"targetItemId":"m2"}}""",
            """{"removeChatItemByAuthorAction":{"externalChannelId":"UCtimedout0000000000000A1"}}""",
        )
        assertEquals(
            listOf(
                LiveChatEvent.Message(message("m1", listOf(ChatSegment.Text("hello")))),
                LiveChatEvent.MessageDeleted("m1"),
                LiveChatEvent.UserMessagesDeleted("UCbanned000000000000000A1"),
                LiveChatEvent.MessageDeleted("m2"),
                LiveChatEvent.UserMessagesDeleted("UCtimedout0000000000000A1"),
            ),
            parsed,
        )
    }

    @Test
    fun otherActionsAndUnusableMessagesAreSkipped() {
        val noAuthor = """{"liveChatTextMessageRenderer":{"id":"x","message":{"runs":[{"text":"anonymous"}]}}}"""
        val parsed = messages(
            """{"updateLiveChatPollAction":{"pollToUpdate":{"pollRenderer":{"liveChatPollId":"poll"}}}}""",
            """{"updateOrAddInteractivityWidgetAction":{"widgetRenderer":{"interactivityWidgetRenderer":{"id":"w"}}}}""",
            addAction("""{"liveChatViewerEngagementMessageRenderer":{"id":"e","message":{"runs":[{"text":"Welcome to live chat!"}]}}}"""),
            addAction(textMessage("empty", runs = "[]")),
            addAction(textMessage("blank", runs = textRuns(textRun("  ")))),
            addAction(noAuthor),
            addAction(textMessage("kept")),
        )
        assertEquals(listOf("kept"), parsed.map { it.id })
    }

    @Test
    fun continuationComesFromInvalidationOrTimedData() {
        val actions = listOf(addAction(textMessage("m1")))
        assertEquals("a", parseLiveChat(liveChatResponse(actions, "a"), now).continuation)
        assertEquals("b", parseLiveChat(liveChatResponse(actions, "b", "timedContinuationData"), now).continuation)
    }

    @Test
    fun responsesWithoutContinuationStillDeliverTheirMessages() {
        val page = parseLiveChat(liveChatResponse(listOf(addAction(textMessage("last"))), null), now)
        assertNull(page.continuation)
        assertEquals(1, page.events.size)
        val replay = liveChatResponse(listOf(), "r", "liveChatReplayContinuationData")
        assertNull(parseLiveChat(replay, now).continuation)
        val unrelated = parseLiveChat("""{"responseContext":{},"error":{"code":400}}""", now)
        assertEquals(0, unrelated.events.size)
        assertNull(unrelated.continuation)
    }

    @Test
    fun invalidJsonIsAnError() {
        assertFailsWith<SerializationException> { parseLiveChat("<html>", now) }
    }

    @Test
    fun pollDelayAimsForFiveMessagesPerPoll() {
        val timings = YouTubeChatTimings()
        val cases = listOf(
            Triple(2000L, 0, 3000L),
            Triple(2000L, 5, 2000L),
            Triple(2000L, 10, 1000L),
            Triple(1000L, 10, 500L),
            Triple(300L, 50, 200L),
            Triple(2000L, 1, 3000L),
        )
        for ((current, count, expected) in cases) {
            assertEquals(expected, nextPollDelayMs(current, count, timings), "$current ms, $count messages")
        }
    }

    @Test
    fun sourceIsAVideoIdOrAChannelHandle() {
        val cases = mapOf(
            "4t4AQ15yj1M" to YouTubeTarget.Video("4t4AQ15yj1M"),
            "-cc5bWM8zpg" to YouTubeTarget.Video("-cc5bWM8zpg"),
            "@LofiGirl" to YouTubeTarget.Channel("@LofiGirl"),
            "LofiGirl" to YouTubeTarget.Channel("@LofiGirl"),
            "  @neyako.live " to YouTubeTarget.Channel("@neyako.live"),
        )
        for ((source, expected) in cases) {
            assertEquals(expected, parseTarget(source), source)
        }
        listOf("", "  ", "@").forEach { assertFailsWith<IllegalArgumentException>("'$it'") { parseTarget(it) } }
    }

    @Test
    fun videoIdIsTheShortlinkOfTheChannelLivePage() {
        assertEquals("4t4AQ15yj1M", findVideoId(channelLivePage("4t4AQ15yj1M")))
        assertEquals("abc-DEF_123", findVideoId("""<link rel='shortlinkUrl' href='https://youtu.be/abc-DEF_123'>"""))
        assertNull(findVideoId(OFFLINE_CHANNEL_PAGE))
    }

    @Test
    fun chatPageContinuationIsTheFirstOneAndUnescaped() {
        assertEquals("0ofMyAO0AxqeAUNpa3FK%3D", findContinuation(chatPage("0ofMyAO0AxqeAUNpa3FK%3D")))
        assertEquals("abc==", findContinuation("""{"continuation":"abc=="}"""))
        assertNull(findContinuation(DISABLED_CHAT_PAGE))
    }
}
