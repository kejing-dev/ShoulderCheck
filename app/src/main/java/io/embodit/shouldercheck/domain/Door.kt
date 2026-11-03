package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Door model (design `model.doorModel`): grip inside the handle's reach sphere = hold (the door does not move; the
 * holding controller is the opening hand); then push the hand out along the door's outward normal: 3 cm → the door
 * cracks open (0° → 8°, edge ~0.16 m out), 10 cm → it swings open (8° → 60° over 0.5 s, edge ~1.0 m out). Releasing
 * the grip stops the door at its current angle; [close] (B key / P2 "关门") resets it. "Execute" = the angle crossing
 * 12° (edge into the passing band 0.25 m outside the body).
 *
 * Angles in degrees, push in metres (outward displacement of the hand since the grab, see [pushDistance]).
 */
class DoorModel(val cabin: Cabin) {
    var angle = 0.0
        private set
    private var target = 0.0

    /** The hand holding the handle now, or null. */
    var holder: Hand? = null
        private set

    /** The hand that last grabbed the handle (stays after release, for the far-hand step). */
    var lastGrabHand: Hand? = null
        private set

    /** Time-free update: [dt] s since the last call, [holding] the hand gripping inside the reach sphere, [push] outward m. */
    fun update(dt: Double, holding: Hand?, push: Double): Double {
        if (holding != null && holder == null) lastGrabHand = holding
        holder = holding
        if (holding == null) {
            target = angle // released: stays where it is
        } else {
            val wanted = if (push >= OPEN_PUSH) OPEN_DEG else if (push >= CRACK_PUSH) CRACK_DEG else 0.0
            target = max(target, wanted).coerceAtMost(OPEN_DEG)
        }
        if (angle < target) {
            val rate = if (angle < CRACK_DEG) CRACK_DEG / CRACK_TIME else (OPEN_DEG - CRACK_DEG) / OPEN_TIME
            angle = min(target, angle + rate * dt)
        }
        return angle
    }

    fun close() {
        angle = 0.0
        target = 0.0
    }

    /** Outward reach of the door edge beyond the body side at [deg] (m): 8° → 0.16, 60° → 1.0. */
    fun edgeOut(deg: Double = angle): Double = cabin.doorLength * sin(Math.toRadians(deg))

    val executed: Boolean
        get() = angle > EXECUTE_DEG

    companion object {
        const val CRACK_PUSH = 0.03
        const val OPEN_PUSH = 0.10
        const val CRACK_DEG = 8.0
        const val OPEN_DEG = 60.0
        const val CRACK_TIME = 0.15
        const val OPEN_TIME = 0.5
        const val EXECUTE_DEG = 12.0
        const val MARGIN = 0.3

        /** Hand displacement since the grab along the outward normal (m; negative = pulled in). */
        fun pushDistance(grabPoint: Vec3, hand: Vec3, outwardNormal: Vec3): Double = (hand - grabPoint).dot(outwardNormal)

        /** True when [hand] is inside the handle's reach sphere. */
        fun inReach(cabin: Cabin, hand: Vec3, radius: Double = cabin.handleReach): Boolean = hand.distanceTo(cabin.doorHandle) <= radius

        /** Plan-view polygon swept by the door from closed to [deg] (hinge, then arc points). */
        fun sweep(cabin: Cabin, deg: Double): List<Vec2> {
            val h = Vec2(cabin.doorHinge.x, cabin.doorHinge.z)
            val out = cabin.doorSide.sign.toDouble()
            val pts = ArrayList<Vec2>()
            pts += h
            val steps = max(1, (deg / 7.5).toInt() + 1)
            for (i in 0..steps) {
                val a = Math.toRadians(deg * i / steps)
                pts += Vec2(h.x + out * cabin.doorLength * sin(a), h.z + cabin.doorLength * cos(a))
            }
            return pts
        }

        /**
         * Door conflict (design `doorModel.conflict`): while the door is past 12°, any road user's footprint within
         * [MARGIN] of the swept sector. A crack (≤ 8°) never conflicts.
         */
        fun conflicts(cabin: Cabin, deg: Double, box: Box): Boolean {
            if (deg <= EXECUTE_DEG) return false
            return Geometry2D.distance(sweep(cabin, deg), box.footprint()) < MARGIN
        }

        /** Conflict zone used by the generator: the full-open sweep (60°) plus the margin. */
        fun inOpenZone(cabin: Cabin, box: Box): Boolean = Geometry2D.distance(sweep(cabin, OPEN_DEG), box.footprint()) < MARGIN
    }
}

/** Small 2D (xz plane) helpers. */
object Geometry2D {
    /** Distance between two convex polygons (0 when they overlap). */
    fun distance(a: List<Vec2>, b: List<Vec2>): Double {
        if (overlap(a, b)) return 0.0
        var best = Double.POSITIVE_INFINITY
        for (p in a) for (i in b.indices) best = min(best, pointSegment(p, b[i], b[(i + 1) % b.size]))
        for (p in b) for (i in a.indices) best = min(best, pointSegment(p, a[i], a[(i + 1) % a.size]))
        return best
    }

    fun pointSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val l2 = ab.dot(ab)
        val t = if (l2 < 1e-12) 0.0 else ((p - a).dot(ab) / l2).coerceIn(0.0, 1.0)
        return (p - (a + ab * t)).length
    }

    /** Separating-axis test for convex polygons. */
    fun overlap(a: List<Vec2>, b: List<Vec2>): Boolean {
        for (poly in listOf(a, b)) {
            for (i in poly.indices) {
                val e = poly[(i + 1) % poly.size] - poly[i]
                if (abs(e.x) < 1e-12 && abs(e.z) < 1e-12) continue
                val n = Vec2(-e.z, e.x)
                var minA = Double.POSITIVE_INFINITY
                var maxA = Double.NEGATIVE_INFINITY
                for (p in a) {
                    val d = p.dot(n)
                    minA = min(minA, d)
                    maxA = max(maxA, d)
                }
                var minB = Double.POSITIVE_INFINITY
                var maxB = Double.NEGATIVE_INFINITY
                for (p in b) {
                    val d = p.dot(n)
                    minB = min(minB, d)
                    maxB = max(maxB, d)
                }
                if (maxA < minB || maxB < minA) return false
            }
        }
        return true
    }
}
