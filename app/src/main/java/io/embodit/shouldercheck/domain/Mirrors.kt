package io.embodit.shouldercheck.domain

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Physical mirror ids (after the traffic-side mirroring: LEFT is always the mirror on your left). */
enum class MirrorId(val code: String) {
    INNER("MIR-I"),
    LEFT("MIR-L"),
    RIGHT("MIR-R"),
    ;

    companion object {
        fun side(dir: Dir): MirrorId = if (dir == Dir.LEFT) LEFT else RIGHT
    }
}

/**
 * One mirror (design `model.mirrors`), car frame of the driver's eye.
 *
 * @param pos centre of the mirror glass.
 * @param width / [height] glass size in metres.
 * @param fovH fixed horizontal field of view of the equivalent curved mirror (inner 40°, sides 20°).
 * @param outward the side the outward aim turns towards (null for the inner mirror, which always aims straight back).
 */
data class MirrorSpec(
    val id: MirrorId,
    val pos: Vec3,
    val width: Double,
    val height: Double,
    val fovH: Double,
    val outward: Dir?,
) {
    /** Azimuth of the mirror centre from the eye (design yawDeg: MIR-L −48.5°, MIR-R +66.7°, MIR-I +34.5°). */
    val yawDeg: Double
        get() = Angles.yawOf(pos)

    val pitchDeg: Double
        get() = Angles.pitchOf(pos)

    val distance: Double
        get() = pos.length

    /** Vertical field of view from the glass aspect ratio. */
    val fovV: Double
        get() = Math.toDegrees(2.0 * atan(tan(Math.toRadians(fovH / 2.0)) * height / width))

    /** The side of the car this mirror is on, as seen from the eye (inner mirror: the side of its azimuth). */
    val lookSide: Dir
        get() = Dir.ofSign(pos.x)

    companion object {
        const val AIM_MIN = 0.0
        const val AIM_MAX = 15.0
        const val AIM_DEFAULT = 5.0

        /** Design mirrors (right-hand traffic) with x × m; MIR-L / MIR-R swap names in left-hand traffic. */
        fun driverMirrors(side: TrafficSide): List<MirrorSpec> {
            val m = side.m
            val near = Dir.LEFT.physical(side)
            return listOf(
                MirrorSpec(MirrorId.INNER, Vec3(0.33 * m, 0.12, -0.48), 0.26, 0.07, 40.0, null),
                MirrorSpec(MirrorId.side(near), Vec3(-0.70 * m, -0.17, -0.62), 0.18, 0.11, 20.0, near),
                MirrorSpec(MirrorId.side(near.flip()), Vec3(1.44 * m, -0.17, -0.62), 0.18, 0.11, 20.0, near.flip()),
            )
        }
    }
}

/** Where a world point lands on a mirror: [u] 0 = left edge … 1 = right edge as the viewer sees the glass,
 *  [v] 0 = bottom … 1 = top (Canvas code flips v to its own y-down), [distance] from the virtual eye (for scaling). */
data class MirrorHit(val u: Double, val v: Double, val distance: Double)

/** Mirror aims (outward, degrees) of the two side mirrors; lesson 1 adjusts them, 0 … 15, default 5. */
data class MirrorAims(val left: Double = MirrorSpec.AIM_DEFAULT, val right: Double = MirrorSpec.AIM_DEFAULT) {
    fun of(id: MirrorId): Double =
        when (id) {
            MirrorId.INNER -> 0.0
            MirrorId.LEFT -> left
            MirrorId.RIGHT -> right
        }

    fun with(dir: Dir, aim: Double): MirrorAims {
        val a = aim.coerceIn(MirrorSpec.AIM_MIN, MirrorSpec.AIM_MAX)
        return if (dir == Dir.LEFT) copy(left = a) else copy(right = a)
    }
}

/**
 * The equivalent curved mirror (design `model.mirrors.render`): a virtual camera at E′ = the CURRENT eye reflected in
 * the mirror plane (so head movement gives parallax), with a fixed horizontal FOV, looking along the aim direction
 * (straight back, turned outward by the aim angle), mapped onto the glass rectangle. The mirror plane is the one that
 * reflects the default eye E0 onto the aim direction. Blind spots fall out of this projection; traffic scripts,
 * judging, the generator and the mirror Canvas all call the same functions.
 */
object MirrorModel {
    /** Horizontal unit vector the mirror looks along: +Z (back), turned outward by [aimDeg]. */
    fun aimDirection(mirror: MirrorSpec, aimDeg: Double): Vec3 {
        val o = mirror.outward ?: return Vec3(0.0, 0.0, 1.0)
        val a = Math.toRadians(aimDeg)
        return Vec3(o.sign * sin(a), 0.0, cos(a))
    }

    /** Unit normal of the glass plane (bisects "to the default eye" and the aim direction). */
    fun planeNormal(mirror: MirrorSpec, aimDeg: Double, defaultEye: Vec3 = Vec3.ZERO): Vec3 =
        ((defaultEye - mirror.pos).normalized() + aimDirection(mirror, aimDeg)).normalized()

    /** E′: [eye] reflected in the mirror plane. */
    fun virtualEye(mirror: MirrorSpec, eye: Vec3, aimDeg: Double): Vec3 {
        val n = planeNormal(mirror, aimDeg)
        return eye - n * (2.0 * (eye - mirror.pos).dot(n))
    }

    /**
     * Projects [world] into [mirror] seen from [eye]: glass coordinates, or null when the point is outside the
     * mirror's view (behind the virtual camera or outside the FOV). No occlusion test here (see [sees]).
     */
    fun project(world: Vec3, mirror: MirrorSpec, eye: Vec3, aimDeg: Double): MirrorHit? {
        val hit = projectRaw(world, mirror, eye, aimDeg) ?: return null
        if (hit.u < 0.0 || hit.u > 1.0 || hit.v < 0.0 || hit.v > 1.0) return null
        return hit
    }

