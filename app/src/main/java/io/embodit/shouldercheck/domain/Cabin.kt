package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Traffic side (design settings 行车方向). [m] multiplies every x of the design geometry: the design numbers are for
 * right-hand traffic with a left-hand-drive car (China); left-hand traffic regenerates the same geometry with x → −x
 * (design `coordinateFrame`, D10: never a negative scale, text is never mirrored).
 */
enum class TrafficSide(val m: Double) {
    RIGHT_HAND(1.0),
    LEFT_HAND(-1.0),
}

/** Physical left / right in the car frame. [sign] is the sign of x on that side (and of yaw toward it). */
enum class Dir(val sign: Int) {
    LEFT(-1),
    RIGHT(1);

    fun flip(): Dir = if (this == LEFT) RIGHT else LEFT

    /** The design's direction (written for right-hand traffic) on the road of [side]. */
    fun physical(side: TrafficSide): Dir = if (side == TrafficSide.RIGHT_HAND) this else flip()

    /** Far hand for a door on this side (design `judging.farHand`: left door → right hand). */
    fun farHand(): Hand = if (this == LEFT) Hand.RIGHT else Hand.LEFT

    companion object {
        fun ofSign(s: Double): Dir = if (s < 0) LEFT else RIGHT
    }
}

enum class Hand {
    LEFT,
    RIGHT,
}

/**
 * Seats (design `model.seats`). [REAR] is the design's "rearRight" passenger seat (lesson 4, ride-hailing view): it is
 * the kerb-side rear seat, so in left-hand traffic it is physically the rear-LEFT seat (all x mirrored).
 */
enum class Seat {
    DRIVER,
    REAR,
}

/** A pillar as an occluding segment (car frame) with a half-width in metres. */
data class Pillar(val name: String, val a: Vec3, val b: Vec3, val halfWidth: Double)

/** Axis-aligned occluder box (front seats seen from the rear seat). */
data class OccluderBox(val min: Vec3, val max: Vec3)

/**
 * Cabin geometry for one seat and traffic side, in that seat's eye frame (eye at the origin), all numbers from the
 * design (`model.cabinDriver`, `model.cabinRear`, `model.mirrors`) with x × [TrafficSide.m].
 *
 * The cabin is modelled as an inner box ([innerLeft] … [innerRight], floor … [roofY], [frontZ] … [rearZ]) whose
 * faces are opaque except the glass: side windows (between [beltY] and [roofY], z inside [sideWindows]), the
 * windshield (front face above the dashboard line [dashY], and the roof face forward of [windshieldTopZ]) and the rear
 * window (rear face, y inside [rearWindowY]). A pillars are slanted occluder segments; B / C pillars are the gaps
 * between the side windows.
 */
class Cabin(val seat: Seat, val side: TrafficSide) {
    private val m = side.m

    /** Eye point of this seat (= origin of the frame). */
    val eye: Vec3 = Vec3.ZERO

    /** Car centre line x (driver: +0.37; rear seat: −0.37, × m). */
    val carCentreX: Double = (if (seat == Seat.DRIVER) 0.37 else -0.37) * m

    /** Outer body sides (physical min / max x). Driver frame design: −0.54 / +1.28. */
    val bodyLeft: Double
    val bodyRight: Double

    /** Inner trim / window planes (physical min / max x). Driver frame design: −0.50 / +1.24. */
    val innerLeft: Double
    val innerRight: Double

    val beltY = -0.22
    val roofY = 0.20
    val floorY = -1.00
    val dashY = -0.42

    /** Windshield base plane (front face of the cabin box). */
    val frontZ: Double

    /** Where the windshield meets the roof. */
    val windshieldTopZ: Double

    /** Rear window plane (rear face of the cabin box). */
    val rearZ: Double
    val rearWindowY = -0.22..0.17

    /** Glass ranges of z on each side face (same for both sides). */
    val sideWindows: List<ClosedFloatingPointRange<Double>>

    val pillars: List<Pillar>
    val occluders: List<OccluderBox>

    /** Ego car length span along z (bumper to bumper), for the lane-change slot. */
    val egoFrontZ: Double
    val egoRearZ: Double

    /** Door of this seat's lesson: inner handle, physical side, outward normal, hinge (front edge, at the body side). */
    val doorSide: Dir
    val doorHandle: Vec3
    val doorNormal: Vec3
    val doorHinge: Vec3
    val doorLength = 1.15

