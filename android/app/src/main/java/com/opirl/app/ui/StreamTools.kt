package com.opirl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opirl.app.R
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.Poll
import com.opirl.app.engine.chat.StreamApi
import com.opirl.app.engine.chat.StreamCategory
import com.opirl.app.engine.chat.StreamFeature
import com.opirl.app.engine.chat.StreamInfo
import com.opirl.app.engine.chat.streamFeatures
import com.opirl.app.stream.AlertLevel
import com.opirl.app.stream.StreamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val TIMEOUT_SECONDS = 600

class PlatformRunner(private val scope: CoroutineScope, private val engine: StreamEngine) {
    var busy by mutableStateOf(false)
        private set

    fun run(platform: ChatPlatform, done: String? = null, onSuccess: () -> Unit = {}, block: (StreamApi) -> Unit) {
        scope.launch {
            busy = true
            val result = engine.platforms.call(platform, block)
            busy = false
            result.fold(
                {
                    if (done != null) engine.showAlert(AlertLevel.INFO, platform.label, done)
                    onSuccess()
                },
                { engine.showAlert(AlertLevel.ERROR, platform.label, it.message) },
            )
        }
    }
}

@Composable
fun rememberPlatformRunner(engine: StreamEngine): PlatformRunner {
    val scope = rememberCoroutineScope()
    return remember(engine) { PlatformRunner(scope, engine) }
}

@Composable
fun StreamTools(engine: StreamEngine, platforms: List<ChatPlatform>, modifier: Modifier = Modifier) {
    var platform by remember(platforms) { mutableStateOf(platforms.first()) }
    val runner = rememberPlatformRunner(engine)
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (platforms.size > 1) {
            Group {
                row { shapes -> ChoiceRow(shapes, stringResource(R.string.platform), platforms, platform, { it.label }) { platform = it } }
            }
        }
        key(platform) {
            val features = streamFeatures(platform)
            StreamInfoRows(engine, runner, platform, StreamFeature.CATEGORY in features)
            if (StreamFeature.MARKER in features) {
                Group {
                    row { shapes ->
                        val done = stringResource(R.string.marker_added)
                        ActionRow(shapes, stringResource(R.string.add_marker), Icons.Rounded.BookmarkAdd, enabled = !runner.busy) {
                            runner.run(platform, done) { it.createMarker() }
                        }
                    }
                }
            }
            if (StreamFeature.POLL in features) PollRows(engine, runner, platform, StreamFeature.POLL_RESULTS in features)
            if (StreamFeature.CHAT_MODES in features) ChatModeRows(runner, platform)
            if (StreamFeature.UNBAN in features) UserRows(runner, platform, StreamFeature.ROLES in features)
        }
    }
}

@Composable
private fun StreamInfoRows(engine: StreamEngine, runner: PlatformRunner, platform: ChatPlatform, categories: Boolean) {
    var info by remember { mutableStateOf<StreamInfo?>(null) }
    var title by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<StreamCategory>()) }
    val saved = stringResource(R.string.saved)
    LaunchedEffect(Unit) {
        engine.platforms.call(platform) { it.streamInfo() }.fold(
            {
                info = it
                title = it.title
            },
            { engine.showAlert(AlertLevel.ERROR, platform.label, it.message) },
        )
    }
    Group {
        row { shapes -> TextRow(shapes, stringResource(R.string.stream_title), title) { title = it } }
        row { shapes ->
            val changed = title.isNotBlank() && title.trim() != info?.title
            ActionRow(shapes, stringResource(R.string.save_title), Icons.Rounded.Check, enabled = changed && !runner.busy) {
                val next = title.trim()
                runner.run(platform, saved, { info = StreamInfo(next, info?.category.orEmpty()) }) { it.setTitle(next) }
            }
        }
    }
    if (!categories) return
    LaunchedEffect(query) {
        found = emptyList()
        if (query.trim().length < 2) return@LaunchedEffect
        delay(400)
        found = engine.platforms.call(platform) { it.searchCategories(query.trim()) }.getOrDefault(emptyList())
    }
    Group {
        row { ValueRow(it, stringResource(R.string.category), info?.category.orEmpty()) }
        row { shapes -> TextRow(shapes, stringResource(R.string.search_category), query) { query = it } }
        found.forEach { category ->
            row { shapes ->
                ActionRow(shapes, category.name, Icons.Rounded.Search, enabled = !runner.busy) {
                    runner.run(
                        platform,
                        saved,
                        {
                            info = StreamInfo(info?.title.orEmpty(), category.name)
                            query = ""
                        },
                    ) { it.setCategory(category) }
                }
            }
        }
    }
}

private val POLL_DURATIONS = listOf(30, 120, 300)

