package com.opirl.app.widgets.alerts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatBotTest {
    @Test
    fun readsTheAlertNameOutOfTheChatMessage() {
        val cases = mapOf(
            "!opirl alert hello" to "hello",
            "  !OPIRL   Alert   Hello  " to "Hello",
            "!opirl alert \"two words\"" to "two words",
            "!opirl alert \"one\"" to "one",
            "!opirl alert \"\"" to "",
            "!opirl alert hello extra words" to "hello",
        )
        for ((text, name) in cases) assertEquals(name, parseAlertCommand(text), text)
    }

    @Test
    fun ignoresEverythingElse() {
        val ignored = listOf(
            "",
            "hello",
            "!opirl",
            "!opirl alert",
            "!opirl alert \"never closed",
            "!opirl tts on",
            "opirl alert hello",
            "!other alert hello",
            "hi !opirl alert hello",
        )
        for (text in ignored) assertNull(parseAlertCommand(text), text)
    }

    private val owner = ChatSender(broadcaster = true, moderator = false, subscriber = false)
    private val moderator = ChatSender(broadcaster = false, moderator = true, subscriber = false)
    private val subscriber = ChatSender(broadcaster = false, moderator = false, subscriber = true)
    private val viewer = ChatSender(broadcaster = false, moderator = false, subscriber = false)

    @Test
    fun theOwnerAlwaysMayAndTheDefaultsLetModeratorsIn() {
        val defaults = AlertPermissions()
        assertTrue(checkChatCommand(defaults, owner, 1000, null).allowed)
        assertTrue(checkChatCommand(AlertPermissions(moderators = false), owner, 1000, null).allowed)
        assertTrue(checkChatCommand(defaults, moderator, 1000, null).allowed)
        assertFalse(checkChatCommand(AlertPermissions(moderators = false), moderator, 1000, null).allowed)
        assertFalse(checkChatCommand(defaults, subscriber, 1000, null).allowed)
        assertFalse(checkChatCommand(defaults, viewer, 1000, null).allowed)
    }

    @Test
    fun subscribersAndEveryoneNeedTheirOwnSwitch() {
        val subscribers = AlertPermissions(subscribers = true)
        assertTrue(checkChatCommand(subscribers, subscriber, 0, null).allowed)
        assertFalse(checkChatCommand(subscribers, viewer, 0, null).allowed)
        val everyone = AlertPermissions(others = true)
        assertTrue(checkChatCommand(everyone, viewer, 0, null).allowed)
        assertTrue(checkChatCommand(everyone, subscriber, 0, null).allowed)
    }

    @Test
    fun theCooldownOnlyHoldsBackSubscribersAndEveryone() {
        val permissions = AlertPermissions(subscribers = true, others = true, cooldown = 10)
        val first = checkChatCommand(permissions, viewer, 100_000, null)
        assertTrue(first.allowed)
        assertEquals(100_000L, first.lastExecutedMs)
        val soon = checkChatCommand(permissions, viewer, 110_000, first.lastExecutedMs)
        assertFalse(soon.allowed)
        assertEquals(100_000L, soon.lastExecutedMs)
        assertTrue(checkChatCommand(permissions, viewer, 110_001, first.lastExecutedMs).allowed)
        assertTrue(checkChatCommand(permissions, moderator, 101_000, first.lastExecutedMs).allowed)
        assertTrue(checkChatCommand(permissions, owner, 101_000, first.lastExecutedMs).allowed)
    }

    @Test
    fun anOwnerOrModeratorStartsTheCooldownForEveryoneElse() {
        val permissions = AlertPermissions(others = true, cooldown = 30)
        val byOwner = checkChatCommand(permissions, owner, 50_000, null)
        assertFalse(checkChatCommand(permissions, viewer, 60_000, byOwner.lastExecutedMs).allowed)
        assertTrue(checkChatCommand(permissions, viewer, 80_001, byOwner.lastExecutedMs).allowed)
    }
}
