package com.opirl.app.widgets

import com.opirl.app.widgets.crop.CropSettings
import com.opirl.app.widgets.crop.cropRect
import com.opirl.app.widgets.slideshow.nextSlide
import com.opirl.app.widgets.videosource.VideoSourceSettings
import com.opirl.app.widgets.videosource.feedBox
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetLayoutTest {
    private fun percent(vararg values: Float) = values.map { (it * 100).roundToInt() }

    @Test
    fun aFeedHasThePictureShapeUntilItIsCroppedOrTurned() {
        val plain = feedBox(VideoSourceSettings(), 0.5f, 1920, 1080)!!
        assertEquals(listOf(50, 50, 0, 0, 100, 100), percent(plain.width, plain.height, plain.layerX, plain.layerY, plain.layerWidth, plain.layerHeight))
        val cropped = feedBox(VideoSourceSettings(cropEnabled = true), 0.25f, 1920, 1080)!!
        assertEquals(listOf(25, 50, 25, 0, 50, 100), percent(cropped.width, cropped.height, cropped.cropX, cropped.cropY, cropped.cropWidth, cropped.cropHeight))
        val turned = feedBox(VideoSourceSettings(rotation = 90), 0.1f, 1920, 1080)!!
        assertEquals(listOf(10, 32), percent(turned.width, turned.height))
        assertEquals(listOf(-39, 22, 178, 56), percent(turned.layerX, turned.layerY, turned.layerWidth, turned.layerHeight))
    }

    @Test
    fun aMirroredFeedReadsItsCropBackwards() {
        val box = feedBox(VideoSourceSettings(cropEnabled = true, mirror = true), 0.25f, 1920, 1080)!!
        assertEquals(listOf(75, -50), percent(box.cropX, box.cropWidth))
    }

    @Test
    fun aCropStaysInsideThePage() {
        val rect = cropRect(CropSettings(x = 1000, y = 600, width = 500, height = 500), 1280, 720)!!
        assertEquals(listOf(78, 83, 22, 17), percent(rect.x, rect.y, rect.width, rect.height))
        assertNull(cropRect(CropSettings(x = 1280, width = 100), 1280, 720))
    }

    @Test
    fun slidesFollowEachOtherAndStartOver() {
        val seconds = listOf(5, 10)
        val first = nextSlide(0, 0, 1_000, seconds)!!
        assertEquals(0 to 6_000L, first.index to first.end)
        val same = nextSlide(first.index, first.end, 5_999, seconds)!!
        assertEquals(0 to 6_000L, same.index to same.end)
        val second = nextSlide(first.index, first.end, 6_000, seconds)!!
        assertEquals(1 to 16_000L, second.index to second.end)
        val again = nextSlide(second.index, second.end, 16_000, seconds)!!
        assertEquals(0 to 21_000L, again.index to again.end)
        assertNull(nextSlide(0, 0, 1_000, emptyList()))
    }
}
