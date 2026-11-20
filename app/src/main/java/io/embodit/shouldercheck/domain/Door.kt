package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Door model v0.2.0 (design `model.doorModel`, profile D50 — user on the headset 2026-11-17: 「现在门 push 不了」):
 * ONE tap on the inner handle (controller ray + trigger, hand ray + pinch, eye-hand gaze + pinch, a poke, the 「开门」
 * button or the A key) swings the door open by itself: 0° → [OPEN_DEG] in [OPEN_TIME] s, ease-in-out. There is no grip,
 * no push distance, no far-hand rule. On a close call the door stops at [STOP_DEG] (the cyclist is braking hard).
 *
 * Time-based and pure: [angleAt] is a function of the time since the tap, so the conflict judge, the replay and the
 * scene all read the same curve. Angles in degrees.
 */
object DoorModel {
    const val OPEN_DEG = 35.0
    const val OPEN_TIME = 1.0
    const val STOP_DEG = 12.0

    /** After the door is open the judge keeps watching the swept zone this long (a rider that arrives a beat later). */
    const val WATCH_TIME = 1.0
    const val MARGIN = 0.3

    /** Door angle [sinceTap] s after the tap (null = not tapped): ease-in-out to 35°, held; [stopAt] caps it (close call). */
    fun angleAt(sinceTap: Double?, stopAt: Double? = null): Double {
        val s = sinceTap ?: return 0.0
        if (s <= 0.0) return 0.0
        val u = (s / OPEN_TIME).coerceIn(0.0, 1.0)
        val a = OPEN_DEG * (u * u * (3 - 2 * u))
        return if (stopAt != null) minOf(a, stopAt) else a
    }

    /** Outward reach of the door edge beyond the body side at [deg] (m): 35° → 0.66 m. */
    fun edgeOut(cabin: Cabin, deg: Double): Double = cabin.doorLength * sin(Math.toRadians(deg))

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

    /** True when a road user's footprint is within [MARGIN] of the sector swept by the door at [deg]. */
    fun conflicts(cabin: Cabin, deg: Double, box: Box): Boolean {
        if (deg <= 1.0) return false
        return Geometry2D.distance(sweep(cabin, deg), box.footprint()) < MARGIN
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