    /** Turn-signal stalk (left of the wheel), driver seat only. */
    val stalk: Vec3? = if (seat == Seat.DRIVER) Vec3(-0.20 * m, -0.32, -0.40) else null

    /** Steering wheel centre / radius / tilt (deg back), driver seat only. */
    val wheelCentre: Vec3? = if (seat == Seat.DRIVER) Vec3(0.0, -0.36, -0.42) else null
    val wheelRadius = 0.18
    val wheelTiltDeg = 25.0

    /** Door-handle reach sphere radius (design 0.15 m; assumption A3 fallback 0.25 m). */
    val handleReach = 0.15

    /** Mirrors usable from this seat. Empty for the rear seat (design `cabinRear.mirror`: they only show your own door). */
    val mirrors: List<MirrorSpec>

    init {
        if (seat == Seat.DRIVER) {
            bodyLeft = min(-0.54 * m, 1.28 * m)
            bodyRight = max(-0.54 * m, 1.28 * m)
            innerLeft = min(-0.50 * m, 1.24 * m)
            innerRight = max(-0.50 * m, 1.24 * m)
            frontZ = -1.05
            windshieldTopZ = -0.45
            rearZ = 1.40
            // front door window up to the B pillar (z 0.10 … 0.20), rear door window to the C pillar (1.10 … 1.40)
            sideWindows = listOf(-1.05..0.10, 0.20..1.10)
            pillars =
                listOf(
                    Pillar("A-near", Vec3(-0.50 * m, -0.42, -1.05), Vec3(-0.40 * m, 0.20, -0.45), 0.04),
                    Pillar("A-far", Vec3(1.18 * m, -0.42, -1.05), Vec3(1.06 * m, 0.20, -0.45), 0.04),
                )
            occluders = emptyList()
            egoFrontZ = -2.10
            egoRearZ = 2.50
            doorSide = Dir.LEFT.physical(side)
            doorHandle = Vec3(-0.44 * m, -0.35, -0.20)
            doorHinge = Vec3(-0.54 * m, -0.35, 0.20 - doorLength)
            mirrors = MirrorSpec.driverMirrors(side)
        } else {
            bodyLeft = min(-1.28 * m, 0.54 * m)
            bodyRight = max(-1.28 * m, 0.54 * m)
            innerLeft = min(-1.24 * m, 0.50 * m)
            innerRight = max(-1.24 * m, 0.50 * m)
            frontZ = -1.70
            windshieldTopZ = -1.10
            rearZ = 0.70
            // B pillar z −0.55 … −0.45 (deviation: not given by the design, derived from the driver frame shifted
            // 0.65 m back), rear door window to the C pillar z 0.45 … 0.70 (design: occludes ~132° … 144°)
            sideWindows = listOf(-1.70..-0.55, -0.45..0.45)
            pillars =
                listOf(
                    Pillar("A-left", Vec3(-1.24 * m, -0.42, -1.70), Vec3(-1.14 * m, 0.20, -1.10), 0.04),
                    Pillar("A-right", Vec3(0.44 * m, -0.42, -1.70), Vec3(0.32 * m, 0.20, -1.10), 0.04),
                )
            // the front passenger seat right ahead (back face z −0.70, x −0.25 … 0.25, headrest top y −0.02) and the
            // driver's seat beside it
            occluders =
                listOf(
                    OccluderBox(Vec3(-0.25, -1.00, -0.90), Vec3(0.25, -0.02, -0.70)),
                    box(Vec3(-0.99 * m, -1.00, -0.90), Vec3(-0.49 * m, -0.02, -0.70)),
                )
            egoFrontZ = -2.75
            egoRearZ = 1.85
            doorSide = Dir.RIGHT.physical(side)
            doorHandle = Vec3(0.44 * m, -0.35, -0.05)
            doorHinge = Vec3(0.54 * m, -0.35, -0.55)
            mirrors = emptyList()
        }
        doorNormal = Vec3(doorSide.sign.toDouble(), 0.0, 0.0)
    }

    /** The far hand for this seat's door. */
    val farHand: Hand
        get() = doorSide.farHand()

    fun mirror(id: MirrorId): MirrorSpec? = mirrors.firstOrNull { it.id == id }

