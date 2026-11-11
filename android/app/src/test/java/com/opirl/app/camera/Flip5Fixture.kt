package com.opirl.app.camera

internal object Flip5Fixture {
    private fun dims(vararg values: Int) = values.asList().chunked(2) { Dim(it[0], it[1]) }

    private fun lens(id: String, focal: Float, aperture: Float, width: Float, caps: Set<Capability>, sizes: List<Dim>) =
        RawLens(id, focal, aperture, width, isJunk(Facing.BACK, caps, false, sizes))

    private val sizes0 = dims(
        4000, 3000, 4000, 2252, 4000, 1716, 4000, 1638, 3840, 2160, 2992, 2992,
        2880, 2992, 2640, 1080, 2400, 1080, 2336, 1080, 1920, 1440, 1920, 1080,
        1920, 824, 1440, 1152, 1440, 1080, 1280, 720, 1088, 1088, 960, 720,
        880, 720, 720, 480, 640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes1 = dims(
        3648, 2736, 3648, 2056, 2736, 2736, 2640, 1080, 1920, 1440, 2400, 1080,
        2336, 1080, 1920, 1080, 1440, 1152, 1920, 824, 1440, 1080, 1088, 1088,
        1280, 720, 960, 720, 880, 720, 720, 480, 640, 480, 640, 360,
        320, 240, 176, 144,
    )
    private val sizes2 = dims(
        4000, 3000, 4000, 2252, 2992, 2992, 2880, 2992, 3840, 2160, 4000, 1716,
        4000, 1638, 2640, 1080, 1920, 1440, 2400, 1080, 2336, 1080, 1920, 1080,
        1440, 1152, 1920, 824, 1440, 1080, 1088, 1088, 1280, 720, 960, 720,
        880, 720, 720, 480, 640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes3 = dims(
        3024, 2080, 2784, 2080, 3008, 1696, 2784, 1568, 2080, 2080, 3008, 1352,
        3008, 1232, 2640, 1080, 1920, 1440, 2400, 1080, 2336, 1080, 1920, 1080,
        1440, 1152, 1920, 824, 1440, 1080, 1440, 980, 1088, 1088, 1280, 720,
        960, 720, 880, 720, 720, 480, 640, 480, 640, 360, 320, 240,
        176, 144,
    )
    private val sizes4 = dims(
        640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes20 = dims(
        4000, 3000, 4000, 2252, 4000, 1716, 4000, 1638, 3840, 2160, 2992, 2992,
        2880, 2992, 2640, 1080, 2400, 1080, 2336, 1080, 1920, 1440, 1920, 1080,
        1920, 824, 1440, 1152, 1440, 1080, 1280, 720, 1088, 1088, 960, 720,
        880, 720, 720, 480, 640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes23 = dims(
        4000, 3000, 4000, 2252, 2992, 2992, 2880, 2992, 3840, 2160, 4000, 1716,
        4000, 1638, 2640, 1080, 1920, 1440, 2400, 1080, 2336, 1080, 1920, 1080,
        1440, 1152, 1920, 824, 1440, 1080, 1088, 1088, 1280, 720, 960, 720,
        880, 720, 720, 480, 640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes56 = dims(
        4000, 3000, 4000, 2252, 2992, 2992, 2880, 2992, 3840, 2160, 4000, 1716,
        4000, 1638, 2640, 1080, 1920, 1440, 2400, 1080, 2336, 1080, 1920, 1080,
        1440, 1152, 1920, 824, 1440, 1080, 1088, 1088, 1280, 720, 960, 720,
        880, 720, 720, 480, 640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes58 = dims(
        4000, 3000, 4000, 2252, 2992, 2992, 2880, 2992, 3840, 2160, 4000, 1716,
        4000, 1638, 2640, 1080, 1920, 1440, 2400, 1080, 2336, 1080, 1920, 1080,
        1440, 1152, 1920, 824, 1440, 1080, 1088, 1088, 1280, 720, 960, 720,
        880, 720, 720, 480, 640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes90 = dims(
        640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes91 = dims(
        640, 480, 640, 360, 320, 240, 176, 144,
    )
    private val sizes5Nested = dims(
        4000, 3000, 4000, 2252, 2992, 2992, 2880, 2992, 3840, 2160, 4000, 1716,
        4000, 1638, 2640, 1080, 1920, 1440, 2400, 1080, 2336, 1080, 1920, 1080,
        1440, 1152, 1920, 824, 1440, 1080, 1088, 1088, 1280, 720, 960, 720,
        880, 720, 720, 480, 640, 480, 640, 360, 320, 240, 176, 144,
    )

    private val caps0 = setOf(Capability.BACKWARD_COMPATIBLE, Capability.LOGICAL_MULTI_CAMERA)
    private val caps1 = setOf(Capability.BACKWARD_COMPATIBLE)
    private val caps2 = setOf(Capability.BACKWARD_COMPATIBLE)
    private val caps3 = setOf(Capability.BACKWARD_COMPATIBLE)
    private val caps4 = setOf(Capability.SECURE_IMAGE_DATA)
    private val caps20 = setOf(Capability.BACKWARD_COMPATIBLE, Capability.LOGICAL_MULTI_CAMERA)
    private val caps23 = setOf(Capability.BACKWARD_COMPATIBLE, Capability.LOGICAL_MULTI_CAMERA)
    private val caps56 = setOf(Capability.BACKWARD_COMPATIBLE)
    private val caps58 = setOf(Capability.BACKWARD_COMPATIBLE)
    private val caps90 = setOf(Capability.SECURE_IMAGE_DATA)
    private val caps91 = setOf(Capability.SECURE_IMAGE_DATA)
    private val caps5Nested = setOf(Capability.BACKWARD_COMPATIBLE)

    private val camera0 = RawCamera(
        id = "0",
        visible = true,
        facing = Facing.BACK,
        capabilities = caps0,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 0.5f..10f,
        lens = RawLens("0", 5f, 1.8f, 7.2f, false),
        physicalLenses = listOf(
            lens("2", 1.7f, 2.2f, 4.48f, caps2, sizes2),
            lens("5", 5f, 1.8f, 7.2f, caps5Nested, sizes5Nested),
        ),
        sizes = sizes0,
        maxFps = sizes0.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW),
        hasFlash = true,
        realtimeTimestamps = true,
    )

    private val camera1 = RawCamera(
        id = "1",
        visible = true,
        facing = Facing.FRONT,
        capabilities = caps1,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("1", 3.17f, 2.2f, 4.56f, false),
        physicalLenses = emptyList(),
        sizes = sizes1,
        maxFps = sizes1.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF),
        hasFlash = false,
        realtimeTimestamps = true,
    )

    private val camera2 = RawCamera(
        id = "2",
        visible = false,
        facing = Facing.BACK,
        capabilities = caps2,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("2", 1.7f, 2.2f, 4.48f, false),
        physicalLenses = emptyList(),
        sizes = sizes2,
        maxFps = sizes2.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW),
        hasFlash = false,
        realtimeTimestamps = true,
    )

    private val camera3 = RawCamera(
        id = "3",
        visible = true,
        facing = Facing.FRONT,
        capabilities = caps3,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("3", 3.17f, 2.2f, 3.78f, false),
        physicalLenses = emptyList(),
        sizes = sizes3,
        maxFps = sizes3.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF),
        hasFlash = false,
        realtimeTimestamps = true,
    )

    private val camera4 = RawCamera(
        id = "4",
        visible = false,
        facing = Facing.FRONT,
        capabilities = caps4,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("4", 3.17f, 2.2f, 4.56f, false),
        physicalLenses = emptyList(),
        sizes = sizes4,
        maxFps = sizes4.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF),
        hasFlash = false,
        realtimeTimestamps = true,
    )

    private val camera20 = RawCamera(
        id = "20",
        visible = false,
        facing = Facing.BACK,
        capabilities = caps20,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 0.5f..10f,
        lens = RawLens("20", 5f, 1.8f, 7.2f, false),
        physicalLenses = listOf(
            lens("58", 1.7f, 2.2f, 4.48f, caps58, sizes58),
            lens("56", 5f, 1.8f, 7.2f, caps56, sizes56),
        ),
        sizes = sizes20,
        maxFps = sizes20.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW),
        hasFlash = true,
        realtimeTimestamps = true,
    )

    private val camera23 = RawCamera(
        id = "23",
        visible = false,
        facing = Facing.BACK,
        capabilities = caps23,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("23", 5f, 1.8f, 7.2f, false),
        physicalLenses = listOf(
            lens("56", 5f, 1.8f, 7.2f, caps56, sizes56),
            lens("58", 1.7f, 2.2f, 4.48f, caps58, sizes58),
        ),
        sizes = sizes23,
        maxFps = sizes23.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW),
        hasFlash = true,
        realtimeTimestamps = true,
    )

    private val camera56 = RawCamera(
        id = "56",
        visible = false,
        facing = Facing.BACK,
        capabilities = caps56,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("56", 5f, 1.8f, 7.2f, false),
        physicalLenses = emptyList(),
        sizes = sizes56,
        maxFps = sizes56.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW),
        hasFlash = true,
        realtimeTimestamps = true,
    )

