package app.mobdroid.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mobdroid.chat.TWITCH_REDIRECT_HOST
import app.mobdroid.chat.kickSessionToken
import app.mobdroid.chat.kickUsername
import app.mobdroid.chat.twitchAccessToken
import app.mobdroid.chat.twitchAuthUrl
import app.mobdroid.chat.validateTwitchToken
import app.mobdroid.settings.KickAccount
import app.mobdroid.settings.TwitchAccount
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginDialog(title: String, url: String, onClose: () -> Unit, onRedirect: (Uri) -> Boolean) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClose) { Icon(Icons.Rounded.Close, "Close") }
                }
                AndroidView(
                    factory = { context ->
                        CookieManager.getInstance().removeAllCookies(null)
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                    onRedirect(request.url)
                            }
                            loadUrl(url)
                        }
                    },
                    onRelease = {
                        it.destroy()
                        CookieManager.getInstance().removeAllCookies(null)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun TwitchLoginDialog(http: OkHttpClient, onAccount: (TwitchAccount) -> Unit, onClose: () -> Unit) {
    val state = remember { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LoginDialog(error ?: "Log in to Twitch", twitchAuthUrl(state), onClose) { url ->
        if (url.host != TWITCH_REDIRECT_HOST) return@LoginDialog false
        val token = twitchAccessToken(url.encodedFragment, state)
        if (token == null) {
            error = "Twitch did not grant access"
        } else {
            scope.launch {
                val account = withContext(Dispatchers.IO) { validateTwitchToken(http, token) }
                if (account == null) error = "Could not check the Twitch login" else onAccount(account)
            }
        }
        true
    }
}

@Composable
fun KickLoginDialog(http: OkHttpClient, onAccount: (KickAccount) -> Unit, onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val token = kickSessionToken(CookieManager.getInstance().getCookie("https://kick.com")) ?: continue
            val username = withContext(Dispatchers.IO) { kickUsername(http, token) } ?: continue
            onAccount(KickAccount(username, token))
            return@LaunchedEffect
        }
    }
    LoginDialog("Log in to Kick", "https://kick.com/login", onClose) { false }
}
