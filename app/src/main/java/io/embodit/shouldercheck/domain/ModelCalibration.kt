package io.embodit.shouldercheck.domain

import io.embodit.shouldercheck.domain.MiniJson.num
import io.embodit.shouldercheck.domain.MiniJson.obj
import io.embodit.shouldercheck.domain.MiniJson.str
import io.embodit.shouldercheck.domain.MiniJson.vec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * D25 model calibration: one calibration node per model (all instances share it), TRS
 * `p' = translation + Ry(yawDeg)(scale · p)` so that the model's bottom centre sits at the origin, its front
 * (car nose / handlebars) points to −Z, +Y is up and its length equals the design length. Motion code only moves the
 * calibration node's PARENT.
 *
 * `Ry(+yaw)` is the right-handed rotation about +Y (counter-clockwise seen from above; +X → −Z at +90°) — the SDK's
 * own quaternion convention, i.e. `Quat.axisAngle(Vec3.UP, toRadians(yawDeg))`. (Not the app's +right yaw.)
 *
 * `assets/models/calibration.json`, one object per role (`sedan`, `suv`, `ebike`, `bike`, `moto`; see
 * [ActorKind.model]); fields read here:
 * - `bboxMin`, `bboxMax`: [x, y, z] in model units (GLB scene space, node transforms included) — required;
 * - `forwardAxis`: "+X" | "-X" | "+Z" | "-Z" — the model's front direction; or `forwardYawDeg` (app convention,
 *   0 = −Z, + = toward +X) — one of them required;
 * - `designM`: {L, W, H} design size in metres (L required unless `scale` is given);
 * - `yawDeg`, `scale`, `translation` [x, y, z]: precomputed TRS (tools/process_models.py); when present they are
 *   used as is, else computed with [compute] from the fields above;
 * - other fields (`modelSize`, `sizeAfterM`, `sizeError`, `scaleBalanced`, `file`, …) are informational.
 */