private fun duration(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

@Composable
private fun PollRows(engine: StreamEngine, runner: PlatformRunner, platform: ChatPlatform, results: Boolean) {
    var question by remember { mutableStateOf("") }
    val choices = remember { mutableStateListOf("", "", "", "") }
    var seconds by remember { mutableIntStateOf(120) }
    var active by remember { mutableStateOf<Poll?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val started = stringResource(R.string.poll_started)
    val ended = stringResource(R.string.poll_ended)
    if (results) {
        LaunchedEffect(reload) { active = engine.platforms.call(platform) { it.poll() }.getOrNull() }
    }
    val poll = active
    Group {
        if (poll != null) {
            row { ValueRow(it, poll.title, stringResource(R.string.poll)) }
            poll.choices.forEach { choice -> row { ValueRow(it, choice.title, choice.votes.toString()) } }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.reload), Icons.Rounded.Refresh, enabled = !runner.busy) { reload++ }
            }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.end_poll), Icons.Rounded.Stop, MaterialTheme.colorScheme.error, !runner.busy) {
                    runner.run(platform, ended, { active = null }) { it.endPoll(poll) }
                }
            }
        } else {
            row { shapes -> TextRow(shapes, stringResource(R.string.poll_question), question) { question = it } }
            choices.forEachIndexed { index, choice ->
                row { shapes -> TextRow(shapes, stringResource(R.string.poll_choice, index + 1), choice) { choices[index] = it } }
            }
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.duration), POLL_DURATIONS, seconds, { duration(it) }) { seconds = it }
            }
            row { shapes ->
                val options = choices.map { it.trim() }.filter { it.isNotEmpty() }
                val ready = question.isNotBlank() && options.size >= 2 && !runner.busy
                ActionRow(shapes, stringResource(R.string.start_poll), Icons.Rounded.Poll, enabled = ready) {
                    runner.run(platform, started, { reload++ }) { it.createPoll(question.trim(), options, seconds) }
                }
            }
            if (!results) {
                row { shapes ->
                    ActionRow(shapes, stringResource(R.string.end_poll), Icons.Rounded.Stop, MaterialTheme.colorScheme.error, !runner.busy) {
                        runner.run(platform, ended) { it.endPoll(null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatModeRows(runner: PlatformRunner, platform: ChatPlatform) {
    var slow by remember { mutableStateOf<Int?>(null) }
    var followers by remember { mutableStateOf<Boolean?>(null) }
    var emotes by remember { mutableStateOf<Boolean?>(null) }
    var subscribers by remember { mutableStateOf<Boolean?>(null) }
    val onOff = listOf<Boolean?>(true, false)
    val label: @Composable (Boolean?) -> String = { stringResource(if (it == true) R.string.on else R.string.off) }
    Group {
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.slow_mode),
                listOf<Int?>(0, 10, 30, 120),
                slow,
                { if (it == 0) stringResource(R.string.off) else duration(it ?: 0) },
                enabled = !runner.busy,
            ) { value -> runner.run(platform, onSuccess = { slow = value }) { it.setSlowMode(value?.takeIf { seconds -> seconds > 0 }) } }
        }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.followers_only), onOff, followers, label, enabled = !runner.busy) { value ->
                runner.run(platform, onSuccess = { followers = value }) { it.setFollowersOnly(value == true) }
            }
        }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.emote_only), onOff, emotes, label, enabled = !runner.busy) { value ->
                runner.run(platform, onSuccess = { emotes = value }) { it.setEmoteOnly(value == true) }
            }
        }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.subscribers_only), onOff, subscribers, label, enabled = !runner.busy) { value ->
                runner.run(platform, onSuccess = { subscribers = value }) { it.setSubscribersOnly(value == true) }
            }
        }
    }
}

@Composable
private fun UserRows(runner: PlatformRunner, platform: ChatPlatform, roles: Boolean) {
    var user by remember { mutableStateOf("") }
    val name = user.trim().removePrefix("@")
    val ready = name.isNotEmpty() && !runner.busy
    val done = stringResource(R.string.done)
    Group {
        row { shapes -> TextRow(shapes, stringResource(R.string.username), user) { user = it } }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.timeout_10), Icons.Rounded.Timer, enabled = ready) {
                runner.run(platform, done) { it.ban(null, name, TIMEOUT_SECONDS) }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.ban), Icons.Rounded.Block, MaterialTheme.colorScheme.error, ready) {
                runner.run(platform, done) { it.ban(null, name, null) }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.unban), Icons.Rounded.Check, enabled = ready) { runner.run(platform, done) { it.unban(name) } }
        }
        if (roles) {
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.mod), Icons.Rounded.Shield, enabled = ready) {
                    runner.run(platform, done) { it.setModerator(name, true) }
                }
            }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.unmod), Icons.Rounded.Delete, enabled = ready) {
                    runner.run(platform, done) { it.setModerator(name, false) }
                }
            }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.vip), Icons.Rounded.Star, enabled = ready) { runner.run(platform, done) { it.setVip(name, true) } }
            }
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.unvip), Icons.Rounded.Delete, enabled = ready) {
                    runner.run(platform, done) { it.setVip(name, false) }
                }
            }
        }
    }
}
