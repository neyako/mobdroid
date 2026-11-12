package com.opirl.app.widgets.crop

import kotlinx.serialization.Serializable

@Serializable
data class CropSettings(
    val sourceWidgetId: String? = null,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 200,
    val height: Int = 200,
)
