package app.mobdroid.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class Dim(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
}

internal data class Fps(val lower: Int, val upper: Int)

internal data class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

private const val ASPECT_TOLERANCE = 0.015f

internal fun cropRegion(active: IRect, zoom: Float): IRect {
    val z = if (zoom.isNaN()) 1f else max(zoom, 1f)
    val w = (active.width / z).roundToInt().coerceIn(1, active.width)
    val h = (active.height / z).roundToInt().coerceIn(1, active.height)
    val left = active.left + (active.width - w) / 2
    val top = active.top + (active.height - h) / 2
    return IRect(left, top, left + w, top + h)
}

internal fun meteringRect(active: IRect, zoom: Float, x: Float, y: Float, fraction: Float): IRect {
    val view = cropRegion(active, zoom)
    val w = (view.width * fraction).roundToInt().coerceIn(1, active.width)
    val h = (view.height * fraction).roundToInt().coerceIn(1, active.height)
    val cx = view.left + (x.coerceIn(0f, 1f) * view.width).roundToInt()
    val cy = view.top + (y.coerceIn(0f, 1f) * view.height).roundToInt()
    val left = (cx - w / 2).coerceIn(active.left, active.right - w)
    val top = (cy - h / 2).coerceIn(active.top, active.bottom - h)
    return IRect(left, top, left + w, top + h)
}

internal fun exposureIndex(ev: Float, step: Float, lower: Int, upper: Int): Int =
    if (ev.isNaN() || step <= 0f) 0 else (ev / step).roundToInt().coerceIn(lower, upper)

internal fun chooseFpsRange(ranges: List<Fps>, fps: Int, sizeMaxFps: Int): Fps? {
    val target = min(fps, sizeMaxFps)
    return ranges.minWithOrNull(
        compareBy<Fps>({ abs(it.upper - target) }, { it.upper - it.lower }, { if (it.upper >= target) 0 else 1 }),
    )
}

internal fun supportsFps(ranges: List<Fps>, maxFps: Map<Dim, Int>, fps: Int): Boolean =
    ranges.any { it.upper >= fps } && maxFps.values.any { it >= fps }

internal fun samsungVideoFps(table: IntArray): Map<Dim, Int> {
    if (table.size % 6 != 0) return emptyMap()
    val result = HashMap<Dim, Int>()
    for (i in table.indices step 6) {
        val lower = table[i + 2]
        val upper = table[i + 3]
        if (table[i] <= 0 || table[i + 1] <= 0 || lower !in 1..upper || upper > 240) return emptyMap()
        result.merge(Dim(table[i], table[i + 1]), upper, ::max)
    }
    return result
}

internal fun vendorFps(ranges: List<Fps>, fps: Int, vendorMaxFps: Int): Int {
    val target = min(fps, vendorMaxFps)
    return if (ranges.none { it.upper >= target }) target else 0
}

internal fun retryDelayMs(failures: Int): Long = min(250L shl (failures - 1).coerceIn(0, 5), 5000L)

internal fun effectiveStabilization(requested: Stabilization, available: List<Stabilization>): Stabilization =
    when {
        requested == Stabilization.PREVIEW && Stabilization.PREVIEW in available -> Stabilization.PREVIEW
        requested != Stabilization.OFF && Stabilization.VIDEO in available -> Stabilization.VIDEO
        else -> Stabilization.OFF
    }

internal fun <T> pickSize(
    items: List<T>,
    width: (T) -> Int,
    height: (T) -> Int,
    maxFps: (T) -> Int,
    requestedWidth: Int,
    requestedHeight: Int,
    fps: Int,
): T? {
    if (items.isEmpty()) return null
    val rw = max(max(requestedWidth, requestedHeight), 1)
    val rh = max(min(requestedWidth, requestedHeight), 1)
    val aspect = rw.toFloat() / rh
    val capable = items.filter { maxFps(it) >= fps }
    val pool = if (fps > 30 && capable.isNotEmpty()) capable else items
    fun long(t: T) = max(width(t), height(t))
    fun short(t: T) = min(width(t), height(t))
    fun area(t: T) = long(t).toLong() * short(t)
    fun aspectGap(t: T) = abs(long(t).toFloat() / short(t) - aspect) / aspect
    fun covers(t: T) = long(t) >= rw && short(t) >= rh
    val matching = pool.filter { aspectGap(it) <= ASPECT_TOLERANCE }
    return matching.filter { covers(it) }.minByOrNull { area(it) }
        ?: matching.maxByOrNull { area(it) }
        ?: pool.filter { covers(it) }.minWithOrNull(compareBy<T>({ aspectGap(it) }, { area(it) }))
        ?: pool.maxByOrNull { area(it) }
}
