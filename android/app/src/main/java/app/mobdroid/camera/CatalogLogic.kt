package app.mobdroid.camera

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class Capability {
    BACKWARD_COMPATIBLE,
    DEPTH_OUTPUT,
    MONOCHROME,
    LOGICAL_MULTI_CAMERA,
    SYSTEM_CAMERA,
    SECURE_IMAGE_DATA,
}

internal data class RawLens(
    val id: String,
    val focalLength: Float,
    val aperture: Float,
    val sensorWidthMm: Float,
    val junk: Boolean,
)

internal data class RawCamera(
    val id: String,
    val visible: Boolean,
    val facing: Facing,
    val capabilities: Set<Capability>,
    val monochromeSensor: Boolean,
    val sensorOrientation: Int,
    val zoomRange: ClosedFloatingPointRange<Float>,
    val lens: RawLens,
    val physicalLenses: List<RawLens>,
    val sizes: List<Dim>,
    val maxFps: Map<Dim, Int>,
    val fpsRanges: List<Fps>,
    val stabilization: List<Stabilization>,
    val hasFlash: Boolean,
    val realtimeTimestamps: Boolean,
    val vendorFps: Map<Dim, Int> = emptyMap(),
)

internal data class CatalogEntry(
    val camera: RawCamera,
    val physicalId: String?,
    val label: String,
    val zoomRange: ClosedFloatingPointRange<Float>,
    val zoomStops: List<Float>,
)

private const val MIN_BACK_AREA = 1920L * 1080
private const val MIN_OTHER_AREA = 1280L * 720
private const val ANCHOR_TOLERANCE = 0.06f
private const val MERGE_RATIO = 1.08f
private const val MAIN_TOLERANCE = 0.08f
private const val WIDEST_SNAP = 0.15f
private const val TELE_SNAP = 0.04f
private const val ULTRA_WIDE_BELOW = 0.75f
private const val TELEPHOTO_ABOVE = 1.25f

internal fun isJunk(
    facing: Facing,
    capabilities: Set<Capability>,
    monochrome: Boolean,
    sizes: List<Dim>,
): Boolean {
    val largest = sizes.maxOfOrNull { it.area } ?: return true
    if (facing == Facing.EXTERNAL) return false
    if (Capability.BACKWARD_COMPATIBLE !in capabilities) return true
    if (Capability.SYSTEM_CAMERA in capabilities || Capability.SECURE_IMAGE_DATA in capabilities) return true
    if (Capability.MONOCHROME in capabilities || monochrome) return true
    val minimum = if (facing == Facing.BACK) MIN_BACK_AREA else MIN_OTHER_AREA
    return largest < minimum
}

internal fun niceRatio(ratio: Float): Float {
    val half = (ratio * 2f).roundToInt() / 2f
    return if (half > 0f && abs(ratio - half) / half <= TELE_SNAP) half else ceil(ratio * 10f - 0.001f) / 10f
}

internal fun niceStop(ratio: Float, range: ClosedFloatingPointRange<Float>): Float {
    val stop = when {
        abs(ratio - 1f) <= MAIN_TOLERANCE -> 1f
        ratio < 1f -> {
            val nearWidest = abs(ratio - range.start) / range.start <= WIDEST_SNAP
            if (nearWidest) range.start else floor(ratio * 10f + 0.001f) / 10f
        }
        else -> niceRatio(ratio)
    }
    return stop.coerceIn(range.start, range.endInclusive)
}

private class LensRatio(val lens: RawLens, val ratio: Float)

private fun fov(lens: RawLens): Float = lens.focalLength / lens.sensorWidthMm

private fun hasFov(lens: RawLens): Boolean = lens.focalLength > 0f && lens.sensorWidthMm > 0f

private fun lensRatios(camera: RawCamera): List<LensRatio> {
    if (Capability.LOGICAL_MULTI_CAMERA !in camera.capabilities) return emptyList()
    val lenses = camera.physicalLenses.filter { !it.junk && hasFov(it) }
    if (lenses.size < 2) return emptyList()
    val ownFov = if (hasFov(camera.lens)) fov(camera.lens) else Float.NaN
    val widest = lenses.minBy { fov(it) }
    val anchor = lenses.minBy { abs(ln(fov(it) / ownFov)) }
        .takeIf { !ownFov.isNaN() && abs(ln(fov(it) / ownFov)) <= ANCHOR_TOLERANCE }
        ?: lenses.minBy { abs(ln(camera.zoomRange.start * fov(it) / fov(widest))) }
    val anchorFov = fov(anchor)
    val merged = ArrayList<LensRatio>()
    for (candidate in lenses.map { LensRatio(it, fov(it) / anchorFov) }.sortedBy { it.ratio }) {
        val last = merged.lastOrNull()
        if (last == null || candidate.ratio / last.ratio >= MERGE_RATIO) {
            merged += candidate
        } else if (candidate.lens === anchor) {
            merged[merged.lastIndex] = candidate
        }
    }
    return if (merged.size >= 2) merged else emptyList()
}

private fun isReachable(ratio: Float, range: ClosedFloatingPointRange<Float>): Boolean =
    ratio >= range.start * 0.9f && ratio <= range.endInclusive * 1.05f

