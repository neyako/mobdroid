package com.opirl.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EmoteImages(private val context: Context, private val scope: CoroutineScope) {
    private val cache = LruCache<String, Bitmap>(150)
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val _loaded = MutableStateFlow(0)
    val loaded: StateFlow<Int> = _loaded.asStateFlow()

    fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (!synchronized(loading) { url !in failed && loading.size < 32 && loading.add(url) }) return null
        scope.launch {
            val request = ImageRequest.Builder(context).data(url).size(112).allowHardware(false).build()
            val bitmap = runCatching { (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap)
            synchronized(loading) {
                loading.remove(url)
                if (bitmap == null && failed.size < 500) failed += url
            }
            if (bitmap != null) _loaded.update { it + 1 }
        }
        return null
    }
}
