package com.opirl.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opirl.app.R

@StringRes
fun messageRes(text: String): Int? = when (text) {
    "Cannot reach OBS" -> R.string.message_obs_unreachable
    "Wrong password" -> R.string.message_wrong_password
    "OBS did not answer" -> R.string.message_obs_no_answer
    "Server refused the connection" -> R.string.message_refused
    "Server not found" -> R.string.message_server_not_found
    "Connection timed out" -> R.string.message_timed_out
    "No network" -> R.string.message_no_network
    "Connection dropped" -> R.string.message_dropped
    "Twitch login expired" -> R.string.message_twitch_expired
    "Not logged in" -> R.string.not_logged_in
    "Camera in use by another app" -> R.string.message_camera_in_use
    "Too many cameras in use" -> R.string.message_too_many_cameras
    "Camera disabled by policy" -> R.string.message_camera_disabled
    "Camera disconnected" -> R.string.message_camera_disconnected
    "Camera permission denied" -> R.string.message_camera_denied
    "Camera unavailable" -> R.string.message_camera_unavailable
    "Camera stopped delivering frames" -> R.string.message_camera_no_frames
    "Camera did not start" -> R.string.message_camera_not_started
    "The camera was unplugged" -> R.string.message_camera_unplugged
    "The camera stopped sending frames" -> R.string.message_camera_stalled
    else -> null
}

@Composable
fun localized(text: String): String = messageRes(text)?.let { stringResource(it) } ?: text
