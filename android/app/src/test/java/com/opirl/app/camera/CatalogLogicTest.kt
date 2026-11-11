package com.opirl.app.camera

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val backwardCompatible = setOf(Capability.BACKWARD_COMPATIBLE)
private val logicalCapabilities = backwardCompatible + Capability.LOGICAL_MULTI_CAMERA
private val fullSizes = listOf(Dim(3840, 2160), Dim(1920, 1080), Dim(1280, 720), Dim(640, 360))

private fun lens(id: String, focal: Float, width: Float, aperture: Float = 1.8f) =
    RawLens(id, focal, aperture, width, false)

private fun camera(
    id: String,
    lens: RawLens,
    visible: Boolean = true,
    facing: Facing = Facing.BACK,
    capabilities: Set<Capability> = backwardCompatible,
    zoomRange: ClosedFloatingPointRange<Float> = 1f..8f,
    physical: List<RawLens> = emptyList(),
    sizes: List<Dim> = fullSizes,
) = RawCamera(
    id = id,
    visible = visible,
    facing = facing,
    capabilities = capabilities,
    monochromeSensor = false,
    sensorOrientation = 90,
    zoomRange = zoomRange,
    lens = lens,
    physicalLenses = physical,
    sizes = sizes,
    maxFps = sizes.associateWith { 30 },
    fpsRanges = listOf(Fps(30, 30)),
    stabilization = listOf(Stabilization.OFF),
    hasFlash = false,
    realtimeTimestamps = false,
)

private fun labels(cameras: List<RawCamera>) = buildCatalog(cameras).associate { it.camera.id to it.label }

class CatalogLogicTest {
    @Test
    fun `flip5 exposes one logical back camera and one front camera`() {
        val entries = buildCatalog(Flip5Fixture.cameras)
        assertEquals(listOf("0", "1"), entries.map { it.camera.id })
        assertEquals(listOf("Back", "Front"), entries.map { it.label })
        assertTrue(entries.all { it.physicalId == null })
    }