data class ModelCalibration(
    val role: String,
    val yawDeg: Double,
    val scale: Double,
    val translation: Vec3,
    val bboxMin: Vec3,
    val bboxMax: Vec3,
    val designLength: Double?,
    val designWidth: Double?,
    val designHeight: Double?,
) {
    val rotation: Quat
        get() = Quat.axisAngle(Vec3.UP, Math.toRadians(yawDeg))

    /** Model-space point → calibrated (metres, bottom centre at the origin, front to −Z). */
    fun apply(p: Vec3): Vec3 = translation + rotation.rotate(p * scale)

    /**
     * The same calibration with another uniform scale (calibration.json `scaleBalanced`, every axis within ~7 % of the
     * design size): the translation is recomputed so the bottom centre stays at the origin.
     */
    fun withScale(s: Double): ModelCalibration {
        val bottom = Vec3((bboxMin.x + bboxMax.x) / 2, bboxMin.y, (bboxMin.z + bboxMax.z) / 2)
        return copy(scale = s, translation = -rotation.rotate(bottom * s))
    }

    /** Calibrated bounding box (min, max) of the model's bbox corners. */
    fun calibratedBounds(): Pair<Vec3, Vec3> {
        val pts = corners(bboxMin, bboxMax).map { apply(it) }
        return Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) to Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
    }

    /** Where the model's bottom centre lands (should be the origin, ±1 cm). */
    fun bottomCentre(): Vec3 = apply(Vec3((bboxMin.x + bboxMax.x) / 2, bboxMin.y, (bboxMin.z + bboxMax.z) / 2))

    /** Calibrated size: length along z, width along x, height. */
    fun size(): Vec3 {
        val (lo, hi) = calibratedBounds()
        return Vec3(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z)
    }

    /** Angle (deg) between the calibrated model front and −Z, for a model front [forwardInModel]. */
    fun forwardError(forwardInModel: Vec3): Double = Angles.between(rotation.rotate(forwardInModel), Vec3.AHEAD)

    companion object {
        fun axisVector(axis: String): Vec3? =
            when (axis.trim().uppercase()) {
                "+X", "X" -> Vec3(1.0, 0.0, 0.0)
                "-X" -> Vec3(-1.0, 0.0, 0.0)
                "+Z", "Z" -> Vec3(0.0, 0.0, 1.0)
                "-Z" -> Vec3(0.0, 0.0, -1.0)
                else -> null
            }

        /** SDK-convention yaw that turns horizontal [forward] onto −Z. */
        fun yawToAhead(forward: Vec3): Double = Math.toDegrees(kotlin.math.atan2(forward.x, -forward.z))

        /**
         * Computes the calibration from a model bbox, its front direction and the design length: rotate the front to
         * −Z, scale the length (extent along the front) to [designLength], put the bottom centre at the origin.
         */
        fun compute(role: String, bboxMin: Vec3, bboxMax: Vec3, forward: Vec3, designLength: Double, designWidth: Double? = null, designHeight: Double? = null): ModelCalibration {
            val f = Vec3(forward.x, 0.0, forward.z).normalized()
            val yaw = yawToAhead(f)
            val r = Quat.axisAngle(Vec3.UP, Math.toRadians(yaw))
            val zs = corners(bboxMin, bboxMax).map { r.rotate(it).z }
            val modelLength = zs.max() - zs.min()
            require(modelLength > 1e-9) { "empty bbox" }
            val s = designLength / modelLength
            val bottom = Vec3((bboxMin.x + bboxMax.x) / 2, bboxMin.y, (bboxMin.z + bboxMax.z) / 2)
            val t = -r.rotate(bottom * s)
            return ModelCalibration(role, yaw, s, t, bboxMin, bboxMax, designLength, designWidth, designHeight)
        }

        /** Reads every role of a calibration.json text (unparseable roles are skipped). */
        fun parseAll(json: String): Map<String, ModelCalibration> {
            val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyMap()
            val out = LinkedHashMap<String, ModelCalibration>()
            for ((k, v) in root) {
                val role = k.toString()
                val m = v as? Map<*, *> ?: continue
                runCatching { parse(role, m) }.getOrNull()?.let { out[role] = it }
            }
            return out
        }

        fun parse(role: String, m: Map<*, *>): ModelCalibration? {
            val lo = m.vec("bboxMin") ?: return null
            val hi = m.vec("bboxMax") ?: return null
            val bmin = Vec3(min(lo.x, hi.x), min(lo.y, hi.y), min(lo.z, hi.z))
            val bmax = Vec3(max(lo.x, hi.x), max(lo.y, hi.y), max(lo.z, hi.z))
            val design = m.obj("designM")
            val dl = design?.num("L")
            val dw = design?.num("W")
            val dh = design?.num("H")
            val forward = m.str("forwardAxis")?.let { axisVector(it) } ?: m.num("forwardYawDeg")?.let { Angles.dir(it) }
            val yaw = m.num("yawDeg")
            val scale = m.num("scale")
            val tr = m.vec("translation")
            if (yaw != null && scale != null) {
                val r = Quat.axisAngle(Vec3.UP, Math.toRadians(yaw))
                val t = tr ?: -r.rotate(Vec3((bmin.x + bmax.x) / 2, bmin.y, (bmin.z + bmax.z) / 2) * scale)
                return ModelCalibration(role, yaw, scale, t, bmin, bmax, dl, dw, dh)
            }
            if (forward == null || dl == null) return null
            return compute(role, bmin, bmax, forward, dl, dw, dh)
        }

        private fun corners(a: Vec3, b: Vec3): List<Vec3> =
            listOf(
                Vec3(a.x, a.y, a.z), Vec3(b.x, a.y, a.z), Vec3(a.x, b.y, a.z), Vec3(b.x, b.y, a.z),
                Vec3(a.x, a.y, b.z), Vec3(b.x, a.y, b.z), Vec3(a.x, b.y, b.z), Vec3(b.x, b.y, b.z),
            )

        /** Relative size error (calibrated − design) / design. */
        fun relError(actual: Double, design: Double): Double = abs(actual - design) / design
    }
}
