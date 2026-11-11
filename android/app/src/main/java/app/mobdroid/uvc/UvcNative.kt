package app.mobdroid.uvc

import android.view.Surface

internal object UvcNative {
    init {
        System.loadLibrary("mobdroid_uvc")
    }

    external fun open(fd: Int): Long

    external fun frameDescriptors(handle: Long): IntArray

    external fun start(handle: Long, mjpeg: Boolean, width: Int, height: Int, fps: Int, surface: Surface)

    external fun stop(handle: Long)

    external fun idleMs(handle: Long): Long

    external fun close(handle: Long)
}
