package com.opirl.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.chat.voiceService
import com.opirl.app.engine.chat.bot.BOT_PREFIX
import com.opirl.app.engine.chat.bot.BotCommand
import com.opirl.app.engine.chat.emotes.EmoteProvider
import com.opirl.app.engine.chat.twitch.TWITCH_SCOPES
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.ChatTextSize
import com.opirl.app.settings.PredefinedMessage
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.SpeechProvider
import com.opirl.app.settings.SpeechVoice
import com.opirl.app.stream.StreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RadioRow(shapes: ListItemShapes, title: String, selected: Boolean, onClick: () -> Unit) {
    SegmentedListItem(
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = onClick,
        shapes = shapes,
        modifier = Modifier.heightIn(max = RowHeight),
        leadingContent = { RadioButton(selected, null) },
    ) { Text(title, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
internal fun TwitchAccountPage(engine: StreamEngine) {
    val logins by engine.logins.logins.collectAsStateWithLifecycle()
    var loggingIn by remember { mutableStateOf(false) }
    val account = logins.twitch
    Group {
        if (account != null) {
            row { ValueRow(it, stringResource(R.string.logged_in_as), account.login) }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.log_out), Icons.Rounded.Close, MaterialTheme.colorScheme.error) {
                    engine.logins.update { it.copy(twitch = null) }
                }
            }
        }
        if (account == null || !account.scopes.containsAll(TWITCH_SCOPES)) {
            val label = if (account == null) R.string.log_in else R.string.log_in_again
            row { ActionRow(it, stringResource(label), Icons.Rounded.Add) { loggingIn = true } }
        }
    }
    if (account != null && !account.scopes.containsAll(TWITCH_SCOPES)) Note(stringResource(R.string.twitch_login_again))
    if (loggingIn) {
        TwitchLoginDialog(
            engine.http,
            onAccount = { added ->
                engine.logins.update { it.copy(twitch = added) }
                loggingIn = false
            },
            onClose = { loggingIn = false },
        )
    }
}

@Composable
internal fun KickAccountPage(engine: StreamEngine) {
    val logins by engine.logins.logins.collectAsStateWithLifecycle()
    var loggingIn by remember { mutableStateOf(false) }
    val account = logins.kick
    Group {
        if (account != null) {
            row { ValueRow(it, stringResource(R.string.logged_in_as), account.username) }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.log_out), Icons.Rounded.Close, MaterialTheme.colorScheme.error) {
                    engine.logins.update { it.copy(kick = null) }
                }
            }
        } else {
            row { ActionRow(it, stringResource(R.string.log_in), Icons.Rounded.Add) { loggingIn = true } }
        }
    }
    if (loggingIn) {
        KickLoginDialog(
            engine.http,
            onAccount = { added ->
                engine.logins.update { it.copy(kick = added) }
                loggingIn = false
            },
            onClose = { loggingIn = false },
        )
    }
}

@Composable
internal fun YouTubeAccountPage(engine: StreamEngine) {
    val logins by engine.logins.logins.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (engine.youtubeAuth.token(result.data) == null) {
            error = R.string.youtube_denied
        } else {
            engine.logins.update { it.copy(youtube = true) }
        }
    }
    Group {
        if (logins.youtube) {
            row { ValueRow(it, stringResource(R.string.youtube_account), stringResource(R.string.connected)) }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.log_out), Icons.Rounded.Close, MaterialTheme.colorScheme.error) {
                    engine.logins.update { it.copy(youtube = false) }
                }
            }
        } else {
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.log_in), Icons.Rounded.Add) {
                    error = null
                    scope.launch {
                        val access = engine.youtubeAuth.authorize()
                        when {
                            access?.consent != null -> consent.launch(IntentSenderRequest.Builder(access.consent).build())
                            access?.token != null -> engine.logins.update { it.copy(youtube = true) }
                            else -> error = R.string.youtube_unavailable
                        }
                    }
                }
            }
        }
    }
    error?.let { Note(stringResource(it)) }
}

private val EmoteProvider.label: String
    get() = when (this) {
        EmoteProvider.BTTV -> "BetterTTV"
        EmoteProvider.FFZ -> "FrankerFaceZ"
        EmoteProvider.SEVENTV -> "7TV"
    }

private val EmoteProvider.short: String
    get() = if (this == EmoteProvider.SEVENTV) "7TV" else name

@Composable
private fun speechLabel(provider: SpeechProvider): String = provider.label ?: stringResource(R.string.voice_system)