    private val camera58 = RawCamera(
        id = "58",
        visible = false,
        facing = Facing.BACK,
        capabilities = caps58,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("58", 1.7f, 2.2f, 4.48f, false),
        physicalLenses = emptyList(),
        sizes = sizes58,
        maxFps = sizes58.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW),
        hasFlash = false,
        realtimeTimestamps = true,
    )

    private val camera90 = RawCamera(
        id = "90",
        visible = false,
        facing = Facing.FRONT,
        capabilities = caps90,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("90", 3.17f, 2.2f, 4.56f, false),
        physicalLenses = emptyList(),
        sizes = sizes90,
        maxFps = sizes90.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF),
        hasFlash = false,
        realtimeTimestamps = true,
    )

    private val camera91 = RawCamera(
        id = "91",
        visible = false,
        facing = Facing.BACK,
        capabilities = caps91,
        monochromeSensor = false,
        sensorOrientation = 270,
        zoomRange = 1f..8f,
        lens = RawLens("91", 5f, 1.8f, 7.2f, false),
        physicalLenses = emptyList(),
        sizes = sizes91,
        maxFps = sizes91.associateWith { 30 },
        fpsRanges = listOf(Fps(10, 10), Fps(15, 15), Fps(15, 24), Fps(24, 24), Fps(7, 30), Fps(30, 30)),
        stabilization = listOf(Stabilization.OFF, Stabilization.VIDEO, Stabilization.PREVIEW),
        hasFlash = true,
        realtimeTimestamps = true,
    )

    val cameras: List<RawCamera> = listOf(
        camera0,
        camera1,
        camera2,
        camera3,
        camera4,
        camera20,
        camera23,
        camera56,
        camera58,
        camera90,
        camera91,
    )
}
