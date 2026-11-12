package com.opirl.app.widgets.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.opirl.app.BuildConfig
import com.opirl.app.widgets.WidgetRuntime
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

fun tileClient(base: OkHttpClient, directory: File): OkHttpClient = base.newBuilder()
    .cache(Cache(directory, 50L * 1024 * 1024))
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

fun tileLoader(client: OkHttpClient, server: String, agent: String): (TileKey) -> Bitmap? = { key ->
    val request = Request.Builder()
        .url("$server/${key.zoom}/${key.x}/${key.y}.png")
        .header("User-Agent", agent)
        .build()
    client.newCall(request).execute().use { response ->
        if (response.isSuccessful) BitmapFactory.decodeStream(response.body.byteStream()) else null
    }
}

fun osmTileStore(runtime: WidgetRuntime): TileStore<Bitmap> {
    val client = tileClient(runtime.http, File(runtime.context.cacheDir, "map-tiles"))
    val executor = ThreadPoolExecutor(
        2,
        2,
        10,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(),
        ThreadFactory { task -> Thread(task, "MapTiles").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }
    val load = tileLoader(client, "https://tile.openstreetmap.org", "opIRL/${BuildConfig.VERSION_NAME} (Android)")
    return TileStore(executor, runtime::invalidate, load = load)
}