@Composable
internal fun ChatPage(store: SettingsStore, settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    val logins by engine.logins.logins.collectAsStateWithLifecycle()
    Group {
        row { shapes ->
            NavRow(shapes, stringResource(R.string.twitch_account), logins.twitch?.login ?: stringResource(R.string.not_logged_in)) {
                push(Route.TwitchAccount)
            }
        }
        row { shapes ->
            NavRow(
                shapes,
                stringResource(R.string.youtube_account),
                stringResource(if (logins.youtube) R.string.connected else R.string.not_logged_in),
            ) { push(Route.YouTubeAccount) }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.kick_account), logins.kick?.username ?: stringResource(R.string.not_logged_in)) {
                push(Route.KickAccount)
            }
        }
    }
    Group {
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.text_size), ChatTextSize.entries, settings.chatTextSize, { stringResource(it.label) }) { size ->
                store.update { it.copy(chatTextSize = size) }
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.chat_on_preview), settings.chatOnPreview) { on ->
                store.update { it.copy(chatOnPreview = on) }
            }
        }
    }
    Group {
        row { shapes ->
            val shown = EmoteProvider.entries - settings.disabledEmotes
            NavRow(shapes, stringResource(R.string.emotes), if (shown.isEmpty()) stringResource(R.string.off) else shown.joinToString { it.short }) {
                push(Route.Emotes)
            }
        }
        row { shapes ->
            NavRow(
                shapes,
                stringResource(R.string.chat_speech),
                if (settings.chatSpeech) speechLabel(settings.chatSpeechProvider) else stringResource(R.string.off),
            ) { push(Route.Speech) }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.predefined_messages), settings.predefinedMessages.size.takeIf { it > 0 }?.toString()) {
                push(Route.PredefinedMessages)
            }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.chat_bot), stringResource(if (settings.chatBot) R.string.on else R.string.off)) {
                push(Route.ChatBot)
            }
        }
    }
}

@Composable
internal fun EmotesPage(store: SettingsStore, settings: AppSettings) {
    Group {
        EmoteProvider.entries.forEach { provider ->
            row { shapes ->
                SwitchRow(shapes, provider.label, provider !in settings.disabledEmotes) { on ->
                    store.update { it.copy(disabledEmotes = if (on) it.disabledEmotes - provider else it.disabledEmotes + provider) }
                }
            }
        }
    }
}

@Composable
internal fun SpeechPage(store: SettingsStore, settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    val logins by engine.logins.logins.collectAsStateWithLifecycle()
    val provider = settings.chatSpeechProvider
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.chat_speech), settings.chatSpeech) { on -> store.update { it.copy(chatSpeech = on) } }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.chat_speech_names), settings.chatSpeechNames) { on ->
                store.update { it.copy(chatSpeechNames = on) }
            }
        }
    }
    Group {
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.speech_service), SpeechProvider.entries, provider, { speechLabel(it) }) { chosen ->
                store.update { it.copy(chatSpeechProvider = chosen) }
            }
        }
        if (provider != SpeechProvider.SYSTEM) {
            row { shapes ->
                val key = if (provider == SpeechProvider.ELEVENLABS) logins.elevenLabsKey else logins.ttsMonsterToken
                TextRow(shapes, stringResource(R.string.api_key), key, keyboard = KeyboardType.Password) { value ->
                    engine.logins.update {
                        if (provider == SpeechProvider.ELEVENLABS) it.copy(elevenLabsKey = value.trim()) else it.copy(ttsMonsterToken = value.trim())
                    }
                }
            }
            row { shapes ->
                NavRow(shapes, stringResource(R.string.voice), settings.chatSpeechVoices[provider]?.name ?: stringResource(R.string.not_set)) {
                    push(Route.SpeechVoices)
                }
            }
        }
        if (settings.chatSpeech) {
            row { shapes ->
                val sample = stringResource(R.string.voice_test_text)
                ActionRow(shapes, stringResource(R.string.test_voice), Icons.Rounded.PlayArrow) { engine.speech.say(sample) }
            }
        }
    }
}

