package com.opirl.app.widgets.alerts

import java.util.UUID
import kotlinx.serialization.Serializable

class BundledMedia(val id: String, val name: String)

val BUNDLED_IMAGES = listOf(
    BundledMedia("moblin_pixels", "Moblin pixels"),
    BundledMedia("moblin_party", "Moblin party"),
    BundledMedia("moblin_trillionaire", "Moblin trillionaire"),
    BundledMedia("white_star", "White star"),
    BundledMedia("angry", "Angry"),
    BundledMedia("sunglasses", "Sunglasses"),
    BundledMedia("salty", "Salty"),
    BundledMedia("minus_100", "-100"),
)

val BUNDLED_SOUNDS = listOf(
    BundledMedia("notification_2", "Notification 2"),
    BundledMedia("boing", "Boing"),
    BundledMedia("cash_register", "Cash register"),
    BundledMedia("dingaling", "Dingaling"),
    BundledMedia("level_up", "Level up"),
    BundledMedia("notification", "Notification"),
    BundledMedia("sfx_magic", "SFX magic"),
    BundledMedia("whoosh", "Whoosh"),
    BundledMedia("coin_dropping", "Coin dropping"),
    BundledMedia("fart", "Fart"),
    BundledMedia("fart_2", "Fart 2"),
    BundledMedia("bad_chili_fart", "Bad chili fart"),
    BundledMedia("perfect_fart", "Perfect fart"),
    BundledMedia("silence", "Silence"),
)

@Serializable
enum class AlertFontDesign { DEFAULT, SERIF, ROUNDED, MONOSPACED }

@Serializable
enum class AlertFontWeight { REGULAR, LIGHT, BOLD }

@Serializable
enum class AlertComparison(val symbol: String) {
    EQUAL("="),
    GREATER_EQUAL(">="),
}

@Serializable
data class AlertSettings(
    val enabled: Boolean = true,
    val image: String = BUNDLED_IMAGES.first().id,
    val loops: Int = 1,
    val sound: String = BUNDLED_SOUNDS.first().id,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val accentColor: Int = 0xFFFDFB67.toInt(),
    val fontSize: Int = 45,
    val fontDesign: AlertFontDesign = AlertFontDesign.MONOSPACED,
    val fontWeight: AlertFontWeight = AlertFontWeight.BOLD,
    val speech: Boolean = true,
    val speechDelayMs: Int = 1500,
)

@Serializable
data class AlertThreshold(
    val id: String = UUID.randomUUID().toString(),
    val amount: Int = 1,
    val comparison: AlertComparison = AlertComparison.GREATER_EQUAL,
    val alert: AlertSettings = AlertSettings(),
)

@Serializable
data class AlertCommand(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "myname",
    val alert: AlertSettings = AlertSettings(),
)

@Serializable
data class AlertPermissions(
    val moderators: Boolean = true,
    val subscribers: Boolean = false,
    val others: Boolean = false,
    val cooldown: Int = 0,
)

@Serializable
data class AlertMedia(val id: String = UUID.randomUUID().toString(), val name: String = "", val file: String = "")

@Serializable
data class TwitchAlerts(
    val follows: AlertSettings = AlertSettings(),
    val subscriptions: AlertSettings = AlertSettings(),
    val raids: AlertSettings = AlertSettings(),
    val cheers: List<AlertThreshold> = listOf(AlertThreshold(id = "first")),
    val redemptions: AlertSettings = AlertSettings(),
)

@Serializable
data class KickAlerts(
    val subscriptions: AlertSettings = AlertSettings(),
    val giftedSubscriptions: AlertSettings = AlertSettings(),
    val hosts: AlertSettings = AlertSettings(),
    val rewards: AlertSettings = AlertSettings(),
    val kicks: List<AlertThreshold> = listOf(AlertThreshold(id = "first")),
)

@Serializable
data class ChatBotAlerts(
    val commands: List<AlertCommand> = emptyList(),
    val permissions: AlertPermissions = AlertPermissions(),
)

@Serializable
data class AlertsSettings(
    val twitch: TwitchAlerts = TwitchAlerts(),
    val kick: KickAlerts = KickAlerts(),
    val chatBot: ChatBotAlerts = ChatBotAlerts(),
    val images: List<AlertMedia> = emptyList(),
    val sounds: List<AlertMedia> = emptyList(),
)

fun AlertsSettings.imageFile(id: String): String? = images.firstOrNull { it.id == id }?.file

fun AlertsSettings.soundFile(id: String): String? = sounds.firstOrNull { it.id == id }?.file

fun AlertsSettings.imageName(id: String): String =
    BUNDLED_IMAGES.firstOrNull { it.id == id }?.name ?: images.firstOrNull { it.id == id }?.name.orEmpty()

fun AlertsSettings.soundName(id: String): String =
    BUNDLED_SOUNDS.firstOrNull { it.id == id }?.name ?: sounds.firstOrNull { it.id == id }?.name.orEmpty()

fun AlertsSettings.mapAlerts(transform: (AlertSettings) -> AlertSettings): AlertsSettings = copy(
    twitch = twitch.copy(
        follows = transform(twitch.follows),
        subscriptions = transform(twitch.subscriptions),
        raids = transform(twitch.raids),
        cheers = twitch.cheers.map { it.copy(alert = transform(it.alert)) },
        redemptions = transform(twitch.redemptions),
    ),
    kick = kick.copy(
        subscriptions = transform(kick.subscriptions),
        giftedSubscriptions = transform(kick.giftedSubscriptions),
        hosts = transform(kick.hosts),
        rewards = transform(kick.rewards),
        kicks = kick.kicks.map { it.copy(alert = transform(it.alert)) },
    ),
    chatBot = chatBot.copy(commands = chatBot.commands.map { it.copy(alert = transform(it.alert)) }),
)

fun AlertsSettings.withoutImage(id: String): AlertsSettings = copy(images = images.filterNot { it.id == id }).mapAlerts {
    if (it.image == id) it.copy(image = BUNDLED_IMAGES.first().id) else it
}

fun AlertsSettings.withoutSound(id: String): AlertsSettings = copy(sounds = sounds.filterNot { it.id == id }).mapAlerts {
    if (it.sound == id) it.copy(sound = BUNDLED_SOUNDS.first().id) else it
}
