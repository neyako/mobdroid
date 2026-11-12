package com.opirl.app.widgets.vtuber

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

class VrmBind(val node: String, val index: Int, val weight: Float)

class Vrm(val facesForward: Boolean, val bones: Map<String, String>, val mouth: List<VrmBind>, val blink: List<VrmBind>)

val VRM_BONES = listOf("neck", "spine", "leftUpperArm", "rightUpperArm")

fun glbJson(file: ByteBuffer): JsonObject? {
    val bytes = file.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.remaining() < 20 || bytes.getInt(0) != 0x46546C67 || bytes.getInt(16) != 0x4E4F534A) return null
    val length = bytes.getInt(12)
    if (length <= 0 || length > bytes.remaining() - 20) return null
    val text = ByteArray(length)
    bytes.position(20)
    bytes.get(text)
    return runCatching { Json.parseToJsonElement(String(text, Charsets.UTF_8)) as? JsonObject }.getOrNull()
}

fun plainSlashes(file: ByteBuffer) {
    val bytes = file.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.remaining() < 20 || bytes.getInt(0) != 0x46546C67 || bytes.getInt(16) != 0x4E4F534A) return
    val end = 20 + bytes.getInt(12)
    if (end > bytes.limit()) return
    var write = 20
    var read = 20
    while (read < end) {
        val byte = bytes.get(read)
        if (byte == '\\'.code.toByte() && read + 1 < end) {
            val next = bytes.get(read + 1)
            if (next != '/'.code.toByte()) bytes.put(write++, byte)
            bytes.put(write++, next)
            read += 2
        } else {
            bytes.put(write++, byte)
            read++
        }
    }
    while (write < end) bytes.put(write++, ' '.code.toByte())
}

private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

private fun JsonElement?.list(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()

private fun JsonElement?.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

private fun JsonElement?.float(key: String): Float? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.floatOrNull

private fun JsonElement?.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun parseVrm(gltf: JsonObject): Vrm? {
    val nodes = gltf.list("nodes")
    fun name(node: Int?): String? = nodes.getOrNull(node ?: -1).text("name")?.takeIf { it.isNotEmpty() }
    val extensions = gltf.obj("extensions")
    val modern = extensions.obj("VRMC_vrm")
    if (modern != null) {
        val humanBones = modern.obj("humanoid").obj("humanBones")
        val bones = VRM_BONES.mapNotNull { bone -> name(humanBones.obj(bone).int("node"))?.let { bone to it } }.toMap()
        val preset = modern.obj("expressions").obj("preset")
        fun binds(expression: String) = preset.obj(expression).list("morphTargetBinds").mapNotNull { bind ->
            val node = name(bind.int("node")) ?: return@mapNotNull null
            VrmBind(node, bind.int("index") ?: return@mapNotNull null, bind.float("weight") ?: 1f)
        }
        return Vrm(true, bones, binds("aa"), binds("blink"))
    }
    val legacy = extensions.obj("VRM") ?: return null
    val bones = legacy.obj("humanoid").list("humanBones").mapNotNull { bone ->
        val kind = bone.text("bone")?.takeIf { it in VRM_BONES } ?: return@mapNotNull null
        name(bone.int("node"))?.let { kind to it }
    }.toMap()
    val groups = legacy.obj("blendShapeMaster").list("blendShapeGroups")
    fun binds(preset: String) = groups.firstOrNull { it.text("presetName")?.lowercase() == preset }.list("binds").flatMap { bind ->
        val mesh = bind.int("mesh")
        val index = bind.int("index")
        val weight = (bind.float("weight") ?: 100f) / 100f
        if (mesh == null || index == null) {
            emptyList()
        } else {
            nodes.filter { it.int("mesh") == mesh }.mapNotNull { node -> node.text("name")?.takeIf(String::isNotEmpty)?.let { VrmBind(it, index, weight) } }
        }
    }
    return Vrm(false, bones, binds("a"), binds("blink"))
}

fun armSway(seconds: Double): Double {
    val turn = PI * 2
    var angle = seconds - turn * Math.rint(seconds / turn)
    angle = abs(angle) - PI / 2
    return angle * 0.5 * 0.1
}
