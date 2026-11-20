package io.embodit.shouldercheck.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * Road users (design `gateBDecisions.models`, sizes L × W × H in metres). [model] is the role key of
 * `assets/models/calibration.json`.
 */
enum class ActorKind(val length: Double, val width: Double, val height: Double, val rider: Boolean, val model: String) {
    SEDAN(4.6, 1.8, 1.45, false, "sedan"),
    SUV(4.7, 1.9, 1.7, false, "suv"),
    EBIKE(1.7, 0.7, 1.7, true, "ebike"),
    BIKE(1.75, 0.6, 1.75, true, "bike"),
    MOTO(2.0, 0.8, 1.6, true, "moto"),
    ;

    companion object {
        fun parse(s: String): ActorKind? =
            when (s.lowercase()) {
                "car", "sedan" -> SEDAN
                "suv" -> SUV
                "ebike", "e-bike" -> EBIKE
                "bike", "bicycle" -> BIKE
                "moto", "motorcycle" -> MOTO
                else -> null
            }
    }
}

/**
 * Oriented box on the road: [centre] = centre of the BOTTOM face (on the road, y = −1.20), [headingDeg] = yaw of its
 * front (app convention, 0 = −Z = same direction as us), [length] along the heading.
 */
data class Box(val centre: Vec3, val length: Double, val width: Double, val height: Double, val headingDeg: Double = 0.0) {
    private val fwd: Vec3
        get() = Angles.dir(headingDeg)

    private val side: Vec3
        get() = Vec3(cos(Math.toRadians(headingDeg)), 0.0, sin(Math.toRadians(headingDeg)))

    fun grown(margin: Double): Box = Box(centre - Vec3(0.0, margin, 0.0), length + 2 * margin, width + 2 * margin, height + 2 * margin, headingDeg)

    /** Front end (along the heading) centre, at road level. */
    val front: Vec3
        get() = centre + fwd * (length / 2)

    val rear: Vec3
        get() = centre - fwd * (length / 2)

    /** Footprint corners in the xz plane (y ignored), counter-clockwise order not guaranteed. */
    fun footprint(): List<Vec2> {
        val f = fwd * (length / 2)
        val s = side * (width / 2)
        return listOf(centre + f + s, centre + f - s, centre - f - s, centre - f + s).map { Vec2(it.x, it.z) }
    }

    /** Sample points over the box (5 along × 3 across × 3 up) for visibility and mirror tests. */
    fun samplePoints(): List<Vec3> {
        val out = ArrayList<Vec3>(45)
        val f = fwd
        val s = side
        for (i in 0..4) for (j in 0..2) for (k in 0..2) {
            val a = (i / 4.0 - 0.5) * length
            val b = (j / 2.0 - 0.5) * width
            val c = k / 2.0 * height
            out += centre + f * a + s * b + Vec3(0.0, c, 0.0)
        }
        return out
    }

    /** Ray / box hit: true when the ray from [origin] along [dir] passes through the box. */
    fun hitByRay(origin: Vec3, dir: Vec3): Boolean {
        val o = toLocal(origin)
        val d = toLocalDir(dir)
        var t0 = 0.0
        var t1 = Double.POSITIVE_INFINITY
        val lo = doubleArrayOf(-length / 2, 0.0, -width / 2)
        val hi = doubleArrayOf(length / 2, height, width / 2)
        val ov = doubleArrayOf(o.x, o.y, o.z)
        val dv = doubleArrayOf(d.x, d.y, d.z)
        for (i in 0 until 3) {
            if (kotlin.math.abs(dv[i]) < 1e-12) {
                if (ov[i] < lo[i] || ov[i] > hi[i]) return false
            } else {
                var a = (lo[i] - ov[i]) / dv[i]
                var b = (hi[i] - ov[i]) / dv[i]
                if (a > b) a = b.also { b = a }
                t0 = maxOf(t0, a)
                t1 = minOf(t1, b)
                if (t0 > t1) return false
            }
        }
        return true
    }

    /** Local frame: x along the heading, y up from the bottom, z across. */
    private fun toLocal(p: Vec3): Vec3 {
        val q = p - centre
        return Vec3(q.dot(fwd), q.y, q.dot(side))
    }

    private fun toLocalDir(v: Vec3): Vec3 = Vec3(v.dot(fwd), v.y, v.dot(side))
}

