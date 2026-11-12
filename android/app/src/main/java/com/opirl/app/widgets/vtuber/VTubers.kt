package com.opirl.app.widgets.vtuber

import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.opirl.app.media.gl.VTuberSource
import com.opirl.app.media.gl.VideoCompositor
import com.opirl.app.widgets.Face
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow

private class Bone(val entity: Int, val rest: FloatArray, val parent: FloatArray)

private class Morph(val entity: Int, val weights: FloatArray)

private fun readModel(file: File): ByteBuffer = RandomAccessFile(file, "r").use { input ->
    val bytes = ByteBuffer.allocateDirect(input.length().toInt()).order(ByteOrder.LITTLE_ENDIAN)
    while (bytes.hasRemaining() && input.channel.read(bytes) > 0) Unit
    bytes.flip()
    plainSlashes(bytes)
    bytes
}

private class VTuberRenderer(private val model: ByteBuffer, vrm: Vrm, surface: Surface, side: Int) {
    private val engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera = engine.createCamera(cameraEntity)
    private val light = EntityManager.get().create()
    private val indirect = IndirectLight.Builder().irradiance(1, floatArrayOf(1f, 1f, 1f)).intensity(30_000f).build(engine)
    private val grading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
    private val swapChain = engine.createSwapChain(surface, SwapChainFlags.CONFIG_TRANSPARENT)
    private val materials = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, materials, EntityManager.get())
    private val resources = ResourceLoader(engine, true)
    private val asset = loader.createAsset(model)
    private val bones = HashMap<String, Bone>()
    private val mouth = ArrayList<Pair<Morph, VrmBind>>()
    private val blink = ArrayList<Pair<Morph, VrmBind>>()
    private val morphs = HashMap<Int, Morph>()
    private val turn = FloatArray(16)
    private val scratch = FloatArray(16)
    private val local = FloatArray(16)
    private val animator: Animator
    private var yaw = 0f
    private var roll = 0f
    private var mouthOpen = 0f
    private var eyesClosed = 0f

    init {
        if (asset == null) {
            destroy()
            error("Not a glTF file")
        }
        resources.loadResources(asset)
        asset.releaseSourceData()
        animator = asset.instance.animator
        scene.addEntities(asset.entities)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f)
            .intensity(100_000f)
            .direction(0f, -1f, 2f)
            .castShadows(false)
            .build(engine, light)
        scene.addEntity(light)
        scene.indirectLight = indirect
        view.scene = scene
        view.camera = camera
        view.viewport = Viewport(0, 0, side, side)
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.colorGrading = grading
        view.isShadowingEnabled = false
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true }
        renderer.clearOptions = Renderer.ClearOptions().apply { clear = true }
        val transforms = engine.transformManager
        if (vrm.facesForward) {
            Matrix.setRotateM(turn, 0, 180f, 0f, 1f, 0f)
            transforms.setTransform(transforms.getInstance(asset.root), turn)
        }
        for ((bone, node) in vrm.bones) {
            val entity = asset.getFirstEntityByName(node)
            val instance = transforms.getInstance(entity)
            if (entity == 0 || instance == 0) continue
            val parent = FloatArray(16)
            transforms.getWorldTransform(transforms.getInstance(transforms.getParent(instance)), parent)
            bones[bone] = Bone(entity, transforms.getTransform(instance, FloatArray(16)), rotationOf(parent))
        }
        fun bind(binds: List<VrmBind>, into: MutableList<Pair<Morph, VrmBind>>) {
            for (bind in binds) {
                val entity = asset.getFirstEntityByName(bind.node)
                val instance = engine.renderableManager.getInstance(entity)
                val count = if (instance == 0) 0 else engine.renderableManager.getMorphTargetCount(instance)
                if (bind.index >= count) continue
                into += morphs.getOrPut(entity) { Morph(entity, FloatArray(count)) } to bind
            }
        }
        bind(vrm.mouth, mouth)
        bind(vrm.blink, blink)
    }

    private fun rotationOf(world: FloatArray): FloatArray {
        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        for (column in 0..2) {
            val x = world[column * 4]
            val y = world[column * 4 + 1]
            val z = world[column * 4 + 2]
            val length = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat().coerceAtLeast(1e-6f)
            rotation[column * 4] = x / length
            rotation[column * 4 + 1] = y / length
            rotation[column * 4 + 2] = z / length
        }
        return rotation
    }

    private fun setBone(name: String, yAngle: Double, zAngle: Double) {
        val bone = bones[name] ?: return
        Matrix.transposeM(scratch, 0, bone.parent, 0)
        Matrix.rotateM(scratch, 0, Math.toDegrees(yAngle).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(scratch, 0, Math.toDegrees(zAngle).toFloat(), 0f, 0f, 1f)
        Matrix.multiplyMM(turn, 0, scratch, 0, bone.parent, 0)
        System.arraycopy(bone.rest, 0, scratch, 0, 16)
        scratch[12] = 0f
        scratch[13] = 0f
        scratch[14] = 0f
        Matrix.multiplyMM(local, 0, turn, 0, scratch, 0)
        local[12] = bone.rest[12]
        local[13] = bone.rest[13]
        local[14] = bone.rest[14]
        val transforms = engine.transformManager
        transforms.setTransform(transforms.getInstance(bone.entity), local)
    }

    fun frame(face: Face?, source: VTuberSource, seconds: Double, nanos: Long) {
        camera.setProjection(source.fieldOfView.toDouble(), 1.0, 0.1, 20.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, source.positionY.toDouble(), -1.8, 0.0, source.positionY.toDouble(), 0.0, 0.0, 1.0, 0.0)
        val side = if (source.mirror) -1f else 1f
        val ease = 0.35f
        yaw += ((face?.yaw ?: 0f) * side - yaw) * ease
        roll += ((face?.roll ?: 0f) * side - roll) * ease
        mouthOpen += (((face?.mouthOpen ?: 0f) * source.mouth).coerceIn(0f, 1f) - mouthOpen) * ease
        eyesClosed += (((1f - (face?.leftEyeOpen ?: 1f)) * source.eyes).coerceIn(0f, 1f) - eyesClosed) * 0.6f
        for (morph in morphs.values) morph.weights.fill(0f)
        for ((morph, bind) in mouth) morph.weights[bind.index] += bind.weight * mouthOpen
        for ((morph, bind) in blink) morph.weights[bind.index] += bind.weight * eyesClosed
        val renderables = engine.renderableManager
        for (morph in morphs.values) renderables.setMorphWeights(renderables.getInstance(morph.entity), morph.weights, 0)
        val neckY = Math.toRadians(yaw.toDouble()) * 0.8
        val neckZ = Math.toRadians(roll.toDouble()) * 0.8
        setBone("neck", -neckY, -neckZ)
        setBone("spine", -neckY / 3, -neckZ / 3)
        val arm = armSway(seconds) + Math.toRadians(source.armsAngle.toDouble())
        setBone("leftUpperArm", 0.0, arm)
        setBone("rightUpperArm", 0.0, -arm)
        animator.updateBoneMatrices()
        if (renderer.beginFrame(swapChain, nanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun destroy() {
        engine.destroySwapChain(swapChain)
        engine.flushAndWait()
        if (asset != null) loader.destroyAsset(asset)
        resources.destroy()
        loader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyIndirectLight(indirect)
        engine.destroyColorGrading(grading)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroyEntity(light)
        EntityManager.get().destroy(cameraEntity)
        EntityManager.get().destroy(light)
        engine.destroy()
    }
}

class VTubers(private val files: File, private val faces: StateFlow<Map<String?, Face>>) {
    private class Running(var source: VTuberSource, val compositor: VideoCompositor, val renderer: VTuberRenderer)

    private val thread = HandlerThread("VTuber")
    private val handler by lazy {
        thread.start()
        Handler(thread.looper)
    }
    private val running = HashMap<String, Running>()
    private val failed = HashMap<String, String>()
    @Volatile
    private var loaded = false
    private var ticking = false
    private val tick = Runnable { drawAll() }

    fun sync(models: Map<String, VTuberSource>, compositor: VideoCompositor?) {
        if (models.isEmpty() && !loaded) return
        val done = CountDownLatch(1)
        handler.post {
            apply(models, compositor)
            done.countDown()
        }
        if (compositor == null) done.await(2, TimeUnit.SECONDS)
    }

    private fun apply(models: Map<String, VTuberSource>, compositor: VideoCompositor?) {
        running.entries
            .filter { (id, model) ->
                val wanted = models[id]
                model.compositor !== compositor || wanted == null || wanted.file != model.source.file || wanted.side != model.source.side
            }
            .map { it.key }
            .forEach(::close)
        failed.keys.retainAll { id -> models[id]?.file == failed[id] }
        if (compositor == null) return
        for ((id, source) in models) {
            val model = running[id]
            if (model != null) {
                model.source = source
            } else if (id !in failed) {
                open(id, source, compositor)
            }
        }
        if (running.isNotEmpty() && !ticking) {
            ticking = true
            handler.post(tick)
        }
    }

    private fun open(id: String, source: VTuberSource, compositor: VideoCompositor) {
        if (!loaded) {
            Filament.init()
            Gltfio.init()
            loaded = true
        }
        val surface = compositor.openSurfaceOverlay(id, source.side, source.side) ?: return
        try {
            val model = readModel(File(files, source.file))
            val vrm = checkNotNull(glbJson(model)?.let(::parseVrm)) { "Not a VRM file" }
            running[id] = Running(source, compositor, VTuberRenderer(model, vrm, surface, source.side))
        } catch (e: Exception) {
            Log.e(TAG, "VTuber model failed", e)
            failed[id] = source.file
            compositor.closeSurfaceOverlay(id)
        }
    }

    private fun close(id: String) {
        val model = running.remove(id) ?: return
        model.renderer.destroy()
        model.compositor.closeSurfaceOverlay(id)
    }

    private fun drawAll() {
        if (running.isEmpty()) {
            ticking = false
            return
        }
        val nanos = System.nanoTime()
        val seen = faces.value
        for (model in running.values) {
            runCatching { model.renderer.frame(seen[model.source.camera], model.source, nanos / 1e9, nanos) }
                .onFailure { Log.w(TAG, "VTuber frame failed", it) }
        }
        handler.postDelayed(tick, 33)
    }

    private companion object {
        const val TAG = "VTubers"
    }
}