    /**
     * Same projection as [project] without the glass-rectangle test (u / v may fall outside 0 … 1): the mirror
     * Canvas needs the corners of road polygons that are only partly in view. Null only behind the virtual camera.
     */
    fun projectRaw(world: Vec3, mirror: MirrorSpec, eye: Vec3, aimDeg: Double): MirrorHit? {
        val f = aimDirection(mirror, aimDeg)
        val h = Vec3.UP.cross(f) // world +X side for a backward view
        val up = f.cross(h)
        val e = virtualEye(mirror, eye, aimDeg)
        val q = world - e
        val depth = q.dot(f)
        if (depth < 0.05) return null
        val tx = q.dot(h) / depth
        val ty = q.dot(up) / depth
        val hx = tan(Math.toRadians(mirror.fovH / 2.0))
        val hy = tan(Math.toRadians(mirror.fovV / 2.0))
        val u = 0.5 + 0.5 * tx / hx
        val v = 0.5 + 0.5 * ty / hy
        return MirrorHit(u, v, q.length)
    }

    /** Outward angle of [world] seen from E′ (degrees; 0 = straight back, + = outward). Debug / tests. */
    fun outwardAngle(world: Vec3, mirror: MirrorSpec, aimDeg: Double, eye: Vec3 = Vec3.ZERO): Double {
        val e = virtualEye(mirror, eye, aimDeg)
        val q = world - e
        val o = mirror.outward?.sign ?: 1
        return Math.toDegrees(kotlin.math.atan2(o * q.x, q.z))
    }

    /**
     * True when any sample point of [box] shows in [mirror]. The inner mirror sits inside the cabin, so its view is
     * also cut by the cabin (rear window, C pillars); the side mirrors are outside it.
     */
    fun sees(box: Box, mirror: MirrorSpec, cabin: Cabin, aimDeg: Double, eye: Vec3 = cabin.eye): Boolean {
        for (p in box.samplePoints()) {
            project(p, mirror, eye, aimDeg) ?: continue
            if (mirror.id == MirrorId.INNER && !cabin.clearLine(mirror.pos, p)) continue
            return true
        }
        return false
    }

    /** Points of [box] that show in [mirror] with their glass coordinates (what the mirror Canvas draws). */
    fun hits(box: Box, mirror: MirrorSpec, cabin: Cabin, aimDeg: Double, eye: Vec3 = cabin.eye): List<MirrorHit> =
        box.samplePoints().mapNotNull { p ->
            val hit = project(p, mirror, eye, aimDeg) ?: return@mapNotNull null
            if (mirror.id == MirrorId.INNER && !cabin.clearLine(mirror.pos, p)) null else hit
        }

    /** Any mirror of the seat that shows [box]; null when none does. */
    fun anyMirrorSees(box: Box, cabin: Cabin, aims: MirrorAims, eye: Vec3 = cabin.eye): MirrorId? =
        cabin.mirrors.firstOrNull { sees(box, it, cabin, aims.of(it.id), eye) }?.id
}

/** Blind-spot helpers (design `model.mirrors.blindSpot`, U12; lesson 1 output). */
object BlindSpot {
    /** Head-mounted display field of view, half angle (design ±50°). */
    const val HMD_HALF_FOV = 50.0

    /**
     * True when [box] is in the [side] blind spot: not in the [side] mirror, not in the inner mirror, and no
     * un-occluded part within the headset view (±50°) at [headYaw] (driver seat).
     */
    fun isInBlindSpot(box: Box, side: Dir, aims: MirrorAims, cabin: Cabin, headYaw: Double = 0.0): Boolean {
        val sideMirror = cabin.mirror(MirrorId.side(side))
        if (sideMirror != null && MirrorModel.sees(box, sideMirror, cabin, aims.of(sideMirror.id))) return false
        val inner = cabin.mirror(MirrorId.INNER)
        if (inner != null && MirrorModel.sees(box, inner, cabin, 0.0)) return false
        return !Visibility.anyInView(box, cabin, headYaw, HMD_HALF_FOV)
    }

    /**
     * Length of the [side] blind spot behind your eye (m) for a mirror aim: the distance behind the eye at which the
     * near side of a sedan in the adjacent lane first shows in that side mirror. Default aim 5° ≈ 4.5 m (left).
     */
    fun length(side: Dir, aimDeg: Double, cabin: Cabin): Double {
        val mirror = cabin.mirror(MirrorId.side(side)) ?: return 0.0
        val laneCentre = cabin.carCentreX + side.sign * Road.LANE_WIDTH
        val nearX = laneCentre - side.sign * ActorKind.SEDAN.width / 2.0
        val y = Road.ROAD_Y + 0.70
        fun inMirror(z: Double) = MirrorModel.project(Vec3(nearX, y, z), mirror, cabin.eye, aimDeg) != null
        // the lane line enters the mirror at its outer edge and leaves it again far back at the inner edge (large
        // aims), so walk outward from the eye instead of bisecting, then refine
        var z = 0.0
        if (inMirror(z)) return 0.0
        while (z < MAX_LENGTH && !inMirror(z + SCAN)) z += SCAN
        if (z >= MAX_LENGTH) return MAX_LENGTH
        var lo = z
        var hi = z + SCAN
        repeat(30) {
            val mid = (lo + hi) / 2
            if (inMirror(mid)) hi = mid else lo = mid
        }
        return hi
    }

    private const val SCAN = 0.05
    private const val MAX_LENGTH = 60.0
}
