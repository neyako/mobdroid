package com.opirl.app.widgets.pngtuber

import java.nio.ByteBuffer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun png(width: Int, height: Int): String {
    val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
    val size = ByteBuffer.allocate(8).putInt(width).putInt(height).array()
    return Base64.getEncoder().encodeToString(header + size + ByteArray(8))
}

private const val ALL_COSTUMES = "[1, 1, 1, 1, 1, 1, 1, 1, 1, 1]"

private fun entry(
    image: String = png(10, 10),
    costumes: String = ALL_COSTUMES,
    blink: Int = 0,
    talk: Int = 0,
    z: Int = 0,
): JsonObject = buildJsonObject {
    put("identification", 1)
    put("parentId", JsonNull)
    put("pos", "Vector2(0, 0)")
    put("offset", "Vector2(0, 0)")
    put("imageData", image)
    put("costumeLayers", costumes)
    put("showBlink", blink)
    put("showTalk", talk)
    put("zindex", z)
}

private fun part(
    size: Int = 10,
    costumes: String = ALL_COSTUMES,
    blink: Int = 0,
    talk: Int = 0,
    z: Int = 0,
): PngTuberPart = parsePngTuberFile(buildJsonObject { put("0", entry(png(size, size), costumes, blink, talk, z)) }).parts.single()

private val NEUTRAL = PngTuberPose(mouthOpen = false, eyesOpen = true)
private val BLINKING = PngTuberPose(mouthOpen = false, eyesOpen = false)
private val TALKING = PngTuberPose(mouthOpen = true, eyesOpen = true)

class PngTuberModelTest {
    @Test
    fun layersAreReadInNumericKeyOrderWithTheirStates() {
        val file = parsePngTuberFile(
            buildJsonObject {
                put("10", entry(png(30, 3), blink = 2, talk = 1, z = -4))
                put("2", entry(png(20, 2), costumes = "[0, 1, 0, 0, 0, 0, 0, 0, 0, 0]"))
                put("1", entry(png(10, 1)))
            },
        )
        assertEquals(listOf(10, 20, 30), file.parts.map { it.width })
        assertEquals(listOf(1, 2, 3), file.parts.map { it.height })
        assertEquals(listOf(png(10, 1), png(20, 2), png(30, 3)), file.images)
        assertEquals(List(10) { it == 1 }, file.parts[1].costumes)
        assertEquals(listOf(2, 1, -4), listOf(file.parts[2].blink, file.parts[2].talk, file.parts[2].z))
    }

    @Test
    fun layersThatCannotBeReadAreSkipped() {
        val file = parsePngTuberFile(
            buildJsonObject {
                put("0", entry(png(10, 10)))
                put("1", entry(image = "not a png at all, just some text that is long enough"))
                put("2", entry(costumes = "[1, 1, 1]"))
                put("3", entry(costumes = "no array"))
                put("4", buildJsonObject { put("imageData", png(5, 5)) })
            },
        )
        assertEquals(1, file.parts.size)
        assertEquals(10, file.parts.single().width)
    }

    @Test
    fun anythingButAnObjectOfLayersHasNoParts() {
        assertTrue(parsePngTuberFile(JsonNull).parts.isEmpty())
        assertTrue(parsePngTuberFile(JsonObject(emptyMap())).parts.isEmpty())
    }

    @Test
    fun theSizeIsReadFromThePngHeader() {
        assertEquals(1920 to 1080, pngSize(png(1920, 1080)))
        assertNull(pngSize(png(0, 5)))
        assertNull(pngSize("short"))
    }

    @Test
    fun aLayerBelongsToTheCostumesItsFlagsName() {
        val layer = part(costumes = "[0, 1, 0, 0, 0, 0, 0, 0, 0, 1]")
        assertFalse(layer.visible(1, NEUTRAL))
        assertTrue(layer.visible(2, NEUTRAL))
        assertTrue(layer.visible(10, NEUTRAL))
        assertFalse(layer.visible(11, NEUTRAL))
        assertFalse(layer.visible(0, NEUTRAL))
    }

    @Test
    fun blinkLayersFollowTheEyes() {
        val eyesOpen = part(blink = 1)
        val eyesClosed = part(blink = 2)
        assertTrue(eyesOpen.visible(1, NEUTRAL))
        assertFalse(eyesOpen.visible(1, BLINKING))
        assertFalse(eyesClosed.visible(1, NEUTRAL))
        assertTrue(eyesClosed.visible(1, BLINKING))
        assertTrue(part().visible(1, BLINKING))
    }

