package com.opirl.app.widgets

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.util.Log
import com.opirl.app.chat.ChatLine
import com.opirl.app.media.gl.OverlaySource
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.Widget
import com.opirl.app.stream.LiveStats
import com.opirl.app.stream.UiState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

sealed interface Need {
    data object Location : Need

    data class FaceOf(val camera: String?) : Need
}

class Face(
    val mouthOpen: Float,
    val leftEyeOpen: Float,
    val rightEyeOpen: Float,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

class Layer(
    val bitmap: Bitmap? = null,
    val source: OverlaySource? = null,
    val surface: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropWidth: Float = 1f,
    val cropHeight: Float = 1f,
    val cornerRadius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Int = 0,
)

class WidgetFrame(val layers: List<Layer>, val width: Float, val height: Float)

interface WidgetDrawer {
    val sized: Boolean get() = true

    fun placement(widget: Widget): SceneWidget = SceneWidget(widget.id)

    fun needs(widget: Widget, settings: AppSettings): Set<Need> = emptySet()

    fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame?
}

class WidgetRuntime(
    val context: Context,
    val scope: CoroutineScope,
    val store: SettingsStore,
    val http: OkHttpClient,
    val chat: StateFlow<List<ChatLine>>,
    val track: StateFlow<Track?>,
    val location: StateFlow<Location?>,
    val faces: StateFlow<Map<String?, Face>>,
    val stats: StateFlow<LiveStats>,
    val ui: StateFlow<UiState>,
) {
    val files = File(context.filesDir, "widgets")
    private val states = ConcurrentHashMap<String, Any>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(active: Boolean) {
        _active.value = active
    }

    fun invalidate() = _version.update { it + 1 }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> state(key: String, create: () -> T): T = states.getOrPut(key, create) as T

    fun widget(id: String): Widget? = store.settings.value.widgets.firstOrNull { it.id == id }

    fun update(id: String, transform: (Widget) -> Widget) = store.update { settings ->
        settings.copy(widgets = settings.widgets.map { if (it.id == id) transform(it) else it })
    }
}

internal class BitmapCache {
    private class Entry(val key: Any?, val bitmap: Bitmap)

    private val entries = HashMap<String, Entry>()
    private val used = HashSet<String>()

    fun get(slot: String, key: Any?, create: () -> Bitmap?): Bitmap? {
        used += slot
        entries[slot]?.takeIf { it.key == key }?.let { return it.bitmap }
        val bitmap = create()
        if (bitmap == null) entries.remove(slot) else entries[slot] = Entry(key, bitmap)
        return bitmap
    }

    fun sweep() {
        entries.keys.retainAll(used)
        used.clear()
    }
}

class WidgetContext internal constructor(
    val runtime: WidgetRuntime,
    val settings: AppSettings,
    val width: Int,
    val height: Int,
    val now: Long,
    private val cache: BitmapCache,
) {
    val textScale: Float get() = minOf(width, height) / 400f

    var deadline = Long.MAX_VALUE
        private set

    fun redrawAt(time: Long) {
        if (time < deadline) deadline = time
    }

    fun redrawIn(milliseconds: Long) = redrawAt(now + milliseconds)

    fun cached(slot: String, key: Any?, create: () -> Bitmap?): Bitmap? = cache.get(slot, key, create)

    fun natural(bitmap: Bitmap): WidgetFrame =
        WidgetFrame(listOf(Layer(bitmap)), bitmap.width.toFloat() / width, bitmap.height.toFloat() / height)

    fun sized(bitmap: Bitmap, placement: SceneWidget): WidgetFrame {
        val share = placement.size / 100f
        return WidgetFrame(listOf(Layer(bitmap)), share, share * width / height * bitmap.height / bitmap.width)
    }

    fun draw(widget: Widget, placement: SceneWidget): WidgetFrame? = try {
        widget.type.drawer.draw(this, widget, placement)
    } catch (e: Exception) {
        Log.w("Widgets", "${widget.type} widget failed", e)
        null
    }
}
