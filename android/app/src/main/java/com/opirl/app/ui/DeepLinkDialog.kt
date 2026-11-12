package com.opirl.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.DeepLink
import com.opirl.app.settings.DeepLinkException
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.parseDeepLink
import com.opirl.app.settings.withDeepLink
import com.opirl.app.stream.AlertLevel
import com.opirl.app.stream.StreamEngine

@Composable
fun DeepLinkDialog(link: String, store: SettingsStore, current: AppSettings, engine: StreamEngine, busy: Boolean) {
    val close = { engine.pendingLink.value = null }
    val parsed = remember(link) {
        try {
            Result.success(parseDeepLink(link))
        } catch (e: DeepLinkException) {
            Result.failure<DeepLink>(e)
        }
    }
    val settings = parsed.getOrNull()
    val error = when {
        busy -> stringResource(R.string.link_import_busy)
        settings == null -> localized(parsed.exceptionOrNull()?.message.orEmpty())
        else -> null
    }
    if (settings == null || error != null) {
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.link_import_failed)) },
            text = { Text(error.orEmpty()) },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.ok)) } },
        )
        return
    }
    val collisions = settings.collisions(current)
    val imported = stringResource(R.string.settings_imported)
    val apply = { replace: Boolean ->
        store.update { it.withDeepLink(settings, replace) }
        close()
        engine.showAlert(AlertLevel.INFO, imported)
    }
    val parts = buildList {
        if (settings.streams.isNotEmpty()) add(settings.streams.joinToString(", ") { it.name })
        if (settings.quickButtons != null) add(stringResource(R.string.quick_buttons))
        if (settings.remoteControl != null) add(stringResource(R.string.remote_control))
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.link_import_title)) },
        text = {
            Text(
                if (collisions.isEmpty()) {
                    parts.joinToString("\n")
                } else {
                    parts.joinToString("\n") + "\n\n" + stringResource(R.string.link_import_collision, collisions.joinToString(", "))
                },
            )
        },
        confirmButton = {
            if (collisions.isEmpty()) {
                TextButton({ apply(false) }) { Text(stringResource(R.string.import_action)) }
            } else {
                Row {
                    TextButton({ apply(false) }) { Text(stringResource(R.string.keep_both)) }
                    TextButton({ apply(true) }) { Text(stringResource(R.string.replace)) }
                }
            }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.cancel)) } },
    )
}
