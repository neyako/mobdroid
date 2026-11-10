package app.mobdroid.widgets

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import app.mobdroid.media.gl.BrowserPage
import app.mobdroid.media.gl.VideoCompositor

class BrowserWidgets(private val context: Context) {
    private class Running(
        var page: BrowserPage,
        val compositor: VideoCompositor,
        val display: VirtualDisplay,
        val presentation: Presentation,
        val webView: WebView,
    ) {
        val load = Runnable { webView.loadUrl(browserUrl(page.url)) }
    }

    private val running = HashMap<String, Running>()
    private val handler = Handler(Looper.getMainLooper())

    fun sync(pages: Map<String, BrowserPage>, compositor: VideoCompositor?) {
        running.entries
            .filter { (id, widget) ->
                val page = pages[id]
                widget.compositor !== compositor || page == null || page.width != widget.page.width || page.height != widget.page.height
            }
            .map { it.key }
            .forEach(::close)
        if (compositor == null) return
        for ((id, page) in pages) {
            val widget = running[id]
            if (widget == null) {
                open(id, page, compositor)
            } else if (widget.page.url != page.url) {
                widget.page = page
                handler.removeCallbacks(widget.load)
                handler.postDelayed(widget.load, 800)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(id: String, page: BrowserPage, compositor: VideoCompositor) {
        val surface = compositor.openSurfaceOverlay(id, page.width, page.height) ?: return
        var display: VirtualDisplay? = null
        try {
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "mobdroid-widget",
                page.width,
                page.height,
                160,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            val presentation = Presentation(context, display.display, android.R.style.Theme_Translucent_NoTitleBar)
            val webView = WebView(presentation.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        Log.w(TAG, "Browser widget renderer gone, crashed: ${detail.didCrash()}")
                        handler.post { close(id) }
                        return true
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
            }
            presentation.setContentView(webView)
            presentation.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            presentation.show()
            val widget = Running(page, compositor, display, presentation, webView)
            running[id] = widget
            widget.load.run()
        } catch (e: Exception) {
            Log.e(TAG, "Browser widget failed", e)
            display?.release()
            compositor.closeSurfaceOverlay(id)
        }
    }

    private fun close(id: String) {
        val widget = running.remove(id) ?: return
        handler.removeCallbacks(widget.load)
        runCatching { widget.presentation.dismiss() }
        widget.webView.destroy()
        widget.display.release()
        widget.compositor.closeSurfaceOverlay(id)
    }

    private companion object {
        const val TAG = "BrowserWidgets"
    }
}

fun browserUrl(url: String): String {
    val trimmed = url.trim()
    return if (trimmed.isEmpty()) "about:blank" else if ("://" in trimmed) trimmed else "https://$trimmed"
}