    @Test
    fun `flip5 back camera stops are the ultra wide and the main lens`() {
        val back = buildCatalog(Flip5Fixture.cameras).first()
        assertEquals(listOf(0.5f, 1f), back.zoomStops)
        assertEquals(0.5f..10f, back.zoomRange)
        assertEquals(listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW), back.camera.stabilization)
    }

    @Test
    fun `flip5 front camera has no stops and no video stabilization`() {
        val front = buildCatalog(Flip5Fixture.cameras).last()
        assertEquals(emptyList(), front.zoomStops)
        assertEquals(listOf(Stabilization.OFF), front.camera.stabilization)
        assertFalse(front.camera.hasFlash)
    }

    @Test
    fun `flip5 result does not depend on probe order or hidden ids`() {
        val expected = buildCatalog(Flip5Fixture.cameras)
        assertEquals(expected, buildCatalog(Flip5Fixture.cameras.reversed()))
        assertEquals(
            expected.map { it.copy(fallbackLenses = emptyList()) },
            buildCatalog(Flip5Fixture.cameras.filter { it.visible }).map { it.copy(fallbackLenses = emptyList()) },
        )
    }

    @Test
    fun `flip5 secure only cameras are junk`() {
        val secure = Flip5Fixture.cameras.filter { it.id in setOf("4", "90", "91") }
        assertEquals(3, secure.size)
        assertTrue(secure.all { isJunk(it.facing, it.capabilities, it.monochromeSensor, it.sizes) })
    }

    @Test
    fun `flip5 does not advertise 60 fps through camera2`() {
        val back = Flip5Fixture.cameras.first { it.id == "0" }
        assertFalse(supportsFps(back.fpsRanges, back.maxFps, 60))
        assertTrue(supportsFps(back.fpsRanges, back.maxFps, 30))
    }

    @Test
    fun `pixel style logical camera offers ultra wide main and telephoto stops`() {
        val main = lens("4", 6.9f, 9.8f)
        val cameras = listOf(
            camera(
                "0",
                lens("0", 6.9f, 9.8f),
                capabilities = logicalCapabilities,
                zoomRange = 0.5f..30f,
                physical = listOf(lens("2", 2f, 5f, 2.2f), main, lens("3", 12.5f, 3.5f, 2.8f)),
            ),
        )
        assertEquals(listOf(0.5f, 1f, 5f), buildCatalog(cameras).single().zoomStops)
    }

    @Test
    fun `samsung style logical camera snaps stops to advertised values`() {
        val cameras = listOf(
            camera(
                "0",
                lens("0", 6.3f, 9.8f),
                capabilities = logicalCapabilities,
                zoomRange = 0.6f..100f,
                physical = listOf(
                    lens("2", 2.2f, 6f, 2.2f),
                    lens("3", 6.3f, 9.8f),
                    lens("4", 9.65f, 5f, 2.4f),
                    lens("5", 13f, 2f, 4.9f),
                ),
            ),
        )
        assertEquals(listOf(0.6f, 1f, 3f, 10f), buildCatalog(cameras).single().zoomStops)
    }

    @Test
    fun `lens close to the main lens does not add a stop`() {
        val cameras = listOf(
            camera(
                "0",
                lens("0", 5f, 7.2f),
                capabilities = logicalCapabilities,
                physical = listOf(lens("1", 5f, 7.2f), lens("2", 5.2f, 7.2f, 2f)),
            ),
        )
        assertEquals(emptyList(), buildCatalog(cameras).single().zoomStops)
    }

    @Test
    fun `lens the zoom range cannot reach is a stop that opens a physical stream`() {
        val cameras = listOf(
            camera(
                "0",
                lens("0", 5f, 7.2f),
                capabilities = logicalCapabilities,
                physical = listOf(lens("5", 5f, 7.2f), lens("2", 1.7f, 4.48f, 2.2f)),
            ),
        )
        val entry = buildCatalog(cameras).single()
        assertEquals("Back", entry.label)
        assertEquals(listOf(0.5f, 1f), entry.zoomStops)
        val lens = entry.lenses.single()
        assertEquals(0.5f, lens.ratio)
        assertEquals("0" to "2", lens.entry.camera.id to lens.entry.physicalId)
        assertEquals(1f..8f, lens.entry.zoomRange)
    }

    @Test
    fun `hidden auxiliary cameras are kept and junk sensors are dropped`() {
        val cameras = listOf(
            camera("0", lens("0", 5f, 7.2f)),
            camera("1", lens("1", 3.17f, 4.56f, 2.2f), facing = Facing.FRONT),
            camera("2", lens("2", 1.7f, 4.48f, 2.2f), visible = false),
            camera("3", lens("3", 15f, 7.2f, 2.8f), visible = false),
            camera("4", lens("4", 2f, 3f, 2.4f), visible = false, sizes = listOf(Dim(1600, 1200), Dim(640, 480))),
            camera("5", lens("5", 4f, 5f), visible = false, capabilities = setOf(Capability.DEPTH_OUTPUT)),
            camera(
                "6",
                lens("6", 5f, 7.2f, 2f),
                visible = false,
                capabilities = backwardCompatible + Capability.MONOCHROME,
            ),
            camera("7", lens("7", 5f, 7.2f), visible = false),
        )
        assertEquals(
            mapOf("0" to "Wide", "1" to "Front", "2" to "Ultra wide", "3" to "Telephoto 3x"),
            labels(cameras),
        )
    }

    @Test
    fun `hidden logical camera does not replace standalone lens cameras`() {
        val cameras = listOf(
            camera("0", lens("0", 5f, 7.2f)),
            camera("2", lens("2", 1.7f, 4.48f, 2.2f), visible = false),
            camera(
                "20",
                lens("20", 5f, 7.2f),
                visible = false,
                capabilities = logicalCapabilities,
                zoomRange = 0.5f..10f,
                physical = listOf(lens("56", 5f, 7.2f), lens("58", 1.7f, 4.48f, 2.2f)),
            ),
        )
        assertEquals(mapOf("0" to "Wide", "2" to "Ultra wide"), labels(cameras))
    }

    @Test
    fun `hidden logical camera is kept when it adds lenses`() {
        val cameras = listOf(
            camera("0", lens("0", 5f, 7.2f)),
            camera(
                "20",
                lens("20", 5f, 7.2f),
                visible = false,
                capabilities = logicalCapabilities,
                zoomRange = 0.6f..10f,
                physical = listOf(lens("56", 5f, 7.2f), lens("58", 1.7f, 4.48f, 2.2f)),
            ),
        )
        val entries = buildCatalog(cameras)
        assertEquals(listOf("Wide", "Back"), entries.map { it.label })
        assertEquals(listOf(0.6f, 1f), entries.last().zoomStops)
    }

    @Test
    fun `lens the zoom range cannot reach is opened as its own camera when it has one`() {
        val cameras = listOf(
            camera(
                "0",
                lens("0", 5f, 7.2f),
                capabilities = logicalCapabilities,
                physical = listOf(lens("5", 5f, 7.2f), lens("2", 1.7f, 4.48f, 2.2f)),
            ),
            camera("2", lens("2", 1.7f, 4.48f, 2.2f), visible = false),
        )
        val lens = buildCatalog(cameras).single().lenses.single().entry
        assertEquals("2" to null, lens.camera.id to lens.physicalId)
    }

    @Test
    fun `hidden physical ids the logical camera reaches are not listed`() {
        val cameras = listOf(
            camera(
                "0",
                lens("0", 5f, 7.2f),
                capabilities = logicalCapabilities,
                zoomRange = 0.5f..8f,
                physical = listOf(lens("5", 5f, 7.2f), lens("2", 1.7f, 4.48f, 2.2f)),
            ),
            camera("2", lens("2", 1.7f, 4.48f, 2.2f), visible = false),
        )
        assertEquals(listOf("0" to null), buildCatalog(cameras).map { it.camera.id to it.physicalId })
    }

    @Test
    fun `vivo with a 35 mm main lens lists the hidden three lens camera with the ultra wide as its widest stop`() {
        val main = lens("3", 11.56f, 11.424f, 1.85f)
        val ultraWide = lens("4", 4.03f, 9.994f, 2f)
        val telephoto = lens("5", 22.48f, 9.139f, 2.67f)
        val cameras = listOf(
            camera(
                "0",
                main.copy(id = "0"),
                capabilities = logicalCapabilities,
                zoomRange = 0.65f..10f,
                physical = listOf(ultraWide, main),
            ),
            camera("1", lens("1", 3.64f, 5.2f, 2f), facing = Facing.FRONT),
            camera(
                "2",
                main.copy(id = "2"),
                visible = false,
                capabilities = logicalCapabilities,
                zoomRange = 0.65f..10f,
                physical = listOf(ultraWide, main, telephoto),
            ),
            camera("3", main, visible = false),
            camera("4", ultraWide, visible = false),
            camera("5", telephoto, visible = false),
        )
        val entries = buildCatalog(cameras)
        assertEquals(listOf("2" to "Back", "1" to "Front"), entries.map { it.camera.id to it.label })
        assertEquals(listOf(0.4f, 1f, 2.5f), entries.first().zoomStops)
        val ultraWideLens = entries.first().lenses.single()
        assertEquals(0.4f, ultraWideLens.ratio)
        assertEquals("4" to null, ultraWideLens.entry.camera.id to ultraWideLens.entry.physicalId)
        assertEquals(listOf(1f to "3", 2.5f to "5"), entries.first().fallbackLenses.map { it.ratio to it.entry.camera.id })
    }

    @Test
    fun `usb cameras are listed without lens data`() {
        val usb = { id: String, sizes: List<Dim> ->
            camera(
                id,
                RawLens(id, 0f, 0f, 0f, false),
                facing = Facing.EXTERNAL,
                capabilities = emptySet(),
                zoomRange = 1f..1f,
                sizes = sizes,
            )
        }
        val entries = buildCatalog(
            listOf(usb("5", listOf(Dim(1280, 720))), usb("6", listOf(Dim(640, 480))), usb("7", emptyList())),
        )
        assertEquals(listOf("USB camera", "USB camera 2"), entries.map { it.label })
        assertTrue(entries.all { it.zoomStops.isEmpty() && it.camera.facing == Facing.EXTERNAL })
    }

    @Test
    fun `two distinct front lenses are named by field of view`() {
        val cameras = listOf(
            camera("1", lens("1", 3.17f, 4.56f, 2.2f), facing = Facing.FRONT),
            camera("3", lens("3", 1.5f, 4f, 2.4f), facing = Facing.FRONT, visible = false),
        )
        assertEquals(mapOf("1" to "Front", "3" to "Front ultra wide"), labels(cameras))
    }

    @ParameterizedTest
    @CsvSource(
        "BACK, BACKWARD_COMPATIBLE, 3840, 2160, false",
        "BACK, '', 3840, 2160, true",
        "BACK, DEPTH_OUTPUT, 640, 480, true",
        "BACK, 'BACKWARD_COMPATIBLE;MONOCHROME', 3840, 2160, true",
        "BACK, 'BACKWARD_COMPATIBLE;SYSTEM_CAMERA', 3840, 2160, true",
        "BACK, SECURE_IMAGE_DATA, 3840, 2160, true",
        "BACK, BACKWARD_COMPATIBLE, 1600, 1200, true",
        "BACK, BACKWARD_COMPATIBLE, 1920, 1080, false",
        "FRONT, BACKWARD_COMPATIBLE, 1280, 720, false",
        "FRONT, BACKWARD_COMPATIBLE, 640, 480, true",
        "EXTERNAL, '', 640, 480, false",
    )
    fun `junk filter`(facing: Facing, capabilities: String, width: Int, height: Int, junk: Boolean) {
        val caps = capabilities.split(';').filter { it.isNotBlank() }.map { Capability.valueOf(it) }.toSet()
        assertEquals(junk, isJunk(facing, caps, false, listOf(Dim(width, height))))
    }

    @Test
    fun `junk filter rejects monochrome sensors and cameras without sizes`() {
        assertTrue(isJunk(Facing.BACK, backwardCompatible, true, fullSizes))
        assertTrue(isJunk(Facing.BACK, backwardCompatible, false, emptyList()))
        assertTrue(isJunk(Facing.EXTERNAL, emptySet(), false, emptyList()))
    }

    @ParameterizedTest
    @CsvSource(
        "0.5465, 0.5, 10, 0.5",
        "0.57, 0.6, 100, 0.6",
        "0.7, 0.5, 10, 0.7",
        "1.04, 0.5, 10, 1.0",
        "2.95, 0.5, 30, 3.0",
        "4.87, 0.5, 30, 5.0",
        "10.2, 0.6, 100, 10.0",
        "3.7, 0.5, 30, 3.7",
        "50.0, 0.5, 30, 30.0",
    )
    fun `stop snapping`(ratio: Float, lower: Float, upper: Float, expected: Float) {
        assertEquals(expected, niceStop(ratio, lower..upper))
    }

    @Test
    fun labelsUseTheGivenWords() {
        val words = CameraWords(back = "Sau", front = "Trước")
        assertEquals(listOf("Sau", "Trước"), buildCatalog(Flip5Fixture.cameras, words).map { it.label })
    }
}
