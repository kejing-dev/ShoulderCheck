package io.embodit.shouldercheck.scene

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import io.embodit.shouldercheck.domain.Actor
import io.embodit.shouldercheck.domain.ActorRole
import io.embodit.shouldercheck.domain.Box
import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.MirrorModel
import io.embodit.shouldercheck.domain.MirrorSpec
import io.embodit.shouldercheck.domain.Road
import io.embodit.shouldercheck.domain.RoadLayout
import io.embodit.shouldercheck.domain.StripType
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.ui.theme.ScColors
import kotlin.math.abs

/** A filled polygon in glass coordinates (u 0 → 1 left → right as seen, v 0 = bottom). */
class MPoly(val color: Color, val pts: FloatArray)

/** A silhouette: [image] key ("sedan_fr"), ground point (u, v), canvas heights per metre, highlighted in S09. */
class MSprite(val image: String, val u: Float, val v: Float, val vPerM: Float, val highlight: Boolean, val distance: Float)

/** One mirror picture: what the mirror Canvas draws (design model.mirrors.render), produced ~20 Hz by the scene. */
class MirrorFrame(
    val id: MirrorId,
    val horizonV: Float,
    val polys: List<MPoly>,
    val sprites: List<MSprite>,
    /** Drawn over the sprites: your own car's flank (side mirrors). */
    val front: List<MPoly>,
    /** Inner mirror: the rear window outline; everything outside it is the car's interior. */
    val window: FloatArray?,
    val seq: Int,
)

/** Compose-side holder of the latest frame per mirror and the N3 counters (Canvas draws per second). */
object MirrorStore {
    val frames = MirrorId.values().associateWith { mutableStateOf<MirrorFrame?>(null) }

    @Volatile var draws = 0
}

/**
 * Builds the mirror pictures with the domain's equivalent curved mirror ([MirrorModel.projectRaw]: virtual camera at the
 * CURRENT eye reflected in the glass, fixed FOV, aim angle) — the same projection the judge and the generator use, so
 * a car is in the picture exactly when the rules say it is in the mirror.
 */
internal object MirrorFrames {
    private val Z_SAMPLES = doubleArrayOf(-1.0, 0.0, 1.0, 2.0, 3.0, 4.5, 6.0, 8.0, 10.0, 13.0, 17.0, 22.0, 28.0, 36.0, 46.0, 60.0, 80.0, 110.0, 160.0)
    private var seq = 0

