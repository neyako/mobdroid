package com.opirl.app.widgets.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

const val TILE_SIZE = 256
const val MAX_ZOOM = 19
private const val EARTH_CIRCUMFERENCE = 40_075_016.686
private const val MAX_LATITUDE = 85.0511287798
private const val FAST = 4f

data class TileKey(val zoom: Int, val x: Int, val y: Int) {
    fun wrapped() = copy(x = Math.floorMod(x, 1 shl zoom))
}

class MapFix(val latitude: Double, val longitude: Double, val speed: Float, val course: Float, val time: Long)

class Position(val latitude: Double, val longitude: Double)

data class MapView(
    val zoom: Int,
    val scale: Float,
    val centerX: Double,
    val centerY: Double,
    val heading: Int,
    val marker: Int,
)

data class MapLayout(val side: Int, val border: Int) {
    val total get() = side + 2 * border
}

fun worldX(longitude: Double, zoom: Int): Double = (longitude + 180.0) / 360.0 * (TILE_SIZE shl zoom)

fun worldY(latitude: Double, zoom: Int): Double {
    val sine = sin(Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
    return (0.5 - ln((1 + sine) / (1 - sine)) / (4 * PI)) * (TILE_SIZE shl zoom)
}

fun translateMeters(latitude: Double, longitude: Double, east: Double, north: Double): Position {
    val scale = cos(Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
    val newLatitude = (latitude + 360 * north / EARTH_CIRCUMFERENCE).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    val newLongitude = longitude + 360 * east / (EARTH_CIRCUMFERENCE * scale)
    return Position(newLatitude, ((newLongitude + 180) % 360 + 360) % 360 - 180)
}

fun mapLayout(shortSide: Int, percent: Int, borderPercent: Int): MapLayout {
    val wanted = max(40, shortSide * percent / 100)
    val border = (0.025 * borderPercent / 100 * wanted).roundToInt()
    return MapLayout(max(1, min(wanted, shortSide - 2 * border)), border)
}

fun mapView(fix: MapFix, northUp: Boolean, size: Int, side: Int): MapView {
    val fast = fix.speed > FAST
    val distance = if (fast) size + 150.0 * (fix.speed - FAST) else size.toDouble()
    val extent = 2 * tan(PI / 12) * distance
    val ratio = if (fast && !northUp) min((fix.speed - FAST) / 32.0, 0.5) else 0.0
    val course = Math.toRadians(max(fix.course, 0f).toDouble())
    val ahead = ratio * extent / 2
    val center = translateMeters(fix.latitude, fix.longitude, sin(course) * ahead, cos(course) * ahead)
    val resolution = EARTH_CIRCUMFERENCE * cos(Math.toRadians(center.latitude)) / TILE_SIZE
    val zoom = ceil(log2(resolution * side / extent) - 0.25).toInt().coerceIn(0, MAX_ZOOM)
    val scale = (side / extent * resolution / 2.0.pow(zoom) * 1000).roundToInt() / 1000f
    return MapView(
        zoom = zoom,
        scale = scale,
        centerX = snap(worldX(center.longitude, zoom), scale),
        centerY = snap(worldY(center.latitude, zoom), scale),
        heading = if (northUp || fix.course < 0f) 0 else fix.course.roundToInt() % 360,
        marker = (ratio * side / 2).roundToInt(),
    )
}

private fun snap(value: Double, scale: Float) = (value * scale).roundToInt() / scale.toDouble()

fun MapView.tiles(side: Int): List<TileKey> {
    val half = side / 2.0 / scale
    val cosine = cos(Math.toRadians(heading.toDouble()))
    val sine = sin(Math.toRadians(heading.toDouble()))
    val reach = half * (abs(cosine) + abs(sine))
    val left = floor((centerX - reach) / TILE_SIZE).toInt()
    val right = floor((centerX + reach) / TILE_SIZE).toInt()
    val top = max(0, floor((centerY - reach) / TILE_SIZE).toInt())
    val bottom = min((1 shl zoom) - 1, floor((centerY + reach) / TILE_SIZE).toInt())
    return (top..bottom).flatMap { y ->
        (left..right).filter { x -> covers(x, y, half, cosine, sine) }.map { x -> TileKey(zoom, x, y) }
    }
}

private fun MapView.covers(x: Int, y: Int, half: Double, cosine: Double, sine: Double): Boolean {
    val dx = x * TILE_SIZE + TILE_SIZE / 2.0 - centerX
    val dy = y * TILE_SIZE + TILE_SIZE / 2.0 - centerY
    val spread = TILE_SIZE / 2.0 * (abs(cosine) + abs(sine))
    return abs(dx * cosine + dy * sine) <= half + spread && abs(-dx * sine + dy * cosine) <= half + spread
}

class FixQueue {
    private val fixes = ArrayList<MapFix>()

    fun add(fix: MapFix) {
        val last = fixes.lastOrNull()
        if (last != null && last.time == fix.time && last.latitude == fix.latitude && last.longitude == fix.longitude) return
        fixes.removeAll { it.time < fix.time - HISTORY }
        fixes += fix
        while (fixes.size > 128) fixes.removeAt(0)
    }

    private fun index(now: Long, delay: Long) = max(fixes.indexOfLast { it.time + delay <= now }, 0)

    fun current(now: Long, delay: Long): MapFix? = fixes.getOrNull(index(now, delay))

    fun nextChange(now: Long, delay: Long): Long? = fixes.getOrNull(index(now, delay) + 1)?.let { it.time + delay }

    private companion object {
        const val HISTORY = 12_000L
    }
}
