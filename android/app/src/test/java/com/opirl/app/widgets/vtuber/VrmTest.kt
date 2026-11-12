package com.opirl.app.widgets.vtuber

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class VrmTest {
    private fun gltf(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val nodes = """"nodes": [{"name": "Root"}, {"name": "Neck"}, {"name": "Face", "mesh": 0}, {"name": "Body", "mesh": 1}, {"mesh": 0}]"""

    private fun binds(binds: List<VrmBind>) = binds.map { Triple(it.node, it.index, it.weight) }

    @Test
    fun aLegacyModelBindsByMeshAndWeighsInPercent() {
        val vrm = parseVrm(
            gltf(
                """{$nodes, "extensions": {"VRM": {
                    "humanoid": {"humanBones": [{"bone": "neck", "node": 1}, {"bone": "hips", "node": 0}, {"bone": "spine", "node": 9}]},
                    "blendShapeMaster": {"blendShapeGroups": [
                        {"presetName": "A", "binds": [{"mesh": 0, "index": 3, "weight": 100}]},
                        {"presetName": "blink", "binds": [{"mesh": 0, "index": 7, "weight": 50}, {"mesh": 1, "index": 2, "weight": 100}]}
                    ]}}}}""",
            ),
        )!!
        assertEquals(false, vrm.facesForward)
        assertEquals(mapOf("neck" to "Neck"), vrm.bones)
        assertEquals(listOf(Triple("Face", 3, 1f)), binds(vrm.mouth))
        assertEquals(listOf(Triple("Face", 7, 0.5f), Triple("Body", 2, 1f)), binds(vrm.blink))
    }

    @Test
    fun aModernModelBindsByNode() {
        val vrm = parseVrm(
            gltf(
                """{$nodes, "extensions": {"VRMC_vrm": {
                    "humanoid": {"humanBones": {"neck": {"node": 1}, "leftUpperArm": {"node": 3}}},
                    "expressions": {"preset": {
                        "aa": {"morphTargetBinds": [{"node": 2, "index": 4, "weight": 0.8}]},
                        "blink": {"morphTargetBinds": [{"node": 4, "index": 1, "weight": 1}]}
                    }}}}}""",
            ),
        )!!
        assertEquals(true, vrm.facesForward)
        assertEquals(mapOf("neck" to "Neck", "leftUpperArm" to "Body"), vrm.bones)
        assertEquals(listOf(Triple("Face", 4, 0.8f)), binds(vrm.mouth))
        assertEquals(emptyList(), binds(vrm.blink))
        assertNull(parseVrm(gltf("""{$nodes}""")))
    }

    @Test
    fun theJsonChunkIsReadFromABinaryFile() {
        val json = """{"asset":{"version":"2.0"}}""".toByteArray()
        val file = ByteBuffer.allocate(20 + json.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        file.putInt(0x46546C67).putInt(2).putInt(file.capacity()).putInt(json.size).putInt(0x4E4F534A).put(json).flip()
        file.limit(file.capacity())
        assertEquals(setOf("asset"), glbJson(file)!!.keys)
        file.putInt(0, 0)
        assertNull(glbJson(file))
    }

    @Test
    fun escapedSlashesAreMadePlainWithoutMovingTheData() {
        val json = """{"mimeType":"image\/png","name":"a\\b\"c"}""".toByteArray()
        val file = ByteBuffer.allocate(20 + json.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        file.putInt(0x46546C67).putInt(2).putInt(file.capacity()).putInt(json.size).putInt(0x4E4F534A).put(json).putInt(7).flip()
        plainSlashes(file)
        val text = ByteArray(json.size)
        val copy = file.duplicate()
        copy.position(20)
        copy.get(text)
        assertEquals("""{"mimeType":"image/png","name":"a\\b\"c"} """, String(text))
        assertEquals(7, file.getInt(20 + json.size))
    }

    @Test
    fun theArmsSwayAFewDegreesAroundTheirAngle() {
        assertEquals(-PI / 40, armSway(0.0), 1e-9)
        assertEquals(PI / 40, armSway(PI), 1e-9)
        assertEquals(armSway(1.0), armSway(1.0 + PI * 2), 1e-9)
    }
}
