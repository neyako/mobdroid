package com.opirl.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.opirl.app.R

class Access(
    val camera: Boolean,
    val microphone: Boolean,
    val notifications: Boolean,
    val blocked: Boolean,
    val request: () -> Unit,
) {
    val ready: Boolean get() = camera && microphone
}

private val REQUIRED = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

@Composable
fun rememberAccess(onReady: () -> Unit): Access {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var checked by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf(false) }
    val granted = { permission: String -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checked++
        blocked = REQUIRED.any { !granted(it) && activity?.shouldShowRequestPermissionRationale(it) == false }
        if (REQUIRED.all(granted)) onReady()
    }
    LifecycleResumeEffect(Unit) {
        checked++
        if (REQUIRED.all(granted)) {
            blocked = false
            onReady()
        }
        onPauseOrDispose {}
    }
    return remember(checked, blocked) {
        Access(
            camera = granted(Manifest.permission.CAMERA),
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            blocked = blocked,
            request = {
                if (blocked) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    val wanted = if (Build.VERSION.SDK_INT >= 33) REQUIRED + Manifest.permission.POST_NOTIFICATIONS else REQUIRED
                    launcher.launch(wanted.filterNot(granted).toTypedArray())
                }
            },
        )
    }
}

@Composable
fun AccessPrompt(access: Access, corner: Dp) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(corner)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.permission_prompt_title), style = RowTitle, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.permission_prompt_reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(access.request) { Text(stringResource(if (access.blocked) R.string.open_settings else R.string.allow)) }
        }
    }
}
