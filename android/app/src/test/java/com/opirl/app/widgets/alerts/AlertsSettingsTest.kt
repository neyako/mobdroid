package com.opirl.app.widgets.alerts

import com.opirl.app.settings.TWITCH_CHAT_SCOPES
import com.opirl.app.settings.TwitchAccount
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.chat.TWITCH_ALERT_SCOPES
import com.opirl.app.chat.missingTwitchScopes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

class AlertsSettingsTest {
    @Test
    fun everyBundledImageAndSoundIsShipped() {
        for (image in BUNDLED_IMAGES) assertTrue(File("src/main/assets/alerts/images/${image.id}.gif").isFile, image.id)
        for (sound in BUNDLED_SOUNDS) assertTrue(File("src/main/assets/alerts/sounds/${sound.id}.mp3").isFile, sound.id)
        assertEquals(BUNDLED_IMAGES.size, BUNDLED_IMAGES.map { it.id }.toSet().size)
        assertEquals(BUNDLED_SOUNDS.size, BUNDLED_SOUNDS.map { it.id }.toSet().size)
    }

    @Test
    fun startsWithMoblinsDefaults() {
        val alert = AlertSettings()
        assertEquals(true, alert.enabled)
        assertEquals("moblin_pixels", alert.image)
        assertEquals("notification_2", alert.sound)
        assertEquals(1, alert.loops)
        assertEquals(0xFFFFFFFF.toInt(), alert.textColor)
        assertEquals(0xFFFDFB67.toInt(), alert.accentColor)
        assertEquals(45, alert.fontSize)
        assertEquals(AlertFontDesign.MONOSPACED, alert.fontDesign)
        assertEquals(AlertFontWeight.BOLD, alert.fontWeight)
        assertEquals(true, alert.speech)
        assertEquals(1500, alert.speechDelayMs)
        val settings = AlertsSettings()
        assertEquals(listOf(1), settings.twitch.cheers.map { it.amount })
        assertEquals(AlertComparison.GREATER_EQUAL, settings.twitch.cheers.single().comparison)
        assertEquals(listOf(1), settings.kick.kicks.map { it.amount })
    }

    @Test
    fun keepsWhatWasSavedAndFillsInWhatAnOlderFileLacked() {
        val settings = AlertsSettings(
            twitch = TwitchAlerts(follows = AlertSettings(image = "angry", fontSize = 60, textColor = 0xFF112233.toInt())),
            chatBot = ChatBotAlerts(listOf(AlertCommand("1", "hello")), AlertPermissions(others = true, cooldown = 20)),
            images = listOf(AlertMedia("a", "mine", "widget-1")),
        )
        val widget = Widget(id = "w", type = WidgetType.ALERTS, alerts = settings)
        assertEquals(widget, json.decodeFromString(Widget.serializer(), json.encodeToString(Widget.serializer(), widget)))
        val old = json.decodeFromString(
            Widget.serializer(),
            """{"id":"w","type":"ALERTS","alerts":{"twitch":{"follows":{"fontSize":30,"unknown":1}},"gone":true}}""",
        )
        assertEquals(30, old.alerts.twitch.follows.fontSize)
        assertEquals(AlertSettings().copy(fontSize = 30), old.alerts.twitch.follows)
        assertEquals(AlertSettings(), old.alerts.twitch.subscriptions)
        assertEquals(1, old.alerts.twitch.cheers.size)
        assertEquals(emptyList(), old.alerts.chatBot.commands)
    }

    @Test
    fun aLoginFromBeforeTheAlertsHasOnlyTheChatScopes() {
        val old = json.decodeFromString(TwitchAccount.serializer(), """{"login":"me","userId":"1","accessToken":"t"}""")
        assertEquals(TWITCH_CHAT_SCOPES, old.scopes)
        assertEquals(TWITCH_ALERT_SCOPES, missingTwitchScopes(old))
        val fresh = old.copy(scopes = TWITCH_CHAT_SCOPES + TWITCH_ALERT_SCOPES)
        assertEquals(emptyList(), missingTwitchScopes(fresh))
        assertEquals(listOf("bits:read"), missingTwitchScopes(old.copy(scopes = TWITCH_CHAT_SCOPES + listOf("moderator:read:followers", "channel:read:redemptions"))))
    }
}
