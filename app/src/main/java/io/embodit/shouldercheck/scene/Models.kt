package io.embodit.shouldercheck.scene

import android.content.Context
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelFormat
import com.pico.spatial.core.ecs.OpacityControllerComponent
import io.embodit.shouldercheck.domain.GlbAlpha
import io.embodit.shouldercheck.domain.MiniJson
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import io.embodit.shouldercheck.domain.MiniJson.num
import io.embodit.shouldercheck.domain.ModelCalibration
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog
import java.util.Locale

/**
 * The five road-user GLBs (design gateBDecisions.models, D25): loaded once as disabled templates, cloned per instance.
 * Every instance hangs below its own calibration node with the role's ONE calibration (calibration.json, uniform
 * `scaleBalanced`: every axis within ~7 % of the design size), so the model's bottom centre sits at the node origin,
 * its front points to −Z, +Y up. Motion code only moves the calibration node's parent.
 *
 * All models are forced OPAQUE at load: [GlbAlpha.forceOpaque] reads every glTF material's alphaMode from the GLB's
 * JSON chunk and rewrites BLEND / MASK to OPAQUE before the bytes reach the SDK loader; after loading, every material's
 * BlendingMode is checked and set to OPAQUE if needed, and any opacity component is removed (a component at 1.0 still
 * renders blended: factory finding). No instance or ancestor ever gets an opacity component. Logged per model
 * (`MODEL alpha …`).
 */
internal class Models(private val context: Context) {
    val calibrations: Map<String, ModelCalibration>
    private val templates = HashMap<String, Entity>()
    private val failed = HashSet<String>()

    init {
        val text = context.assets.open("models/calibration.json").bufferedReader().use { it.readText() }
        val raw = ModelCalibration.parseAll(text)
        val root = MiniJson.parse(text) as? Map<*, *>
        calibrations =
            raw.mapValues { (role, c) ->
                val balanced = (root?.get(role) as? Map<*, *>)?.num("scaleBalanced")
                if (balanced != null) c.withScale(balanced) else c
            }
        for ((role, c) in calibrations) {
            val size = c.size()
            ScLog.i(String.format(Locale.US, "MODEL calib %s yaw=%.0f scale=%.4f size L=%.2f W=%.2f H=%.2f (design L=%s W=%s H=%s) bottom=%s", role, c.yawDeg, c.scale, size.z, size.x, size.y, c.designLength, c.designWidth, c.designHeight, ScLog.pos(c.bottomCentre())))
        }
    }

    fun loaded(role: String): Boolean = templates.containsKey(role)

    fun isFailed(role: String): Boolean = role in failed

    /** Loads [role]'s GLB (suspends; main thread). */
    suspend fun load(role: String): Boolean {
        if (templates.containsKey(role)) return true
        val t0 = android.os.SystemClock.elapsedRealtime()
        return try {
            val raw = context.assets.open("models/$role.glb").use { it.readBytes() }
            val (bytes, alpha) = GlbAlpha.forceOpaque(raw)
            ScLog.i("MODEL alpha $role glbAlphaModes=${alpha.modes} forcedInGlb=${alpha.forced}")
            val e = Entity.loadSuspend(java.io.ByteArrayInputStream(bytes), ModelFormat.GLTF)
            e.enabled = false
            val report = opaqueReport(e)
            templates[role] = e
            ScLog.i("MODEL loaded $role ms=${android.os.SystemClock.elapsedRealtime() - t0} $report")
            true
        } catch (t: Throwable) {
            failed += role
            ScLog.e("MODEL load $role failed: $t", t)
            false
        }
    }

    /** Walks the model: removes any opacity component, counts meshes / materials (logged as the instance audit). */
    private fun opaqueReport(root: Entity): String {
        var meshes = 0
        var materials = 0
        var opacityRemoved = 0
        var forcedRuntime = 0
        val names = HashSet<String>()
        fun walk(e: Entity) {
            if (e.components[OpacityControllerComponent::class.java] != null) {
                e.components.remove(OpacityControllerComponent::class.java)
                opacityRemoved++
            }
            e.components[ModelComponent::class.java]?.let { mc ->
                meshes++
                runCatching {
                    mc.materials.forEach { m ->
                        materials++
                        val mode =
                            when (m) {
                                is PhysicallyBasedMaterial -> m.getBlendingMode().also { if (it != BlendingMode.OPAQUE) { m.setBlendingMode(BlendingMode.OPAQUE); forcedRuntime++ } }
                                is UnlitMaterial -> m.getBlendingMode().also { if (it != BlendingMode.OPAQUE) { m.setBlendingMode(BlendingMode.OPAQUE); forcedRuntime++ } }
                                else -> null
                            }
                        names += "${m.javaClass.simpleName}:$mode"
                    }
                }
            }
            runCatching { e.getChildren() }.getOrNull()?.forEach { walk(it) }
        }
        walk(root)
        return "meshes=$meshes materials=$materials blending=$names forcedOpaqueAtRuntime=$forcedRuntime opacityComponentsRemoved=$opacityRemoved"
    }

    /**
     * A new instance: parent (moved by the scene) → calibration node (the role's TRS) → model clone.
     * Null while the template is not loaded.
     */
    fun instance(role: String): Entity? {
        val tpl = templates[role] ?: return null
        val c = calibrations[role] ?: return null
        val clone = runCatching { tpl.clone(Entity.CloneOptions(true, true)) }.getOrNull() ?: return null
        clone.enabled = true
        val calib = Entity().named("calib:$role")
        calib.setLocal(position = c.translation, rotation = c.rotation, scale = Vec3(c.scale, c.scale, c.scale))
        calib.addChild(clone)
        val parent = Entity().named("actor:$role")
        parent.addChild(calib)
        return parent
    }

    fun close() {
        for (t in templates.values) runCatching { t.destroy(true) }
        templates.clear()
    }
}
