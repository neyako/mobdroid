package com.opirl.app.widgets.pngtuber

import kotlinx.serialization.Serializable

@Serializable
data class PngTuberSettings(
    val cameraKey: String? = null,
    val model: String? = null,
    val costume: Int = 1,
    val mirror: Boolean = false,
    val mouthSensitivity: Int = 100,
    val eyeSensitivity: Int = 100,
)
