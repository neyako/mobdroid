package app.mobdroid.engine.chat.youtube

internal const val CUSTOM_EMOJI = """{"emoji":{"emojiId":"UCkszU2WH9gy1mb0dV-11UJg/CIW60rKI74MDFfbywgodSoQNxA","shortcuts":[":yt:"],"searchTerms":["yt"],"image":{"thumbnails":[{"url":"https://yt3.ggpht.com/IkpeJf1gzAN6=w24-h24-c-k-nd","width":24,"height":24},{"url":"https://yt3.ggpht.com/IkpeJf1gzAN6=w48-h48-c-k-nd","width":48,"height":48}],"accessibility":{"accessibilityData":{"label":"yt"}}},"isCustomEmoji":true}}"""

internal const val STANDARD_EMOJI = """{"emoji":{"emojiId":"🤣","shortcuts":[":rolling_on_the_floor_laughing:"],"searchTerms":["rolling","on","the","floor","laughing"],"image":{"thumbnails":[{"url":"https://fonts.gstatic.com/s/e/notoemoji/17.0/1f923/72.png"}],"accessibility":{"accessibilityData":{"label":"🤣"}}}}}"""

internal const val OWNER_BADGES = """[{"liveChatAuthorBadgeRenderer":{"icon":{"iconType":"VERIFIED"},"tooltip":"Verified","accessibility":{"accessibilityData":{"label":"Verified"}}}},{"liveChatAuthorBadgeRenderer":{"icon":{"iconType":"OWNER"},"tooltip":"Owner","accessibility":{"accessibilityData":{"label":"Owner"}}}}]"""

internal const val MODERATOR_BADGES = """[{"liveChatAuthorBadgeRenderer":{"icon":{"iconType":"MODERATOR"},"tooltip":"Moderator","accessibility":{"accessibilityData":{"label":"Moderator"}}}}]"""

internal const val MEMBER_BADGES = """[{"liveChatAuthorBadgeRenderer":{"customThumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/memberbadge=s16-c-k","width":16,"height":16},{"url":"https://yt3.ggpht.com/memberbadge=s32-c-k","width":32,"height":32}]},"tooltip":"Member (6 months)","accessibility":{"accessibilityData":{"label":"Member (6 months)"}}}}]"""

internal const val VERIFIED_BADGES = """[{"liveChatAuthorBadgeRenderer":{"icon":{"iconType":"VERIFIED"},"tooltip":"Verified"}}]"""

internal fun textRuns(vararg runs: String): String = runs.joinToString(",", "[", "]")

internal fun textRun(text: String): String = """{"text":"$text"}"""

internal fun textMessage(
    id: String,
    user: String = "@viewer",
    channel: String = "UCviewer00000000000000A1",
    runs: String = textRuns(textRun("hello")),
    usec: Long = 1794281825437476,
    badges: String? = null,
): String {
    val badgeField = if (badges != null) ""","authorBadges":$badges""" else ""
    return """{"liveChatTextMessageRenderer":{"message":{"runs":$runs},"authorName":{"simpleText":"$user"},"authorPhoto":{"thumbnails":[{"url":"https://yt4.ggpht.com/a=s32-c-k-c0x00ffffff-no-rj","width":32,"height":32},{"url":"https://yt4.ggpht.com/a=s64-c-k-c0x00ffffff-no-rj","width":64,"height":64}]},"contextMenuEndpoint":{"clickTrackingParams":"CAEQl98BIhMIko7S","commandMetadata":{"webCommandMetadata":{"ignoreNavigation":true}},"liveChatItemContextMenuEndpoint":{"params":"Q2g0S0hBb2FRMHRxTFRBNVlUaGY"}},"id":"$id","timestampUsec":"$usec","authorExternalChannelId":"$channel","contextMenuAccessibility":{"accessibilityData":{"label":"Chat actions"}},"timestampText":{"simpleText":"9:17 PM"}$badgeField}}"""
}

internal fun addAction(item: String): String =
    """{"clickTrackingParams":"CAEQl98BIhMIko7So7z8lwMVC5zYBR2yLS2-","addChatItemAction":{"item":$item,"clientId":"CKj1_o7z8JcDFZPYBR0Fny0X"}}"""

internal fun liveChatResponse(
    actions: List<String>,
    continuation: String? = "tok-next",
    kind: String = "invalidationContinuationData",
): String {
    val continuations = if (continuation != null) {
        """[{"$kind":{"timeoutMs":10000,"continuation":"$continuation"}}]"""
    } else {
        "[]"
    }
    return """{"responseContext":{"serviceTrackingParams":[{"service":"CSI","params":[{"key":"c","value":"WEB"}]}]},"continuationContents":{"liveChatContinuation":{"continuations":$continuations,"actions":[${actions.joinToString(",")}]}},"frameworkUpdates":{"entityBatchUpdate":{"mutations":[]}}}"""
}

internal fun channelLivePage(videoId: String): String =
    """<!DOCTYPE html><html lang="en"><head><title>Test Channel - YouTube</title><link rel="canonical" href="https://www.youtube.com/watch?v=$videoId"><link rel="shortlinkUrl" href="https://youtu.be/$videoId"><link rel="alternate" href="android-app://com.google.android.youtube/http/youtube.com/watch?v=$videoId"></head><body></body></html>"""

internal const val OFFLINE_CHANNEL_PAGE =
    """<!DOCTYPE html><html lang="en"><head><title>Test Channel - YouTube</title><link rel="canonical" href="https://www.youtube.com/channel/UCtest0000000000000000A1"></head><body></body></html>"""

internal const val DISABLED_CHAT_PAGE =
    """<!DOCTYPE html><html><head><title>Live chat</title></head><body><script>window["ytInitialData"] = {"contents":{"messageRenderer":{"text":{"runs":[{"text":"Chat is disabled for this live stream."}]}}}};</script></body></html>"""

internal fun chatPage(continuation: String): String =
    """<!DOCTYPE html><html><head><title>Live chat</title></head><body><script>window["ytInitialData"] = {"contents":{"liveChatRenderer":{"continuations":[{"invalidationContinuationData":{"invalidationId":{"objectSource":1056,"objectId":"Y2hhdH40dDRBUTE1eWoxTQ==","topic":"chat~4t4AQ15yj1M","subscribeToGcmTopics":true,"protoCreationTimestampMs":"1794281718490"},"timeoutMs":10000,"continuation":"$continuation"}}],"header":{"liveChatHeaderRenderer":{"viewSelector":{"sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Top chat","selected":true,"continuation":{"reloadContinuationData":{"continuation":"reload-top"}}},{"title":"Live chat","continuation":{"reloadContinuationData":{"continuation":"reload-live"}}}]}}}}}}};</script></body></html>"""
