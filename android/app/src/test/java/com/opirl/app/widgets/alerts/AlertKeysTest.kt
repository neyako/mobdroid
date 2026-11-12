package com.opirl.app.widgets.alerts

import com.opirl.app.engine.alerts.AlertEvent
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Scene
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull

class AlertKeysTest {
    private val settings = AlertsSettings(
        twitch = TwitchAlerts(cheers = listOf(AlertThreshold("c1", 100), AlertThreshold("c2", 5))),
        kick = KickAlerts(kicks = listOf(AlertThreshold("k1", 7))),
        chatBot = ChatBotAlerts(listOf(AlertCommand("b1", "hello"), AlertCommand("b2", "bye"))),
    )

    private val keys = listOf(
        AlertKey.FOLLOWS,
        AlertKey.SUBSCRIPTIONS,
        AlertKey.RAIDS,
        AlertKey.REDEMPTIONS,
        AlertKey.KICK_SUBSCRIPTIONS,
        AlertKey.KICK_GIFTED,
        AlertKey.KICK_HOSTS,
        AlertKey.KICK_REWARDS,
        AlertKey.cheer("c1"),
        AlertKey.cheer("c2"),
        AlertKey.kicks("k1"),
        AlertKey.command("b1"),
        AlertKey.command("b2"),
    )

    @Test
    fun changesOnlyTheAlertTheKeyNames() {
        for (key in keys) {
            val marked = AlertSettings(fontSize = 77)
            val changed = settings.withAlert(key, marked)
            assertEquals(marked, changed.alert(key), key)
            for (other in keys - key) assertEquals(settings.alert(other), changed.alert(other), "$key changed $other")
        }
        assertNull(settings.alert("twitch.cheers.unknown"))
        assertNull(settings.alert("nonsense"))
        assertEquals(settings, settings.withAlert("nonsense", AlertSettings(fontSize = 1)))
    }

    @Test
    fun findsAndChangesThresholdsAndCommands() {
        assertEquals(5, settings.threshold(AlertKey.cheer("c2"))?.amount)
        assertEquals(7, settings.threshold(AlertKey.kicks("k1"))?.amount)
        assertNull(settings.threshold(AlertKey.FOLLOWS))
        val changed = settings.withThreshold(AlertKey.cheer("c2")) { it.copy(amount = 6, comparison = AlertComparison.EQUAL) }
        assertEquals(listOf(100, 6), changed.twitch.cheers.map { it.amount })
        assertEquals(AlertComparison.EQUAL, changed.twitch.cheers[1].comparison)
        assertEquals("bye", settings.command(AlertKey.command("b2"))?.name)
        assertEquals(listOf("hello", "later"), settings.withCommand(AlertKey.command("b2")) { it.copy(name = "later") }.chatBot.commands.map { it.name })
    }

    @Test
    fun theTestButtonPlaysAnEventOfTheRightKind() {
        val random = Random(7)
        assertIs<AlertEvent.TwitchFollow>(settings.testEvent(AlertKey.FOLLOWS, random))
        assertIs<AlertEvent.TwitchSubscribe>(settings.testEvent(AlertKey.SUBSCRIPTIONS, random))
        assertIs<AlertEvent.TwitchRaid>(settings.testEvent(AlertKey.RAIDS, random))
        assertIs<AlertEvent.TwitchRedemption>(settings.testEvent(AlertKey.REDEMPTIONS, random))
        assertIs<AlertEvent.KickSubscription>(settings.testEvent(AlertKey.KICK_SUBSCRIPTIONS, random))
        assertIs<AlertEvent.KickGiftedSubscriptions>(settings.testEvent(AlertKey.KICK_GIFTED, random))
        assertIs<AlertEvent.KickHost>(settings.testEvent(AlertKey.KICK_HOSTS, random))
        assertIs<AlertEvent.KickReward>(settings.testEvent(AlertKey.KICK_REWARDS, random))
        val cheer = assertIs<AlertEvent.TwitchCheer>(settings.testEvent(AlertKey.cheer("c1"), random))
        assertEquals(100, cheer.bits)
        val kicks = assertIs<AlertEvent.KickKicks>(settings.testEvent(AlertKey.kicks("k1"), random))
        assertEquals(7, kicks.amount)
        val command = assertIs<AlertEvent.ChatCommand>(settings.testEvent(AlertKey.command("b2"), random))
        assertEquals("bye", command.command)
        assertTrue(command.user in ALERT_TEST_NAMES)
        assertNull(settings.testEvent("nonsense", random))
    }

