package com.opirl.app.uvc

import kotlin.math.abs

data class UvcFormat(val width: Int, val height: Int, val fps: Int, val mjpeg: Boolean)

fun List<UvcFormat>.closest(width: Int, height: Int, fps: Int): UvcFormat? =
    filter { it.width == width && it.height == height }
        .minWithOrNull(compareBy<UvcFormat> { abs(it.fps - fps) }.thenBy { !it.mjpeg })
