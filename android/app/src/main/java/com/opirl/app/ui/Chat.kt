package com.opirl.app.ui

import com.opirl.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import com.opirl.app.chat.managedPlatforms
import com.opirl.app.engine.chat.StreamFeature
import com.opirl.app.engine.chat.streamFeatures
import com.opirl.app.settings.PredefinedMessage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.chat.ChatLine
import com.opirl.app.chat.chatNameColor
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import com.opirl.app.settings.AppSettings
import com.opirl.app.stream.StreamEngine
import coil3.compose.AsyncImage

internal val ChatPlatform.color: Color
    get() = when (this) {
        ChatPlatform.TWITCH -> Color(0xFFA970FF)
        ChatPlatform.YOUTUBE -> Color(0xFFFF4E45)
        ChatPlatform.KICK -> Color(0xFF53FC18)
    }

internal val ChatPlatform.label: String
    get() = when (this) {
        ChatPlatform.TWITCH -> "Twitch"
        ChatPlatform.YOUTUBE -> "YouTube"
        ChatPlatform.KICK -> "Kick"
    }

@Composable
fun ChatMessageText(line: ChatLine, sizeSp: Int, shadow: Boolean, platformDot: Boolean, modifier: Modifier = Modifier) {
    val color = Color(chatNameColor(line.message))
    val inline = remember(line.key) { HashMap<String, InlineTextContent>() }
    val text = remember(line.key, platformDot) {
        buildAnnotatedString {
            if (platformDot) withStyle(SpanStyle(color = line.message.platform.color)) { append("\u25CF ") }
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(line.message.user) }
            append("  ")
            line.message.segments.forEachIndexed { index, segment ->
                when (segment) {
                    is ChatSegment.Text -> append(segment.text)
                    is ChatSegment.Emote -> {
                        appendInlineContent("e$index", segment.name)
                        inline["e$index"] = InlineTextContent(Placeholder(1.5.em, 1.3.em, PlaceholderVerticalAlign.Center)) {
                            AsyncImage(segment.url, segment.name, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
    Text(
        text,
        inlineContent = inline,
        style = TextStyle(
            color = Color.White,
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * 1.35f).sp,
            fontWeight = FontWeight.Medium,
            fontStyle = if (line.message.isAction) FontStyle.Italic else FontStyle.Normal,
            shadow = if (shadow) Shadow(Color.Black, blurRadius = 6f) else null,
        ),
        modifier = modifier,
    )
}

@Composable
fun ChatOverlay(engine: StreamEngine, sizeSp: Int, count: Int, modifier: Modifier) {
    val lines by engine.chat.lines.collectAsStateWithLifecycle()
    val connected by engine.chat.connected.collectAsStateWithLifecycle()
    Column(modifier) {
        lines.takeLast(count).forEach { ChatMessageText(it, sizeSp, shadow = true, platformDot = connected.size > 1) }
    }
}

@Composable
fun ChatPanel(
    engine: StreamEngine,
    settings: AppSettings,
    draft: MutableState<String>,
    tools: MutableState<Boolean>,
    onHide: () -> Unit,
    onClose: () -> Unit,
    onLogin: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val lines by engine.chat.lines.collectAsStateWithLifecycle()
    val connected by engine.chat.connected.collectAsStateWithLifecycle()
    val canSend by engine.chat.canSend.collectAsStateWithLifecycle()
    val logins by engine.logins.logins.collectAsStateWithLifecycle()
    val managed = remember(settings.selectedProfile, logins) { managedPlatforms(settings.selectedProfile, logins) }
    val showTools = tools.value && managed.isNotEmpty()
    var selected by remember { mutableStateOf<ChatLine?>(null) }
    val list = rememberLazyListState()
    val newest = lines.lastOrNull()?.key
    LaunchedEffect(newest) {
        if (!list.isScrollInProgress && list.firstVisibleItemIndex <= 2) list.scrollToItem(0)
    }
    PanelSurface {
        Column(Modifier.imePadding()) {
            PanelHeader(
                stringResource(if (showTools) R.string.stream_tools else R.string.chat),
                onHide,
                onClose,
                onBack = if (showTools) ({ tools.value = false }) else null,
                titleContent = header.takeIf { !showTools },
            ) {
                if (managed.isNotEmpty() && !showTools) {
                    IconButton({ tools.value = true }, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Tune, stringResource(R.string.stream_tools), Modifier.size(22.dp))
                    }
                }
            }
            if (showTools) {
                StreamTools(engine, managed, Modifier.weight(1f))
                return@Column
            }
            val waiting = connected.filterValues { !it }.keys
            val note = when {
                connected.isEmpty() -> stringResource(R.string.chat_no_channel)
                waiting.isNotEmpty() -> stringResource(R.string.connecting_to, waiting.joinToString(", ") { it.label })
                lines.isEmpty() -> stringResource(R.string.chat_empty)
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines.asReversed(), key = { it.key }) { line ->
                    val marked = selected?.key == line.key
                    ChatMessageText(
                        line,
                        settings.chatTextSize.sp,
                        shadow = false,
                        platformDot = connected.size > 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (marked) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable(enabled = line.message.platform in managed) { selected = if (marked) null else line },
                    )
                }
            }
            selected?.takeIf { it.message.platform in managed }?.let { line -> MessageActions(engine, line) { selected = null } }
            if (canSend.isNotEmpty()) {
                ChatInput(canSend, draft, settings.predefinedMessages) { platform, text, onResult -> engine.chat.send(platform, text, onResult) }
            } else {
                LoginField(onLogin)
            }
        }
    }
}

@Composable
private fun MessageActions(engine: StreamEngine, line: ChatLine, onDone: () -> Unit) {
    val message = line.message
    val features = streamFeatures(message.platform)
    val runner = rememberPlatformRunner(engine)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            message.user,
            style = MaterialTheme.typography.labelLarge,
            color = Color(chatNameColor(message)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val id = message.id
        if (id != null && StreamFeature.DELETE_MESSAGE in features) {
            TextButton({ runner.run(message.platform, onSuccess = onDone) { it.deleteMessage(id) } }, enabled = !runner.busy) {
                Text(stringResource(R.string.delete))
            }
        }
        if (message.userId != null || StreamFeature.UNBAN in features) {
            TextButton(
                { runner.run(message.platform, onSuccess = onDone) { it.ban(message.userId, message.user, TIMEOUT_SECONDS) } },
                enabled = !runner.busy,
            ) { Text(stringResource(R.string.timeout_10)) }
            TextButton(
                { runner.run(message.platform, onSuccess = onDone) { it.ban(message.userId, message.user, null) } },
                enabled = !runner.busy,
            ) { Text(stringResource(R.string.ban), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun LoginField(onLogin: () -> Unit) {
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onLogin)
            .padding(start = 14.dp, end = 10.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.chat_log_in_to_send),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.log_in), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ChatInput(
    platforms: List<ChatPlatform>,
    draft: MutableState<String>,
    predefined: List<PredefinedMessage>,
    onSend: (ChatPlatform, String, (String?) -> Unit) -> Unit,
) {
    var text by draft
    var error by remember { mutableStateOf<String?>(null) }
    var platform by remember(platforms) { mutableStateOf(platforms.first()) }
    var choosing by remember { mutableStateOf(false) }
    val send = {
        val message = text.trim()
        if (message.isNotEmpty()) {
            text = ""
            onSend(platform, message) { error = it }
        }
    }
    Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
        if (choosing && predefined.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(end = 8.dp, bottom = 6.dp)) {
                predefined.forEach { message ->
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                choosing = false
                                onSend(platform, message.text) { error = it }
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }
        }
        error?.let {
            Text(
                localized(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                text,
                {
                    text = it
                    error = null
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { field ->
                    Box(
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                if (platforms.size > 1) {
                                    stringResource(R.string.chat_message_platform, platform.label)
                                } else {
                                    stringResource(R.string.chat_send_message)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp,
                            )
                        }
                        field()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (platforms.size > 1) {
                Text(
                    platform.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { platform = platforms[(platforms.indexOf(platform) + 1) % platforms.size] }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            if (predefined.isNotEmpty()) {
                IconButton({ choosing = !choosing }) {
                    Icon(Icons.Rounded.Bolt, stringResource(R.string.predefined_messages), Modifier.size(22.dp))
                }
            }
            IconButton(send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.send), Modifier.size(22.dp)) }
        }
    }
}