    @Test
    fun removingAMediaItemPutsTheDefaultBackWhereItWasUsed() {
        val withMedia = settings.copy(
            images = listOf(AlertMedia("img", "mine", "file-1")),
            sounds = listOf(AlertMedia("snd", "mine", "file-2")),
        ).withAlert(AlertKey.FOLLOWS, AlertSettings(image = "img", sound = "snd"))
            .withAlert(AlertKey.RAIDS, AlertSettings(image = "img", sound = "boing"))
        val withoutImage = withMedia.withoutImage("img")
        assertEquals(emptyList(), withoutImage.images)
        assertEquals(BUNDLED_IMAGES.first().id, withoutImage.alert(AlertKey.FOLLOWS)?.image)
        assertEquals("snd", withoutImage.alert(AlertKey.FOLLOWS)?.sound)
        assertEquals(BUNDLED_IMAGES.first().id, withoutImage.alert(AlertKey.RAIDS)?.image)
        val withoutSound = withMedia.withoutSound("snd")
        assertEquals(BUNDLED_SOUNDS.first().id, withoutSound.alert(AlertKey.FOLLOWS)?.sound)
        assertEquals("boing", withoutSound.alert(AlertKey.RAIDS)?.sound)
        assertEquals("img", withoutSound.alert(AlertKey.FOLLOWS)?.image)
    }

    @Test
    fun namesMediaByBundledNameOrByTheUsersName() {
        val withMedia = settings.copy(images = listOf(AlertMedia("img", "My frog", "f")), sounds = listOf(AlertMedia("snd", "My ding", "g")))
        assertEquals("Moblin pixels", withMedia.imageName("moblin_pixels"))
        assertEquals("My frog", withMedia.imageName("img"))
        assertEquals("", withMedia.imageName("gone"))
        assertEquals("Notification 2", withMedia.soundName("notification_2"))
        assertEquals("My ding", withMedia.soundName("snd"))
        assertEquals("g", withMedia.soundFile("snd"))
        assertNull(withMedia.imageFile("moblin_pixels"))
    }

    private fun widget(id: String, type: WidgetType, sceneId: String? = null) = Widget(id = id, type = type, sceneId = sceneId)

    @Test
    fun findsTheAlertsWidgetsTheSelectedSceneShows() {
        val widgets = listOf(
            widget("a1", WidgetType.ALERTS),
            widget("a2", WidgetType.ALERTS),
            widget("a3", WidgetType.ALERTS),
            widget("a4", WidgetType.ALERTS),
            widget("t", WidgetType.TEXT),
            widget("nested", WidgetType.SCENE, "second"),
            widget("loop", WidgetType.SCENE, "main"),
        )
        val scenes = listOf(
            Scene(
                id = "main",
                widgets = listOf(
                    SceneWidget("a1"),
                    SceneWidget("a2", enabled = false),
                    SceneWidget("t"),
                    SceneWidget("nested"),
                    SceneWidget("a1"),
                ),
            ),
            Scene(id = "second", widgets = listOf(SceneWidget("a3"), SceneWidget("loop"), SceneWidget("a1"))),
            Scene(id = "third", widgets = listOf(SceneWidget("a4"))),
        )
        val shown = placedAlertWidgets(AppSettings(widgets = widgets, scenes = scenes, selectedSceneId = "main"))
        assertEquals(listOf("a1", "a3"), shown.map { it.id })
        assertEquals(listOf("a4"), placedAlertWidgets(AppSettings(widgets = widgets, scenes = scenes, selectedSceneId = "third")).map { it.id })
        assertEquals(emptyList(), placedAlertWidgets(AppSettings(widgets = widgets)))
    }
}
