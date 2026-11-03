package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vector in metres. In the car ("cabin") frame of the design (`model.coordinateFrame`): origin = your eye point E0,
 * -Z = car front, +X = your right, +Y = up, road at y = -1.20. Plain JVM type so the domain tests run without the SDK.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)

    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Double
        get() = sqrt(dot(this))

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-12) this else this / l
    }

    fun distanceTo(o: Vec3): Double = (this - o).length

    fun lerp(o: Vec3, t: Double): Vec3 = this + (o - this) * t

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    /** Mirror about the car's centre plane x = 0 of the eye frame (used for left-hand traffic, never a negative scale). */
    fun mirrorX(m: Double): Vec3 = Vec3(x * m, y, z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)
        val RIGHT = Vec3(1.0, 0.0, 0.0)

        /** Car front / "ahead". (The SDK's own `Vector3.FORWARD` is +Z: never use that for aiming.) */
        val AHEAD = Vec3(0.0, 0.0, -1.0)
    }
}

/** Unit quaternion, Hamilton convention: `(a * b).rotate(v) == a.rotate(b.rotate(v))`. Same layout as the SDK `Quat`. */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = u.cross(v) * 2.0
        return v + t * w + u.cross(t)
    }

    fun conjugate() = Quat(-x, -y, -z, w)

    operator fun times(o: Quat) =
        Quat(
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
            w * o.w - x * o.x - y * o.y - z * o.z,
        )

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-12) IDENTITY else Quat(x / l, y / l, z / l, w / l)
    }

    fun isIdentity(eps: Double = 1e-6): Boolean = abs(x) < eps && abs(y) < eps && abs(z) < eps && abs(abs(w) - 1.0) < eps

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        fun axisAngle(unitAxis: Vec3, radians: Double): Quat {
            val s = sin(radians / 2.0)
            return Quat(unitAxis.x * s, unitAxis.y * s, unitAxis.z * s, cos(radians / 2.0))
        }

        /** Yaw-only rotation, degrees with the app convention (+ = turn right, see [Angles]). */
        fun yaw(yawDeg: Double): Quat = axisAngle(Vec3.UP, -Math.toRadians(yawDeg))

        /** Head-style rotation: yaw (+ right), then pitch (+ up), then roll (+ = right ear down). */
        fun yawPitchRoll(yawDeg: Double, pitchDeg: Double, rollDeg: Double = 0.0): Quat =
            yaw(yawDeg) * axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg)) * axisAngle(Vec3(0.0, 0.0, -1.0), Math.toRadians(rollDeg))
    }
}

/** Position + rotation. */
data class Pose(val position: Vec3, val rotation: Quat) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    /**
     * First HMD sample can be (0,0,0) + identity (tracking not settled, factory rule): such a pose and any non-finite
     * one count as invalid.
     */
    fun isValid(): Boolean = position.isFinite() && !(position.length < 1e-4 && rotation.isIdentity())
}

/**
 * Angles of the app, degrees.
 *
 * **Yaw / azimuth: 0 = car front (-Z), + = to your RIGHT (clockwise seen from above), range (-180, 180].** This is
 * the design's convention (mirror MIR-L at -48.5°, left A pillar at -25.5° … -41.6°, debug `head=-64` = a left
 * shoulder check). NOTE it is the opposite sign of rehearsal-hall's `Yaw` (+ left).
 *
 * **Pitch / elevation: + = up**, -90 … 90.
 */
object Angles {
    fun dir(yawDeg: Double, pitchDeg: Double = 0.0): Vec3 {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        return Vec3(sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }

    /** Azimuth of [v] (see above); 0 when [v] is vertical. */
    fun yawOf(v: Vec3): Double = if (hypot(v.x, v.z) < 1e-12) 0.0 else Math.toDegrees(atan2(v.x, -v.z))

    fun pitchOf(v: Vec3): Double = Math.toDegrees(atan2(v.y, hypot(v.x, v.z)))

    /** Wraps to (-180, 180]. */
    fun wrap(deg: Double): Double {
        var d = deg % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    /** Angle between two directions, degrees (0 … 180). */
    fun between(a: Vec3, b: Vec3): Double {
        val la = a.length
        val lb = b.length
        if (la < 1e-12 || lb < 1e-12) return 180.0
        return Math.toDegrees(acos((a.dot(b) / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    /** Yaw of a head rotation (+ right). Uses the forward axis, or the right axis when looking nearly straight up/down. */
    fun yawOfRotation(q: Quat): Double {
        val f = q.rotate(Vec3.AHEAD)
        if (hypot(f.x, f.z) > 0.1) return yawOf(f)
        val r = q.rotate(Vec3.RIGHT)
        return Math.toDegrees(atan2(r.z, r.x))
    }

    fun pitchOfRotation(q: Quat): Double = pitchOf(q.rotate(Vec3.AHEAD))
}

/**
 * The one way the app turns a head pose into a placement (factory rule D9 `placeUpright`): position from the head,
 * heading from the head's yaw, **pitch and roll forced to 0**. Used for the car / seat frame (S01 "坐好了", settings
 * "回正") and for any panel placed relative to the head. Assigning the whole head rotation to anything is a violation.
 */
object Placement {
    /** Car frame: eye E0 = head position, car front = head yaw; rotation is yaw only. */
    fun seatFrame(head: Pose): Pose = Pose(head.position, Quat.yaw(Angles.yawOfRotation(head.rotation)))

    /**
     * A panel [distance] m ahead of the head along its yaw, [dropDeg] below eye level, facing the head, upright.
     * The returned rotation is yaw only (the panel's normal points back along the head's yaw).
     */
    fun placeUpright(head: Pose, distance: Double, dropDeg: Double): Pose {
        val yaw = Angles.yawOfRotation(head.rotation)
        val drop = Math.toRadians(dropDeg)
        val horizontal = Angles.dir(yaw) * (distance * cos(drop))
        val position = head.position + horizontal + Vec3(0.0, -distance * sin(drop), 0.0)
        return Pose(position, Quat.yaw(yaw))
    }

    /** Car-frame point → world (Stage) point, for a car placed with [seatFrame]. */
    fun toWorld(car: Pose, p: Vec3): Vec3 = car.position + car.rotation.rotate(p)

    /** World point → car frame. */
    fun toCar(car: Pose, world: Vec3): Vec3 = car.rotation.conjugate().rotate(world - car.position)

    /** Head yaw relative to the car front, + right. */
    fun headYawInCar(car: Pose, head: Quat): Double = Angles.wrap(Angles.yawOfRotation(head) - Angles.yawOfRotation(car.rotation))
}