data class Vec2(val x: Double, val z: Double) {
    operator fun minus(o: Vec2) = Vec2(x - o.x, z - o.z)

    operator fun plus(o: Vec2) = Vec2(x + o.x, z + o.z)

    operator fun times(s: Double) = Vec2(x * s, z * s)

    fun dot(o: Vec2) = x * o.x + z * o.z

    val length: Double
        get() = kotlin.math.sqrt(x * x + z * z)
}

/** Script keyframe: at time [t] (s from the start of S06 "checking") the actor's box centre is at [z]. */
data class Key(val t: Double, val z: Double)

enum class ActorRole {
    HAZARD,
    DISTRACTOR,
    PARKED,
    TRAFFIC,
    DECOR,
}

/**
 * One road user with its script (design `ecs.TrafficSystem`: position = f(t)). [x] is the fixed lane position of the
 * box centre (physical). z(t) is piecewise linear through [keys] and extrapolated with the first / last segment's
 * speed (two equal keys = parked). All actors face the car front (heading 0).
 */
data class Actor(val id: String, val kind: ActorKind, val x: Double, val keys: List<Key>, val role: ActorRole, val colour: Int = 0) {
    init {
        require(keys.isNotEmpty())
    }

    fun centreZ(t: Double): Double {
        if (keys.size == 1) return keys[0].z
        if (t <= keys.first().t) return keys[0].z + slope(0) * (t - keys[0].t)
        if (t >= keys.last().t) return keys.last().z + slope(keys.size - 2) * (t - keys.last().t)
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (t <= b.t) return if (b.t - a.t < 1e-9) b.z else a.z + (b.z - a.z) * (t - a.t) / (b.t - a.t)
        }
        return keys.last().z
    }

    private fun slope(i: Int): Double {
        val a = keys[i]
        val b = keys[i + 1]
        return if (b.t - a.t < 1e-9) 0.0 else (b.z - a.z) / (b.t - a.t)
    }

    /** dz/dt (m/s): negative = moving toward the car front (overtaking you). */
    fun velocityZ(t: Double): Double {
        val h = 0.01
        return (centreZ(t + h) - centreZ(t - h)) / (2 * h)
    }

    /** Relative speed in the design's sense: + = faster than you (overtaking). */
    fun relativeSpeed(t: Double): Double = -velocityZ(t)

    fun position(t: Double): Vec3 = Vec3(x, Road.ROAD_Y, centreZ(t))

    fun box(t: Double): Box = Box(position(t), kind.length, kind.width, kind.height)

    fun frontZ(t: Double): Double = centreZ(t) - kind.length / 2

    fun rearZ(t: Double): Double = centreZ(t) + kind.length / 2
}

object Road {
    const val ROAD_Y = -1.20
    const val LANE_WIDTH = 3.5
}

enum class StripType {
    LANE,
    EGO_LANE,
    PARKING,
    BIKE_LANE,
    SIDEWALK,
}

/** A strip of road surface between [xMin] and [xMax] (physical), running along z. */
data class Strip(val type: StripType, val xMin: Double, val xMax: Double) {
    val centre: Double
        get() = (xMin + xMax) / 2
}

/**
 * Road layout of a scene (physical x, after the traffic-side mirror). [strips] from left to right; [dashedLines] /
 * [solidLines] are the painted line x positions; [kerbX] is the kerb edge; the street runs along z.
 *
 * Design-frame layouts (right-hand traffic, c = car centre x = +0.37 from the driver's eye):
 * - lane change: three lanes c−5.25 … c+5.25, bike lane to c+7.75, kerb;
 * - pull out: you are parked in the kerb strip c−1.75 … c+1.75 (kerb at c+1.75), two lanes on the left;
 * - merge: you are in the acceleration lane c−1.75 … c+1.75 (dashed line to the main road), two main lanes on the left;
 * - door: parked strip c−1.25 … c+1.25 by the kerb, bike lane c−2.75 … c−1.25 next to your door, two lanes beyond.
 */
class RoadLayout(val situation: Situation, val side: TrafficSide) {
    private val m = side.m
    private val c = 0.37

    val strips: List<Strip>
    val dashedLines: List<Double>
    val solidLines: List<Double>
    val kerbX: Double

