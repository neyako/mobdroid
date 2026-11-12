package com.opirl.app.widgets.map

import kotlinx.serialization.Serializable

@Serializable
data class MapSettings(
    val northUp: Boolean = false,
    val size: Int = 1000,
    val delay: Double = 0.0,
    val cornerRadius: Int = 0,
    val borderWidth: Int = 0,
    val borderColor: Int = 0xFF000000.toInt(),
)