    @Test
    fun talkLayersFollowTheMouth() {
        val mouthClosed = part(talk = 1)
        val mouthOpen = part(talk = 2)
        assertTrue(mouthClosed.visible(1, NEUTRAL))
        assertFalse(mouthClosed.visible(1, TALKING))
        assertFalse(mouthOpen.visible(1, NEUTRAL))
        assertTrue(mouthOpen.visible(1, TALKING))
        assertTrue(part().visible(1, TALKING))
    }

    @Test
    fun visibleLayersAreDrawnFromTheLowestZAndKeepFileOrderForEqualZ() {
        val parts = listOf(part(z = 5), part(z = -1), part(z = 5), part(z = 0), part(costumes = "[0, 0, 0, 0, 0, 0, 0, 0, 0, 0]"))
        assertEquals(listOf(1, 3, 0, 2), visibleParts(parts, 1, NEUTRAL))
    }

    @Test
    fun aModelHasAsManyVariantsAsItsStatesDiffer() {
        assertEquals(1, poseVariants(listOf(part(), part()), 1).visible.size)
        val talking = poseVariants(listOf(part(), part(talk = 1), part(talk = 2)), 1)
        assertEquals(2, talking.visible.size)
        assertEquals(listOf(0, 1, 0, 1), talking.byPose)
        val both = poseVariants(listOf(part(), part(blink = 1), part(blink = 2), part(talk = 1), part(talk = 2)), 1)
        assertEquals(listOf(listOf(0, 1, 3), listOf(0, 1, 4), listOf(0, 2, 3), listOf(0, 2, 4)), both.visible)
        assertEquals(listOf(0, 1, 2, 3), both.byPose)
        assertEquals(1, variantOf(both, TALKING))
        assertEquals(2, variantOf(both, BLINKING))
    }

    @Test
    fun theMouthIsOpenFromAboutAFifthOfTheDetectorRange() {
        assertFalse(isMouthOpen(0.05f, 1f))
        assertTrue(isMouthOpen(0.4f, 1f))
        assertTrue(isMouthOpen(0.15f, 2f))
        assertFalse(isMouthOpen(1f, 0.15f))
    }

    @Test
    fun theEyesAreOpenFromAboutFortyPercentAndSensitivityMovesThatLine() {
        assertTrue(isEyeOpen(0.9f, 1f))
        assertFalse(isEyeOpen(0.2f, 1f))
        assertFalse(isEyeOpen(0.5f, 0.5f))
        assertTrue(isEyeOpen(0.3f, 2f))
    }

    @Test
    fun theModelFitsInsideTheSizeInPercentOfBothSidesOfThePictureLikeOnIos() {
        assertEquals(0.324f, fitScale(30, PngTuberExtent(500, 1000), 1920, 1080), 0.0001f)
        assertEquals(1.152f, fitScale(30, PngTuberExtent(500, 200), 1920, 1080), 0.0001f)
        assertEquals(0.96f, fitScale(100, PngTuberExtent(1080, 2000), 1080, 1920), 0.0001f)
    }

    @Test
    fun layersAreDecodedAtTheSmallestHalfOctaveThatStillCoversTheDisplay() {
        assertEquals(0, decodeLevel(1f))
        assertEquals(0, decodeLevel(2.5f))
        assertEquals(2, decodeLevel(0.5f))
        assertEquals(3, decodeLevel(0.3f))
        assertEquals(8, decodeLevel(0.001f))
        assertEquals(0.5f, levelScale(2), 0.0001f)
        assertTrue(listOf(0.9f, 0.6f, 0.45f, 0.3f, 0.1f).all { levelScale(decodeLevel(it)) >= it })
    }

    @Test
    fun aCompositeIsNeverLargerThanThePicture() {
        assertEquals(0.9f, compositeScale(0, PngTuberExtent(1200, 1200), 1920, 1080), 0.0001f)
        assertEquals(0.707f, compositeScale(1, PngTuberExtent(1000, 1000), 1920, 1080), 0.001f)
        assertEquals(0.5f, compositeScale(2, PngTuberExtent(400, 400), 1920, 1080), 0.0001f)
    }

    @Test
    fun theDecodedScaleCoversTheStateWithTheSmallestContent() {
        val parts = listOf(part(size = 400), part(size = 1000, talk = 2))
        val variants = poseVariants(parts, 1)
        assertEquals(0.756f, neededScale(parts, variants, 28, 1920, 1080), 0.0001f)
        assertEquals(1f, neededScale(parts, variants, 100, 1920, 1080))
        assertEquals(1f, neededScale(emptyList(), poseVariants(emptyList(), 1), 28, 1920, 1080))
    }
}