    init {
        val s = ArrayList<Pair<StripType, DoubleArray>>()
        val dashed = ArrayList<Double>()
        val solid = ArrayList<Double>()
        val kerb: Double
        when (situation) {
            Situation.PULL_OUT -> {
                s += StripType.LANE to doubleArrayOf(c - 8.75, c - 5.25)
                s += StripType.LANE to doubleArrayOf(c - 5.25, c - 1.75)
                s += StripType.PARKING to doubleArrayOf(c - 1.75, c + 1.75)
                dashed += listOf(c - 5.25)
                solid += listOf(c - 8.75, c - 1.75)
                kerb = c + 1.75
            }
            Situation.MERGE -> {
                s += StripType.LANE to doubleArrayOf(c - 8.75, c - 5.25)
                s += StripType.LANE to doubleArrayOf(c - 5.25, c - 1.75)
                s += StripType.EGO_LANE to doubleArrayOf(c - 1.75, c + 1.75)
                dashed += listOf(c - 5.25, c - 1.75)
                solid += listOf(c - 8.75, c + 1.75)
                kerb = c + 2.25
            }
            Situation.DOOR -> {
                s += StripType.LANE to doubleArrayOf(c - 9.75, c - 6.25)
                s += StripType.LANE to doubleArrayOf(c - 6.25, c - 2.75)
                s += StripType.BIKE_LANE to doubleArrayOf(c - 2.75, c - 1.25)
                s += StripType.PARKING to doubleArrayOf(c - 1.25, c + 1.25)
                dashed += c - 6.25
                solid += listOf(c - 9.75, c - 2.75, c - 1.25)
                kerb = c + 1.25
            }
            Situation.LANE_LEFT, Situation.LANE_RIGHT -> {
                s += StripType.LANE to doubleArrayOf(c - 5.25, c - 1.75)
                s += StripType.EGO_LANE to doubleArrayOf(c - 1.75, c + 1.75)
                s += StripType.LANE to doubleArrayOf(c + 1.75, c + 5.25)
                s += StripType.BIKE_LANE to doubleArrayOf(c + 5.25, c + 7.75)
                dashed += listOf(c - 1.75, c + 1.75)
                solid += listOf(c - 5.25, c + 5.25)
                kerb = c + 7.75
            }
        }
        s += StripType.SIDEWALK to doubleArrayOf(kerb, kerb + 4.0)
        val physical = s.map { (t, r) -> Strip(t, minOf(r[0] * m, r[1] * m), maxOf(r[0] * m, r[1] * m)) }
        strips = if (m > 0) physical else physical.reversed()
        dashedLines = dashed.map { it * m }
        solidLines = solid.map { it * m }
        kerbX = kerb * m
    }

    /** Ego car centre x (physical). */
    val egoX: Double
        get() = c * m

    /** Centre x of the lane next to yours on [dir]. */
    fun adjacentLaneX(dir: Dir): Double = egoX + dir.sign * Road.LANE_WIDTH

    /** Centre x of the lane you move into (lane scenes) — door: the bike lane next to the door. */
    fun targetX(dir: Dir): Double =
        when (situation) {
            Situation.DOOR -> (c - 2.0) * m
            else -> egoX + dir.sign * Road.LANE_WIDTH
        }

    /**
     * Where a road user of [kind] rides on [dir] in this scene (physical x of its box centre).
     * Design frame: lane scenes: the lane centre beside you; pull out: riders keep 0.85 m off the parked cars
     * (c − 2.6), cars the lane centre; door: riders 0.85 m outside your body (−1.39), cars in the far lane (c − 4.5).
     */
    fun hazardX(kind: ActorKind, dir: Dir): Double {
        val sideIsDesignLeft = (dir.sign * m) < 0
        return when (situation) {
            Situation.DOOR -> if (sideIsDesignLeft) (if (kind.rider) -1.39 else c - 4.5) * m else (c + 2.0) * m
            Situation.PULL_OUT -> if (sideIsDesignLeft) (if (kind.rider) c - 2.6 else c - 3.5) * m else (c + 3.0) * m
            Situation.MERGE -> if (sideIsDesignLeft) (c - 3.5) * m else (c + 3.0) * m
            else -> egoX + dir.sign * Road.LANE_WIDTH
        }
    }

    /** The lane beyond the target lane (pull out / merge / door: the far lane; lane change: none). */
    fun farLaneX(dir: Dir): Double? =
        when (situation) {
            Situation.PULL_OUT, Situation.MERGE -> (c - 7.0) * m
            Situation.DOOR -> (c - 8.0) * m
            else -> null
        }
}
