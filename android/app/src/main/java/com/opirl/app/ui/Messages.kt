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
    "User not found" -> R.string.message_user_not_found
    "Category not found" -> R.string.message_category_not_found
    "Not supported" -> R.string.message_not_supported
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
    "Not a moblin:// link" -> R.string.message_not_moblin_link
    "The link cannot be read" -> R.string.message_link_unreadable
    "Unsupported stream URL" -> R.string.message_unsupported_stream_url
    "Unsupported video resolution" -> R.string.message_unsupported_resolution
    "Unsupported video FPS" -> R.string.message_unsupported_fps
    "Video bitrate out of range" -> R.string.message_bitrate_range
    "Unsupported video codec" -> R.string.message_unsupported_codec
    "Key frame interval out of range" -> R.string.message_key_frame_range
    "Unsupported audio bitrate" -> R.string.message_unsupported_audio_bitrate
    "SRT latency out of range" -> R.string.message_latency_range
    "Malformed WebSocket URL" -> R.string.message_websocket_url
    "Invalid remote control settings" -> R.string.message_remote_settings
    "Not connected to streamer" -> R.string.message_streamer_not_connected
    "The streamer refused the request" -> R.string.message_streamer_refused
    else -> null
}

@Composable
fun localized(text: String): String {
    messageRes(text)?.let { return stringResource(it) }
    val patterns = listOf(
        Triple("Cannot reach ", "", R.string.message_cannot_reach),
        Triple("", " login expired", R.string.message_login_expired),
        Triple("", " API key was not accepted", R.string.message_key_rejected),
    )
    for ((prefix, suffix, res) in patterns) {
        if (text.length > prefix.length + suffix.length && text.startsWith(prefix) && text.endsWith(suffix)) {
            return stringResource(res, text.removePrefix(prefix).removeSuffix(suffix))
        }
    }
    return text
}
