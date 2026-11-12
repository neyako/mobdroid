package com.opirl.app.widgets.videosource

import kotlinx.serialization.Serializable

const val USB_CAMERA = "usb"

@Serializable
data class VideoSourceSettings(
    val camera: String? = null,
    val cornerRadius: Int = 0,
    val cropEnabled: Boolean = false,
    val cropX: Int = 25,
    val cropY: Int = 0,
    val cropWidth: Int = 50,
    val cropHeight: Int = 100,
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val borderWidth: Int = 0,
    val borderColor: Int = 0xFFFFFFFF.toInt(),
)

class FeedBox(
    val width: Float,
    val height: Float,
    val layerX: Float,
    val layerY: Float,
    val layerWidth: Float,
    val layerHeight: Float,
    val cropX: Float,
    val cropY: Float,
    val cropWidth: Float,
    val cropHeight: Float,
)

fun feedBox(settings: VideoSourceSettings, share: Float, pictureWidth: Int, pictureHeight: Int): FeedBox? {
    val left = if (settings.cropEnabled) settings.cropX.coerceIn(0, 99) / 100f else 0f
    val top = if (settings.cropEnabled) settings.cropY.coerceIn(0, 99) / 100f else 0f
    val wide = if (settings.cropEnabled) (settings.cropWidth / 100f).coerceIn(0.01f, 1f - left) else 1f
    val tall = if (settings.cropEnabled) (settings.cropHeight / 100f).coerceIn(0.01f, 1f - top) else 1f
    val aspect = wide * pictureWidth / (tall * pictureHeight)
    val turned = settings.rotation % 180 != 0
    val frameWidth = share * pictureWidth
    val contentWidth = if (turned) frameWidth * aspect else frameWidth
    val contentHeight = contentWidth / aspect
    val frameHeight = if (turned) contentWidth else contentHeight
    if (frameWidth < 1f || frameHeight < 1f) return null
    return FeedBox(
        width = share,
        height = frameHeight / pictureHeight,
        layerX = (frameWidth - contentWidth) / 2 / frameWidth,
        layerY = (frameHeight - contentHeight) / 2 / frameHeight,
        layerWidth = contentWidth / frameWidth,
        layerHeight = contentHeight / frameHeight,
        cropX = if (settings.mirror) left + wide else left,
        cropY = top,
        cropWidth = if (settings.mirror) -wide else wide,
        cropHeight = tall,
    )
}
