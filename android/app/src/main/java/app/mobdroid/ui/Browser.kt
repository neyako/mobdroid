package app.mobdroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.mobdroid.settings.AppSettings
import app.mobdroid.settings.Bookmark
import app.mobdroid.settings.SettingsStore
import java.net.URLEncoder

private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?!\\d+([/?#]|$))")
private val LOCAL_HOST = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$")

fun browserAddress(text: String): String {
    val trimmed = text.trim()
    val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
    val web = (scheme == "http" || scheme == "https") && trimmed.startsWith("//", scheme.length + 1)
    return when {
        trimmed.isEmpty() || trimmed == "about:blank" -> "about:blank"
        web -> trimmed
        scheme == null && LOCAL_HOST.matches(trimmed) -> "http://$trimmed"
        scheme == null && ' ' !in trimmed && '.' in trimmed -> "https://$trimmed"
        else -> "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
    }
}

fun shortAddress(url: String): String = url.removePrefix("https://")

fun editedAddress(text: String, url: String): String = if (text == shortAddress(url)) url else text

class BrowserState {
    var webView by mutableStateOf<WebView?>(null)
        private set
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(100)
        private set

    fun load(context: Context, text: String) {
        (webView ?: create(context)).loadUrl(browserAddress(text))
    }

    fun close() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        url = ""
        title = ""
        canGoBack = false
        canGoForward = false
        progress = 100
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.url.scheme !in setOf("http", "https", "about")

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = sync(view)

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = sync(view)

            override fun onPageFinished(view: WebView, url: String) = sync(view)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Handler(Looper.getMainLooper()).post { if (view === webView) close() }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === webView) this@BrowserState.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) = sync(view)
        }
    }.also { webView = it }

    private fun sync(view: WebView) {
        if (view !== webView) return
        url = view.url.orEmpty().takeIf { it != "about:blank" }.orEmpty()
        title = view.title.orEmpty()
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }
}

@Composable
fun BrowserPanel(store: SettingsStore, settings: AppSettings, browser: BrowserState, onHide: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var showBookmarks by remember { mutableStateOf(false) }
    val view = browser.webView
    val open = { address: String ->
        showBookmarks = false
        browser.load(context, address)
    }
    BackHandler(showBookmarks || browser.canGoBack) {
        if (showBookmarks) showBookmarks = false else browser.webView?.goBack()
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.imePadding()) {
            if (showBookmarks) {
                Row(Modifier.fillMaxWidth().height(PanelHeaderHeight).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(70.dp)) {
                        IconButton({ showBookmarks = false }, Modifier.size(44.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Navigate back", Modifier.size(20.dp))
                        }
                    }
                    Text("Bookmarks", style = PanelTitle, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                    PanelButtons(hidden = false, onHide, onClose)
                }
                Bookmarks(store, settings, browser, add = true, open)
            } else {
                Row(Modifier.fillMaxWidth().height(PanelHeaderHeight).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, "Previous page", browser.canGoBack) { view?.goBack() }
                    ToolButton(Icons.AutoMirrored.Rounded.ArrowForward, "Next page", browser.canGoForward) { view?.goForward() }
                    AddressField(browser.url, Modifier.weight(1f), open)
                    if (browser.progress < 100) {
                        ToolButton(Icons.Rounded.Close, "Stop loading") { view?.stopLoading() }
                    } else {
                        ToolButton(Icons.Rounded.Refresh, "Reload", view != null) { view?.reload() }
                    }
                    ToolButton(Icons.Rounded.Bookmarks, "Bookmarks") { showBookmarks = true }
                    Spacer(Modifier.width(4.dp))
                    PanelButtons(hidden = false, onHide, onClose)
                }
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (browser.progress < 100) {
                        LinearProgressIndicator({ browser.progress / 100f }, Modifier.fillMaxSize())
                    }
                }
                if (view == null) {
                    Bookmarks(store, settings, browser, add = false, open)
                } else {
                    key(view) {
                        AndroidView(
                            factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(onClick, Modifier.size(36.dp, 44.dp), enabled) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}

@Composable
private fun AddressField(url: String, modifier: Modifier, onGo: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(shortAddress(url))) }
    LaunchedEffect(url, focused) { if (!focused) value = TextFieldValue(shortAddress(url)) }
    LaunchedEffect(focused) { if (focused) value = value.copy(selection = TextRange(0, value.text.length)) }
    BasicTextField(
        value,
        { value = it },
        modifier.onFocusChanged { focused = it.isFocused },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(
            onGo = {
                if (value.text.isNotBlank()) onGo(editedAddress(value.text, url))
                focus.clearFocus()
            },
        ),
    ) { field ->
        Box(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.text.isEmpty()) {
                Text(
                    "Search or address",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            field()
        }
    }
}

@Composable
private fun Bookmarks(store: SettingsStore, settings: AppSettings, browser: BrowserState, add: Boolean, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (add) {
            val url = browser.url
            Group {
                row { shapes ->
                    ActionRow(shapes, "Add this page", Icons.Rounded.Add, enabled = url.isNotEmpty() && settings.bookmarks.none { it.url == url }) {
                        val name = browser.title.ifBlank { Uri.parse(url).host ?: url }
                        store.update { it.copy(bookmarks = it.bookmarks + Bookmark(name = name, url = url)) }
                    }
                }
            }
        }
        if (settings.bookmarks.isEmpty()) {
            Text(
                "No bookmarks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            Group {
                settings.bookmarks.forEach { bookmark ->
                    row { shapes ->
                        BookmarkRow(shapes, bookmark, { onOpen(bookmark.url) }) {
                            store.update { s -> s.copy(bookmarks = s.bookmarks.filterNot { it.id == bookmark.id }) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BookmarkRow(shapes: ListItemShapes, bookmark: Bookmark, onClick: () -> Unit, onRemove: () -> Unit) {
    SegmentedListItem(
        contentPadding = PaddingValues(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        colors = rowColors(),
        onClick = onClick,
        shapes = shapes,
        modifier = Modifier.heightIn(max = RowHeight),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Uri.parse(bookmark.url).host?.removePrefix("www.").orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                ToolButton(Icons.Rounded.Close, "Remove ${bookmark.name}", onClick = onRemove)
            }
        },
    ) { Text(bookmark.name, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