@Composable
internal fun SpeechVoicesPage(store: SettingsStore, settings: AppSettings, engine: StreamEngine) {
    val logins by engine.logins.logins.collectAsStateWithLifecycle()
    val provider = settings.chatSpeechProvider
    val service = remember(provider, logins.elevenLabsKey, logins.ttsMonsterToken) { voiceService(engine.http, provider, logins) }
    val voices by produceState<Result<List<com.opirl.app.engine.chat.tts.Voice>>?>(null, service) {
        value = service?.let { withContext(Dispatchers.IO) { runCatching { it.voices() } } }
            ?: Result.success(emptyList())
    }
    val result = voices
    when {
        result == null -> Note(stringResource(R.string.loading))
        result.isFailure -> Note(localized(result.exceptionOrNull()?.message.orEmpty()))
        result.getOrThrow().isEmpty() -> Note(stringResource(R.string.no_voices))
        else -> Group {
            result.getOrThrow().forEach { voice ->
                row { shapes ->
                    RadioRow(shapes, voice.name, settings.chatSpeechVoices[provider]?.id == voice.id) {
                        store.update { it.copy(chatSpeechVoices = it.chatSpeechVoices + (provider to SpeechVoice(voice.id, voice.name))) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PredefinedMessagesPage(store: SettingsStore, settings: AppSettings) {
    var draft by remember { mutableStateOf("") }
    Group {
        row { shapes -> TextRow(shapes, stringResource(R.string.message), draft) { draft = it } }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.add_message), Icons.Rounded.Add, enabled = draft.isNotBlank()) {
                val message = PredefinedMessage(text = draft.trim())
                store.update { it.copy(predefinedMessages = it.predefinedMessages + message) }
                draft = ""
            }
        }
    }
    if (settings.predefinedMessages.isNotEmpty()) {
        Group {
            settings.predefinedMessages.forEach { message ->
                row { shapes ->
                    RemovableRow(shapes, message.text, "", null) {
                        store.update { s -> s.copy(predefinedMessages = s.predefinedMessages.filterNot { it.id == message.id }) }
                    }
                }
            }
        }
    }
}

@get:StringRes
private val BotCommand.label: Int
    get() = when (this) {
        BotCommand.TTS -> R.string.chat_speech
        BotCommand.SNAPSHOT -> R.string.quick_snapshot
        BotCommand.AUDIO -> R.string.microphone
        BotCommand.ZOOM -> R.string.zoom
        BotCommand.TORCH -> R.string.quick_torch
        BotCommand.SCENE -> R.string.scenes
        BotCommand.STREAM -> R.string.obs_stream
        BotCommand.WIDGET -> R.string.quick_widgets
        BotCommand.SEND -> R.string.chat_send_message
    }

private val BotCommand.usage: String
    get() = when (this) {
        BotCommand.TTS -> "tts on|off, say <text>"
        BotCommand.SNAPSHOT -> "snapshot"
        BotCommand.AUDIO -> "mute, unmute"
        BotCommand.ZOOM -> "zoom <x>"
        BotCommand.TORCH -> "torch on|off"
        BotCommand.SCENE -> "scene <name>"
        BotCommand.STREAM -> "stream start|stop|title|category"
        BotCommand.WIDGET -> "widget <name> enable|disable"
        BotCommand.SEND -> "send <text>"
    }

@Composable
internal fun botCommandTitle(command: BotCommand): String = stringResource(command.label)

@Composable
internal fun ChatBotPage(store: SettingsStore, settings: AppSettings, push: (Route) -> Unit) {
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.chat_bot), settings.chatBot) { on -> store.update { it.copy(chatBot = on) } }
        }
        row { ValueRow(it, stringResource(R.string.bot_commands), "$BOT_PREFIX help") }
    }
    Group {
        BotCommand.entries.forEach { command ->
            row { shapes ->
                NavRow(shapes, stringResource(command.label), supporting = "$BOT_PREFIX ${command.usage}") { push(Route.BotCommandPage(command)) }
            }
        }
    }
}

@Composable
internal fun BotCommandPage(store: SettingsStore, settings: AppSettings, command: BotCommand) {
    val permission = settings.botPermission(command)
    val update = { next: com.opirl.app.settings.BotPermissionSettings ->
        store.update { it.copy(chatBotPermissions = it.chatBotPermissions + (command to next)) }
    }
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.bot_moderators), permission.moderators) { update(permission.copy(moderators = it)) }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.bot_subscribers), permission.subscribers) { update(permission.copy(subscribers = it)) }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.bot_others), permission.others) { update(permission.copy(others = it)) }
        }
    }
    Group {
        row { shapes ->
            SliderRow(
                shapes,
                stringResource(R.string.bot_cooldown),
                permission.cooldownSeconds,
                0..300,
                if (permission.cooldownSeconds == 0) stringResource(R.string.off) else "${permission.cooldownSeconds} s",
                step = 5,
            ) { update(permission.copy(cooldownSeconds = it)) }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.bot_replies), permission.replies) { update(permission.copy(replies = it)) }
        }
    }
}
