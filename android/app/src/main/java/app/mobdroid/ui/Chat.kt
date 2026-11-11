package app.mobdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
import app.mobdroid.chat.ChatLine
import app.mobdroid.engine.chat.ChatMessage
import app.mobdroid.engine.chat.ChatPlatform
import app.mobdroid.engine.chat.ChatSegment
import app.mobdroid.settings.AppSettings
import app.mobdroid.stream.StreamEngine
import coil3.compose.AsyncImage
import kotlin.math.absoluteValue

private val NameColors = listOf(
    Color(0xFFFF8A65),
    Color(0xFFB39DFF),
    Color(0xFF7FD4FF),
    Color(0xFFC7F08A),
    Color(0xFFFFD166),
    Color(0xFFFF9ECF),
    Color(0xFF8EF0D0),
)

private fun nameColor(message: ChatMessage): Color {
    val color = message.userColor?.let { Color(0xFF000000 or it.toLong()) }
        ?: return NameColors[message.user.hashCode().absoluteValue % NameColors.size]
    return if (color.luminance() < 0.3f) lerp(color, Color.White, 0.5f) else color
}

private val ChatPlatform.color: Color
    get() = when (this) {
        ChatPlatform.TWITCH -> Color(0xFFA970FF)
        ChatPlatform.YOUTUBE -> Color(0xFFFF4E45)
        ChatPlatform.KICK -> Color(0xFF53FC18)
    }

private val ChatPlatform.label: String
    get() = when (this) {
        ChatPlatform.TWITCH -> "Twitch"
        ChatPlatform.YOUTUBE -> "YouTube"
        ChatPlatform.KICK -> "Kick"
    }

@Composable
fun ChatMessageText(line: ChatLine, sizeSp: Int, shadow: Boolean, platformDot: Boolean, modifier: Modifier = Modifier) {
    val color = nameColor(line.message)
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
fun ChatPanel(engine: StreamEngine, settings: AppSettings, onClose: () -> Unit, header: (@Composable () -> Unit)? = null) {
    val lines by engine.chat.lines.collectAsStateWithLifecycle()
    val connected by engine.chat.connected.collectAsStateWithLifecycle()
    val canSend by engine.chat.canSend.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val newest = lines.lastOrNull()?.key
    LaunchedEffect(newest) {
        if (!list.isScrollInProgress && list.firstVisibleItemIndex <= 2) list.scrollToItem(0)
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.imePadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (header != null) {
                        header()
                    } else {
                        Text("Chat", style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                }
                IconButton(onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
            val waiting = connected.filterValues { !it }.keys
            val note = when {
                connected.isEmpty() -> "Add a chat channel to the stream in Settings"
                waiting.isNotEmpty() -> "Connecting to ${waiting.joinToString(", ") { it.label }}"
                lines.isEmpty() -> "No messages yet"
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
                items(lines.asReversed(), key = { it.key }) {
                    ChatMessageText(it, settings.chatTextSize.sp, shadow = false, platformDot = connected.size > 1)
                }
            }
            if (canSend.isNotEmpty()) ChatInput(canSend) { platform, text, onResult -> engine.chat.send(platform, text, onResult) }
        }
    }
}

@Composable
private fun ChatInput(platforms: List<ChatPlatform>, onSend: (ChatPlatform, String, (String?) -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var platform by remember(platforms) { mutableStateOf(platforms.first()) }
    val send = {
        val message = text.trim()
        if (message.isNotEmpty()) {
            text = ""
            onSend(platform, message) { error = it }
        }
    }
    Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp))
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
                                if (platforms.size > 1) "Message ${platform.label}" else "Send a message",
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
            IconButton(send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", Modifier.size(22.dp)) }
        }
    }
}
