package com.opirl.app.recording

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.opirl.app.engine.mp4.finishMp4
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Recording(val uri: Uri, val name: String, val durationMs: Long, val bytes: Long) {
    val isReplay: Boolean get() = name.startsWith(REPLAY_LABEL)
}

private const val REPLAY_LABEL = "Replay "

class RecordingFile(val uri: Uri, private val descriptor: ParcelFileDescriptor) : Closeable {
    val input: FileChannel = FileInputStream(descriptor.fileDescriptor).channel
    val output: FileChannel = FileOutputStream(descriptor.fileDescriptor).channel

    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { descriptor.close() }
    }
}

class RecordingStore(private val context: Context) {
    private val resolver = context.contentResolver
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val createdAtSeconds = System.currentTimeMillis() / 1000
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun create(replay: Boolean = false): RecordingFile {
        val uri = insert(videos, "mp4", "video/mp4", Environment.DIRECTORY_MOVIES, if (replay) REPLAY_LABEL else "")
        val descriptor = try {
            resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Storage unavailable")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return RecordingFile(uri, descriptor)
    }

    fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        refresh()
    }

    fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
        refresh()
    }

    fun refresh() {
        _recordings.value = runCatching { query(pending = false) }
            .onFailure { Log.w(TAG, "Listing recordings failed", it) }
            .getOrDefault(emptyList())
    }

    fun recover(): Int {
        var recovered = 0
        val unfinished = runCatching { query(pending = true) }.getOrDefault(emptyList())
        for (recording in unfinished) {
            val summary = try {
                RecordingFile(recording.uri, resolver.openFileDescriptor(recording.uri, "rw") ?: continue).use {
                    finishMp4(it.input, it.output)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not recover ${recording.name}", e)
                continue
            }
            if (summary != null) {
                publish(recording.uri)
                recovered++
            } else {
                resolver.delete(recording.uri, null, null)
            }
        }
        refresh()
        return recovered
    }

    fun saveSnapshot(bitmap: Bitmap): Boolean {
        val uri = runCatching { insert(images, "jpg", "image/jpeg", Environment.DIRECTORY_PICTURES) }.getOrNull() ?: return false
        return try {
            resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot failed", e)
            resolver.delete(uri, null, null)
            false
        }
    }

    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }
        .getOrDefault(Long.MAX_VALUE)

    private fun insert(collection: Uri, extension: String, mime: String, directory: String, label: String = ""): Uri {
        val name = "opIRL $label${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())}.$extension"
        return resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: throw IOException("Storage unavailable")
    }

    private fun query(pending: Boolean): List<Recording> {
        var selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        if (pending) selection += " AND ${MediaStore.MediaColumns.DATE_ADDED} < $createdAtSeconds"
        val arguments = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER/", context.packageName),
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        }
        var collection = videos
        if (pending && Build.VERSION.SDK_INT >= 30) {
            arguments.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
        } else if (pending) {
            @Suppress("DEPRECATION")
            collection = MediaStore.setIncludePending(videos)
            selection += " AND ${MediaStore.MediaColumns.IS_PENDING} = 1"
        }
        arguments.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
        )
        val result = ArrayList<Recording>()
        resolver.query(collection, projection, arguments, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result += Recording(
                    Uri.withAppendedPath(videos, cursor.getLong(0).toString()),
                    cursor.getString(1).orEmpty().removeSuffix(".mp4").removePrefix("opIRL "),
                    cursor.getLong(2),
                    cursor.getLong(3),
                )
            }
        }
        return result
    }

    private companion object {
        const val TAG = "RecordingStore"
        const val FOLDER = "opIRL"
    }
}