private fun lensKey(facing: Facing, lens: RawLens): String =
    if (facing == Facing.EXTERNAL || lens.focalLength <= 0f) {
        "$facing:${lens.id}"
    } else {
        "$facing:${(lens.focalLength * 100f).roundToInt()}:${(lens.aperture * 100f).roundToInt()}"
    }

private class Candidate(val camera: RawCamera) {
    val ratios = lensRatios(camera)
    val logical = ratios.isNotEmpty()
    val reachable = ratios.filter { isReachable(it.ratio, camera.zoomRange) }
    val keys = (reachable.map { it.lens } + camera.lens).map { lensKey(camera.facing, it) }
    private val widerLens = ratios.any { it.ratio < camera.zoomRange.start * 0.9f }
    val stops = reachable.map { niceStop(it.ratio, camera.zoomRange) }
        .plus(listOfNotNull(camera.zoomRange.start.takeIf { widerLens }))
        .distinct().sorted()
        .takeIf { it.size >= 2 }.orEmpty()
    val rank = when {
        camera.visible && logical -> 0
        camera.visible -> 1
        !logical -> 2
        else -> 3
    }

    fun extends(other: Candidate): Boolean =
        other.rank == 0 && camera.facing == other.camera.facing && stops.size > other.stops.size &&
            keys.containsAll(other.keys)
}

private class Draft(
    val camera: RawCamera,
    val physicalId: String?,
    val lens: RawLens,
    val zoomRange: ClosedFloatingPointRange<Float>,
    val zoomStops: List<Float>,
)

internal fun buildCatalog(cameras: List<RawCamera>): List<CatalogEntry> {
    val physicalIds = cameras.filter { Capability.LOGICAL_MULTI_CAMERA in it.capabilities }
        .flatMapTo(HashSet()) { camera -> camera.physicalLenses.map { it.id } }
    val candidates = cameras
        .filter { it.visible || it.id !in physicalIds }
        .filterNot { isJunk(it.facing, it.capabilities, it.monochromeSensor, it.sizes) }
        .map { Candidate(it) }
        .sortedWith(
            compareBy<Candidate>(
                { it.rank },
                { -it.camera.lens.sensorWidthMm },
                { it.camera.id.toIntOrNull() ?: Int.MAX_VALUE },
                { it.camera.id },
            ),
        )
    val covered = HashSet<String>()
    val drafts = ArrayList<Draft>()
    val accepted = ArrayList<Candidate>()
    for (listed in candidates) {
        if (listed.keys.all { it in covered }) continue
        val candidate = candidates.filter { it.extends(listed) }.maxByOrNull { it.stops.size } ?: listed
        covered += candidate.keys
        accepted += candidate
        drafts += Draft(candidate.camera, null, candidate.camera.lens, candidate.camera.zoomRange, candidate.stops)
    }
    for (candidate in accepted) {
        for (unreachable in candidate.ratios - candidate.reachable.toSet()) {
            if (!covered.add(lensKey(candidate.camera.facing, unreachable.lens))) continue
            val standalone = cameras.firstOrNull { it.id == unreachable.lens.id }
            if (standalone != null) {
                drafts += Draft(standalone, null, standalone.lens, standalone.zoomRange, emptyList())
                continue
            }
            val range = 1f..max(1f, candidate.camera.zoomRange.endInclusive)
            drafts += Draft(candidate.camera, unreachable.lens.id, unreachable.lens, range, emptyList())
        }
    }
    return label(drafts.sortedBy { it.camera.facing.ordinal })
}

private fun label(drafts: List<Draft>): List<CatalogEntry> {
    val reference = HashMap<Facing, Float>()
    for (draft in drafts) {
        if (hasFov(draft.lens)) reference.putIfAbsent(draft.camera.facing, fov(draft.lens))
    }
    val seen = HashMap<String, Int>()
    val total = drafts.groupingBy { name(it, reference) }.eachCount()
    return drafts.map { draft ->
        val base = name(draft, reference)
        val index = (seen[base] ?: 0) + 1
        seen[base] = index
        val label = if (total.getValue(base) > 1 && index > 1) "$base $index" else base
        CatalogEntry(draft.camera, draft.physicalId, label, draft.zoomRange, draft.zoomStops)
    }
}

private fun name(draft: Draft, reference: Map<Facing, Float>): String {
    val facing = draft.camera.facing
    if (facing == Facing.EXTERNAL) return "USB camera"
    if (draft.zoomStops.isNotEmpty()) return if (facing == Facing.FRONT) "Front" else "Back"
    val base = reference[facing]
    val ratio = if (base != null && hasFov(draft.lens)) fov(draft.lens) / base else 1f
    val lens = when {
        ratio < ULTRA_WIDE_BELOW -> "Ultra wide"
        ratio <= TELEPHOTO_ABOVE -> "Wide"
        else -> "Telephoto ${formatRatio(niceRatio(ratio))}x"
    }
    return when {
        facing != Facing.FRONT -> lens
        lens == "Wide" -> "Front"
        else -> "Front ${lens.lowercase()}"
    }
}

private fun formatRatio(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