    fun build(
        mirror: MirrorSpec,
        cabin: Cabin,
        road: RoadLayout,
        eye: Vec3,
        aim: Double,
        actors: List<Pair<Actor, Box>>,
        highlight: String?,
        hood: Color,
    ): MirrorFrame {
        fun p(x: Double, y: Double, z: Double): Pair<Float, Float>? {
            val h = MirrorModel.projectRaw(Vec3(x, y, z), mirror, eye, aim) ?: return null
            return h.u.toFloat().coerceIn(-6f, 7f) to h.v.toFloat().coerceIn(-6f, 7f)
        }
        val horizon = MirrorModel.projectRaw(eye + MirrorModel.aimDirection(mirror, aim) * 5000.0, mirror, eye, aim)?.v?.toFloat() ?: 0.5f
        val y = Road.ROAD_Y
        val polys = ArrayList<MPoly>()
        fun strip(color: Color, x0: Double, x1: Double, yy: Double = y, zs: DoubleArray = Z_SAMPLES) {
            val left = ArrayList<Pair<Float, Float>>()
            val right = ArrayList<Pair<Float, Float>>()
            for (z in zs) {
                val a = p(x0, yy, z) ?: continue
                val b = p(x1, yy, z) ?: continue
                left += a
                right += b
            }
            if (left.size < 2) return
            val pts = FloatArray((left.size + right.size) * 2)
            var i = 0
            for ((u, v) in left) {
                pts[i++] = u
                pts[i++] = v
            }
            for ((u, v) in right.asReversed()) {
                pts[i++] = u
                pts[i++] = v
            }
            polys += MPoly(color, pts)
        }
        // road surface strips, kerb, sidewalk
        val xMin = road.strips.minOf { it.xMin }
        val xMax = road.strips.maxOf { it.xMax }
        strip(ScColors.Road.shade(0.92f), xMin - 12.0, xMax + 12.0, y - 0.01)
        for (s in road.strips) {
            val c =
                when (s.type) {
                    StripType.BIKE_LANE -> ScColors.BikeLane
                    StripType.SIDEWALK -> ScColors.Sidewalk
                    StripType.PARKING -> ScColors.Road.shade(0.95f)
                    else -> ScColors.Road
                }
            strip(c, s.xMin, s.xMax, if (s.type == StripType.SIDEWALK) y + 0.15 else y)
        }
        strip(ScColors.Kerb, road.kerbX - 0.1, road.kerbX + 0.1, y + 0.15)
        // painted lines
        val lw = 0.075
        for (x in road.solidLines) strip(ScColors.LaneLine, x - lw, x + lw, y + 0.005)
        for (x in road.dashedLines) {
            var z = -1.5
            while (z < 120.0) {
                strip(ScColors.LaneLine, x - lw, x + lw, y + 0.005, doubleArrayOf(z, z + 3.0))
                z += 9.0
            }
        }
        // buildings beyond the sidewalk (a band) for depth
        val side = if (road.kerbX > road.egoX) 1.0 else -1.0
        val bx = road.kerbX + side * 4.0
        val wall = ArrayList<Pair<Float, Float>>()
        for (z in Z_SAMPLES) p(bx, y, z)?.let { wall += it }
        val top = ArrayList<Pair<Float, Float>>()
        for (z in Z_SAMPLES) p(bx, y + 9.0, z)?.let { top += it }
        if (wall.size >= 2 && top.size >= 2) {
            val pts = FloatArray((wall.size + top.size) * 2)
            var i = 0
            for ((u, v) in wall) {
                pts[i++] = u
                pts[i++] = v
            }
            for ((u, v) in top.asReversed()) {
                pts[i++] = u
                pts[i++] = v
            }
            polys.add(0, MPoly(ScColors.BuildingNear, pts))
        }
        // road users: pre-rendered front silhouettes (followers face you), far to near
        val sprites = ArrayList<MSprite>()
        for ((a, box) in actors) {
            if (a.role == ActorRole.DECOR) continue
            val role = a.kind.model ?: continue
            if (!MirrorModel.sees(box, mirror, cabin, aim, eye)) continue
            val bottom = MirrorModel.projectRaw(box.centre, mirror, eye, aim) ?: continue
            val topHit = MirrorModel.projectRaw(box.centre + Vec3(0.0, box.height, 0.0), mirror, eye, aim) ?: continue
            val dx = box.centre.x - cabin.carCentreX
            val view = if (abs(dx) < 1.0) "front" else if (dx < 0) "fr" else "fl"
            val vPerM = ((topHit.v - bottom.v) / box.height).toFloat()
            sprites += MSprite("${role}_$view", bottom.u.toFloat(), bottom.v.toFloat(), vPerM, a.id == highlight, bottom.distance.toFloat())
        }
        sprites.sortByDescending { it.distance }
        // your own car's flank in the side mirrors (inner quarter of the glass at the default aim)
        val front = ArrayList<MPoly>()
        if (mirror.id != MirrorId.INNER) {
            val bodyX = if (mirror.pos.x < cabin.carCentreX) cabin.bodyLeft else cabin.bodyRight
            val lower = listOf(Vec3(bodyX, -1.0, -0.9), Vec3(bodyX, -1.0, 2.6), Vec3(bodyX, -0.24, 2.6), Vec3(bodyX, -0.24, -0.9))
            val glass = listOf(Vec3(bodyX, -0.22, 0.2), Vec3(bodyX, -0.22, 1.3), Vec3(bodyX, 0.18, 1.2), Vec3(bodyX, 0.18, 0.25))
            for ((pts3, col) in listOf(lower to hood.shade(0.75f), glass to ScColors.Dash)) {
                val uv = pts3.mapNotNull { MirrorModel.projectRaw(it, mirror, eye, aim) }
                if (uv.size == pts3.size) front += MPoly(col, uv.flatMap { listOf(it.u.toFloat().coerceIn(-6f, 7f), it.v.toFloat().coerceIn(-6f, 7f)) }.toFloatArray())
            }
        }
        // inner mirror: the rear window
        val window =
            if (mirror.id == MirrorId.INNER) {
                val zr = cabin.rearZ
                val corners = listOf(Vec3(cabin.innerLeft + 0.05, cabin.rearWindowY.start, zr), Vec3(cabin.innerRight - 0.05, cabin.rearWindowY.start, zr), Vec3(cabin.innerRight - 0.12, cabin.rearWindowY.endInclusive, zr), Vec3(cabin.innerLeft + 0.12, cabin.rearWindowY.endInclusive, zr))
                val uv = corners.mapNotNull { MirrorModel.projectRaw(it, mirror, eye, aim) }
                if (uv.size == 4) uv.flatMap { listOf(it.u.toFloat(), it.v.toFloat()) }.toFloatArray() else null
            } else null
        return MirrorFrame(mirror.id, horizon, polys, sprites, front, window, ++seq)
    }
}
