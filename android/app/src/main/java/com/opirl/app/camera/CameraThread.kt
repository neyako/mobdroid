package com.opirl.app.camera

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.Executor

internal object CameraThread {
    val handler: Handler = Handler(HandlerThread("camera", Process.THREAD_PRIORITY_DISPLAY).apply { start() }.looper)
    val executor = Executor { handler.post(it) }
}
