package com.opirl.app.widgets.slideshow

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class Slide(val id: String = UUID.randomUUID().toString(), val widgetId: String? = null, val seconds: Int = 15)

@Serializable
data class SlideshowSettings(val slides: List<Slide> = emptyList())
