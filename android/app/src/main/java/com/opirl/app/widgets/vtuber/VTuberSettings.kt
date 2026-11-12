package com.opirl.app.widgets.vtuber

import kotlinx.serialization.Serializable

@Serializable
data class VTuberSettings(
    val model: String? = null,
    val modelName: String = "",
    val camera: String? = null,
    val cameraPositionY: Int = 137,
    val cameraFieldOfView: Int = 18,
    val armsAngle: Int = 72,
    val mirror: Boolean = false,
    val mouthSensitivity: Int = 100,
    val eyesSensitivity: Int = 100,
)
