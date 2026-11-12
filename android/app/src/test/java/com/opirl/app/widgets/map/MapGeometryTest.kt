package com.opirl.app.widgets.map

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapGeometryTest {
    private fun fix(speed: Float = 0f, course: Float = -1f, time: Long = 0, latitude: Double = 51.5, longitude: Double = 0.0) =
        MapFix(latitude, longitude, speed, course, time)

    private fun metersPerPixel(latitude: Double, zoom: Int) = 40_075_016.686 * cos(Math.toRadians(latitude)) / (TILE_SIZE shl zoom)

    private fun tileOf(latitude: Double, longitude: Double, zoom: Int) =
        TileKey(zoom, (worldX(longitude, zoom) / TILE_SIZE).toInt(), (worldY(latitude, zoom) / TILE_SIZE).toInt())

    @Test
    fun worldPixelsLandInTheTilesOpenStreetMapNamesForThePlace() {
        assertEquals(TileKey(10, 511, 340), tileOf(51.5074, -0.1278, 10))
        assertEquals(TileKey(12, 3636, 1612), tileOf(35.6762, 139.6503, 12))
        assertEquals(TileKey(10, 942, 614), tileOf(-33.8688, 151.2093, 10))
    }

    @Test
    fun theZoomFollowsTheGroundTheMapCovers() {
        val views = listOf(500, 1000, 2000, 5000, 10_000).map { mapView(fix(), true, it, 248) }
        assertEquals(listOf(17, 16, 15, 13, 12), views.map { it.zoom })
        assertEquals(0.688f, views[1].scale, 0.001f)
        assertEquals(views[1].scale, views[2].scale, 0.001f)
        assertEquals(1.101f, views[3].scale, 0.001f)
        assertEquals(1.101f, views[4].scale, 0.001f)
    }

    @Test
    fun aTileIsStretchedAtMostAFifthToSaveALevel() {
        val view = mapView(fix(), true, 1000, 193)
        assertEquals(15, view.zoom)
        assertEquals(1.071f, view.scale, 0.001f)
    }

    @Test
    fun theMapSitsOnTheFixWhenNotMoving() {
        val view = mapView(fix(speed = 3f, course = 90f), false, 1000, 248)
        assertEquals(0, view.marker)
        assertEquals(worldX(0.0, view.zoom), view.centerX, 1.0)
        assertEquals(worldY(51.5, view.zoom), view.centerY, 1.0)
        assertEquals(90, view.heading)
    }

    @Test
    fun fastMovementZoomsOutAndPutsTheMarkerBehindTheCentre() {
        val view = mapView(fix(speed = 12f, course = 90f), false, 1000, 248)
        assertEquals(15, view.zoom)
        assertEquals(31, view.marker)
        val east = (view.centerX - worldX(0.0, view.zoom)) * metersPerPixel(51.5, view.zoom)
        val north = (worldY(51.5, view.zoom) - view.centerY) * metersPerPixel(51.5, view.zoom)
        assertEquals(147.4, east, 4.0)
        assertEquals(0.0, north, 4.0)
    }

    @Test
    fun theMarkerDriftsFromTheMiddleToAQuarterOfTheSideBetweenFourAndTwentyMetersPerSecond() {
        val markers = listOf(4f, 12f, 20f, 40f).map { mapView(fix(speed = it, course = 0f), false, 1000, 248).marker }
        assertEquals(listOf(0, 31, 62, 62), markers)
    }

    @Test
    fun northUpStillZoomsOutWithSpeedButKeepsTheMarkerInTheMiddle() {
        val view = mapView(fix(speed = 12f, course = 90f), true, 1000, 248)
        assertEquals(15, view.zoom)
        assertEquals(0, view.marker)
        assertEquals(0, view.heading)
        assertEquals(worldX(0.0, view.zoom), view.centerX, 1.0)
    }

    @Test
    fun anUnknownCourseKeepsNorthAtTheTopAndMovesTheCentreNorth() {
        val view = mapView(fix(speed = 12f, course = -1f), false, 1000, 248)
        assertEquals(0, view.heading)
        val north = (worldY(51.5, view.zoom) - view.centerY) * metersPerPixel(51.5, view.zoom)
        assertEquals(147.4, north, 4.0)
    }

    @Test
    fun theHeadingIsRoundedToAWholeDegree() {
        assertEquals(0, mapView(fix(course = 359.6f), false, 1000, 248).heading)
        assertEquals(90, mapView(fix(course = 90.4f), false, 1000, 248).heading)
    }

    @Test
    fun aMovementUnderAPixelLeavesTheViewUnchanged() {
        val a = mapView(fix(latitude = 51.5, longitude = 0.0), true, 1000, 248)
        val b = mapView(fix(latitude = 51.500002, longitude = 0.000003), true, 1000, 248)
        val c = mapView(fix(latitude = 51.5, longitude = 0.0003), true, 1000, 248)
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun aTurningMapNeedsExactlyTheTilesItCovers() {
        val centre = 512 * TILE_SIZE + 128.0
        val still = MapView(10, 1f, centre, centre, 0, 0)
        assertEquals(listOf(TileKey(10, 512, 512)), still.tiles(200))
        val diamond = still.copy(heading = 45).tiles(200)
        assertEquals(5, diamond.size)
        assertTrue(TileKey(10, 513, 513) !in diamond)
        assertTrue(TileKey(10, 513, 512) in diamond)
        assertEquals(still.tiles(200), still.copy(heading = 180).tiles(200))
    }

    @Test
    fun everyPixelOfATurnedMapLiesInATileItAsksFor() {
        for (heading in listOf(0, 17, 45, 90, 133, 200, 271, 359)) {
            val view = MapView(12, 0.8f, 5000.3, 7000.7, heading, 0)
            val asked = view.tiles(200).toSet()
            val turn = Math.toRadians(heading.toDouble())
            for (screenX in 0..200 step 10) {
                for (screenY in 0..200 step 10) {
                    val x = screenX - 100.0
                    val y = screenY - 100.0
                    val worldX = view.centerX + (x * cos(turn) - y * sin(turn)) / view.scale
                    val worldY = view.centerY + (x * sin(turn) + y * cos(turn)) / view.scale
                    val tile = TileKey(12, floor(worldX / TILE_SIZE).toInt(), floor(worldY / TILE_SIZE).toInt())
                    assertTrue(tile in asked, "heading $heading, pixel $screenX,$screenY needs $tile")
                }
            }
        }
    }

    @Test
    fun tilesWrapAroundTheDateLineAndStopAtThePoles() {
        val view = MapView(3, 1f, 20.0, 30.0, 0, 0)
        val tiles = view.tiles(100)
        assertTrue(tiles.all { it.y >= 0 })
        assertTrue(tiles.any { it.x < 0 })
        assertEquals(7, TileKey(3, -1, 0).wrapped().x)
        assertEquals(1, TileKey(3, 9, 0).wrapped().x)
    }

    @Test
    fun theMapKeepsAtLeastFortyPixelsAndTheBorderStaysInsideThePicture() {
        assertEquals(MapLayout(248, 0), mapLayout(1080, 23, 0))
        assertEquals(MapLayout(248, 6), mapLayout(1080, 23, 100))
        assertEquals(MapLayout(40, 0), mapLayout(400, 5, 0))
        val full = mapLayout(1080, 100, 100)
        assertEquals(1080, full.total)
        assertEquals(27, full.border)
    }

    @Test
    fun metersMoveTheCoordinatesByTheLocalDegreeLength() {
        val north = translateMeters(10.0, 20.0, 0.0, 1000.0)
        assertEquals(10.008983, north.latitude, 0.000001)
        assertEquals(20.0, north.longitude, 0.000001)
        val east = translateMeters(60.0, 20.0, 1000.0, 0.0)
        assertEquals(20.017966, east.longitude, 0.000001)
        assertEquals(-179.999202, translateMeters(0.0, 179.9999, 100.0, 0.0).longitude, 0.00001)
    }

    @Test
    fun theDelayedFixIsTheNewestOneThatIsOldEnough() {
        val queue = FixQueue()
        listOf(0L, 1000, 2000, 3000).forEach { queue.add(fix(time = it)) }
        assertEquals(1000, queue.current(now = 3500, delay = 2000)?.time)
        assertEquals(4000, queue.nextChange(now = 3500, delay = 2000))
        assertEquals(3000, queue.current(now = 3500, delay = 0)?.time)
        assertNull(queue.nextChange(now = 3500, delay = 0))
    }

    @Test
    fun theOldestFixShowsUntilAnyFixIsOldEnough() {
        val queue = FixQueue()
        listOf(0L, 1000).forEach { queue.add(fix(time = it)) }
        assertEquals(0, queue.current(now = 500, delay = 2000)?.time)
        assertEquals(3000, queue.nextChange(now = 500, delay = 2000))
    }

    @Test
    fun theSameFixTwiceIsOneFixAndTwelveSecondsOfHistoryAreKept() {
        val queue = FixQueue()
        queue.add(fix(time = 1000))
        queue.add(fix(time = 1000))
        assertNull(queue.nextChange(now = 0, delay = 0))
        queue.add(fix(time = 9000))
        queue.add(fix(time = 20_000))
        assertEquals(9000, queue.current(now = 20_000, delay = 15_000)?.time)
        assertEquals(20_000, queue.current(now = 20_000, delay = 0)?.time)
    }
}
