package com.opirl.app.engine.chat.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatBotTest {
    @Test
    fun parsesCommands() {
        val cases = mapOf(
            "!opirl help" to BotAction.Help,
            "!OPIRL tts ON" to BotAction.Tts(true),
            "!opirl tts off" to BotAction.Tts(false),
            "!opirl say  xin chào  mọi người" to BotAction.Say("xin chào mọi người"),
            "!opirl snapshot" to BotAction.Snapshot,
            "!opirl mute" to BotAction.Mute(true),
            "!opirl unmute" to BotAction.Mute(false),
            "!opirl zoom 2.5" to BotAction.Zoom(2.5f),
            "!opirl torch on" to BotAction.Torch(true),
            "!opirl scene Front" to BotAction.Scene("Front"),
            "!opirl scene \"Back camera\" now" to BotAction.Scene("Back camera"),
            "!opirl stream start" to BotAction.Stream(true),
            "!opirl stream stop" to BotAction.Stream(false),
            "!opirl stream title Night market walk" to BotAction.StreamTitle("Night market walk"),
            "!opirl stream category Just Chatting" to BotAction.StreamCategory("Just Chatting"),
            "!opirl widget \"My clock\" disable" to BotAction.Widget("My clock", false),
            "!opirl widget Chat enable" to BotAction.Widget("Chat", true),
            "!opirl send Follow the channel" to BotAction.Send("Follow the channel"),
        )
        assertEquals(cases.values.toList(), cases.keys.map(::parseBotCommand))
    }

    @Test
    fun ignoresEverythingElse() {
        val texts = listOf(
            "hello !opirl help",
            "!moblin help",
            "!opirl",
            "!opirl dance",
            "!opirl tts maybe",
            "!opirl zoom fast",
            "!opirl zoom NaN",
            "!opirl say",
            "!opirl scene \"unterminated name",
            "!opirl widget Clock",
            "!opirl stream",
        )
        assertEquals(texts.map { null }, texts.map(::parseBotCommand))
    }

    @Test
    fun ownerAndModeratorsSkipTheCooldown() {
        val gate = BotGate()
        val permission = BotPermission(others = true, cooldownSeconds = 30)
        fun check(owner: Boolean = false, moderator: Boolean = false, now: Long) =
            gate.check(BotCommand.SNAPSHOT, permission, owner, moderator, false, now)
        assertEquals(BotDecision.Allowed, check(now = 0))
        assertEquals(BotDecision.Cooldown(30), check(now = 1))
        assertEquals(BotDecision.Allowed, check(moderator = true, now = 5_000))
        assertEquals(BotDecision.Cooldown(26), check(now = 9_500))
        assertEquals(BotDecision.Allowed, check(owner = true, now = 10_000))
        assertEquals(BotDecision.Allowed, check(now = 40_000))
        assertEquals(BotDecision.Allowed, gate.check(BotCommand.ZOOM, permission, false, false, false, 40_001))
    }

    @Test
    fun allowsOnlyTheEnabledRoles() {
        fun check(permission: BotPermission, moderator: Boolean = false, subscriber: Boolean = false) =
            BotGate().check(BotCommand.SCENE, permission, false, moderator, subscriber, 0)
        assertEquals(BotDecision.Denied, check(BotPermission()))
        assertEquals(BotDecision.Allowed, check(BotPermission(), moderator = true))
        assertEquals(BotDecision.Denied, check(BotPermission(moderators = false), moderator = true))
        assertEquals(BotDecision.Denied, check(BotPermission(), subscriber = true))
        assertEquals(BotDecision.Allowed, check(BotPermission(subscribers = true), subscriber = true))
        assertEquals(BotDecision.Allowed, check(BotPermission(moderators = false, others = true), moderator = true))
        assertEquals(BotDecision.Allowed, BotGate().check(BotCommand.SCENE, BotPermission(moderators = false), true, false, false, 0))
        assertNull(BotAction.Help.command)
    }
}