    /**
     * True when the straight line [from] → [to] is not blocked by the cabin: it leaves the cabin box through glass
     * and misses the pillars and seat occluders. [from] must be inside the cabin (the eye, or the inner mirror). A
     * [to] inside the cabin counts as visible.
     */
    fun clearLine(from: Vec3, to: Vec3): Boolean {
        val d = to - from
        val tExit = exitParam(from, d)
        val exitPoint = from + d * min(tExit, 1.0)
        if (tExit < 1.0 && !isGlass(exitPoint)) return false
        val reach = min(tExit, 1.0)
        for (p in pillars) {
            if (segmentSegmentDistance(from, from + d * reach, p.a, p.b) < p.halfWidth) return false
        }
        for (o in occluders) {
            if (segmentHitsBox(from, from + d * reach, o)) return false
        }
        return true
    }

    /** Parameter along [d] (from [o]) where the ray leaves the inner box; ≥ 1 means [o] + [d] is inside. */
    private fun exitParam(o: Vec3, d: Vec3): Double {
        var t = Double.POSITIVE_INFINITY
        fun axis(p: Double, v: Double, lo: Double, hi: Double) {
            if (v > 1e-12) t = min(t, (hi - p) / v)
            if (v < -1e-12) t = min(t, (lo - p) / v)
        }
        axis(o.x, d.x, innerLeft, innerRight)
        axis(o.y, d.y, floorY, roofY)
        axis(o.z, d.z, frontZ, rearZ)
        return t
    }

    private fun isGlass(p: Vec3): Boolean {
        val eps = 1e-6
        return when {
            abs(p.x - innerLeft) < eps || abs(p.x - innerRight) < eps -> p.y > beltY && p.y < roofY && sideWindows.any { p.z in it }
            abs(p.y - roofY) < eps -> p.z >= frontZ && p.z <= windshieldTopZ
            abs(p.y - floorY) < eps -> false
            abs(p.z - frontZ) < eps -> p.y > dashY
            abs(p.z - rearZ) < eps -> p.y in rearWindowY
            else -> true
        }
    }

    companion object {
        private fun box(a: Vec3, b: Vec3) = OccluderBox(Vec3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)), Vec3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z)))

        /** Shortest distance between segments [p0]–[p1] and [q0]–[q1]. */
        fun segmentSegmentDistance(p0: Vec3, p1: Vec3, q0: Vec3, q1: Vec3): Double {
            val u = p1 - p0
            val v = q1 - q0
            val w = p0 - q0
            val a = u.dot(u)
            val b = u.dot(v)
            val c = v.dot(v)
            val d = u.dot(w)
            val e = v.dot(w)
            val den = a * c - b * b
            var sN: Double
            var sD = den
            var tN: Double
            var tD = den
            if (den < 1e-12) {
                sN = 0.0
                sD = 1.0
                tN = e
                tD = c
            } else {
                sN = b * e - c * d
                tN = a * e - b * d
                if (sN < 0) {
                    sN = 0.0
                    tN = e
                    tD = c
                } else if (sN > sD) {
                    sN = sD
                    tN = e + b
                    tD = c
                }
            }
            if (tN < 0) {
                tN = 0.0
                sN = if (-d < 0) 0.0 else if (-d > a) sD else -d
                sD = if (-d < 0 || -d > a) sD else a
            } else if (tN > tD) {
                tN = tD
                sN = if (-d + b < 0) 0.0 else if (-d + b > a) sD else -d + b
                sD = if (-d + b < 0 || -d + b > a) sD else a
            }
            val sc = if (abs(sN) < 1e-12) 0.0 else sN / sD
            val tc = if (abs(tN) < 1e-12) 0.0 else tN / tD
            return (w + u * sc - v * tc).length
        }

        fun segmentHitsBox(p0: Vec3, p1: Vec3, b: OccluderBox): Boolean {
            var t0 = 0.0
            var t1 = 1.0
            val d = p1 - p0
            val o = doubleArrayOf(p0.x, p0.y, p0.z)
            val dv = doubleArrayOf(d.x, d.y, d.z)
            val lo = doubleArrayOf(b.min.x, b.min.y, b.min.z)
            val hi = doubleArrayOf(b.max.x, b.max.y, b.max.z)
            for (i in 0 until 3) {
                if (abs(dv[i]) < 1e-12) {
                    if (o[i] < lo[i] || o[i] > hi[i]) return false
                } else {
                    var a = (lo[i] - o[i]) / dv[i]
                    var c = (hi[i] - o[i]) / dv[i]
                    if (a > c) a = c.also { c = a }
                    t0 = max(t0, a)
                    t1 = min(t1, c)
                    if (t0 > t1) return false
                }
            }
            return true
        }
    }
}
