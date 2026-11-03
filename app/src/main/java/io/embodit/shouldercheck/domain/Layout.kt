package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Look of a cabin part (palette.cabin A, dark grey matte). */
enum class PartMat {
    DASH,
    TRIM,
    SEAT,
    HEADLINER,
    FRAME,
    HOOD,
    DARK,
    HANDLE,
    MIRROR_BACK,
}

/** Mesh of a cabin part: the shared unit cube (scaled), or the steering-wheel ring. */
enum class PartMesh {
    CUBE,
    RING,
}

/**
 * One cabin part in the seat's eye frame: a [mesh] scaled to [size] (metres; the ring: diameter, diameter, tube scale)
 * at [centre], turned by [rotation] (SDK right-handed quaternion). Never a negative scale: left-hand traffic builds
 * the parts again with x × m (design `coordinateFrame`, D10).
 */
data class Part(
    val name: String,
    val centre: Vec3,
    val size: Vec3,
    val rotation: Quat = Quat.IDENTITY,
    val mat: PartMat,
    val mesh: PartMesh = PartMesh.CUBE,
) {
    /** Axis-aligned bounds (conservative for rotated parts), for the D20 sight-cone fade. */
    fun bounds(): Pair<Vec3, Vec3> {
        val h = size * 0.5
        var lo = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var hi = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) for (sz in listOf(-1.0, 1.0)) {
            val p = centre + rotation.rotate(Vec3(h.x * sx, h.y * sy, h.z * sz))
            lo = Vec3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
            hi = Vec3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
        }
        return lo to hi
    }
}

/** A door: [parts] in the car frame with the door closed; the pivot sits at [hinge] and turns about +Y. */
data class DoorSpec(val name: String, val side: Dir, val hinge: Vec3, val parts: List<Part>, val opens: Boolean)

/** All cabin geometry of one seat / traffic side. */
data class CabinLayout(val seat: Seat, val side: TrafficSide, val parts: List<Part>, val doors: List<DoorSpec>) {
    val openingDoor: DoorSpec?
        get() = doors.firstOrNull { it.opens }
}

/**
 * Where an attachment panel sits in the car frame: centre, yaw-only rotation (front = local +Z facing the eye
 * horizontally, up = world up) and scale. Built with [Placement.placeUpright] from the eye pose E0 (factory rule
 * 2026-10-30: every panel pose goes through the one yaw-only placeUpright).
 */
data class PanelPlace(val centre: Vec3, val scale: Double, val rotation: Quat = Quat.IDENTITY) {
    companion object {
        /** A panel [distance] m from the eye at azimuth [yawDeg] (+ right), [dropDeg] below the eye line, upright. */
        fun upright(yawDeg: Double, distance: Double, dropDeg: Double, scale: Double): PanelPlace {
            val pose = Placement.placeUpright(Pose(Vec3.ZERO, Quat.yaw(yawDeg)), distance, dropDeg)
            return PanelPlace(pose.position, scale, pose.rotation)
        }
    }
}

object Layout {
    /** Hood colours (design cabinDriver.hood: random of 4): silver, red, blue, white. */
    val HOOD_COLOURS = intArrayOf(0xFFA9AFB6.toInt(), 0xFF9E2B25.toInt(), 0xFF2F4F7F.toInt(), 0xFFE6E6E2.toInt())

    /** Rotation that turns +Z onto unit [dir] (shortest arc). */
    fun fromTo(a: Vec3, b: Vec3): Quat {
        val d = a.dot(b)
        if (d > 0.999999) return Quat.IDENTITY
        if (d < -0.999999) {
            val helper = if (abs(a.x) < 0.9) Vec3.RIGHT else Vec3.UP
            val axis = a.cross(helper).normalized()
            return Quat(axis.x, axis.y, axis.z, 0.0)
        }
        val c = a.cross(b)
        return Quat(c.x, c.y, c.z, 1.0 + d).normalized()
    }

    /** Right-handed rotation about +X (SDK convention). */
    fun rotX(deg: Double): Quat = Quat.axisAngle(Vec3.RIGHT, Math.toRadians(deg))

    /** Right-handed rotation about +Y (SDK convention: +Z → +X at +90°). */
    fun rotY(deg: Double): Quat = Quat.axisAngle(Vec3.UP, Math.toRadians(deg))

    /**
     * Panel facing: local +Z (the panel's front) towards [viewer], yaw then pitch, never roll. Same formula as the
     * reference PanelRig (rehearsal-hall FacePanel).
     */
    fun facing(panel: Vec3, viewer: Vec3): Quat {
        val to = viewer - panel
        val flat = kotlin.math.hypot(to.x, to.z)
        val yaw = if (flat < 1e-6) 0.0 else kotlin.math.atan2(to.x, to.z)
        val pitch = kotlin.math.atan2(to.y, flat)
        return Quat.axisAngle(Vec3.UP, yaw) * Quat.axisAngle(Vec3.RIGHT, -pitch)
    }

    // ------------------------------------------------------------------------------------------------- panels

    /**
     * P1 centre screen (design panels[P1].placement): driver 1.05 m, yaw +6°, 10° below the eye line, upright; rear:
     * the seat-back screen (design: face at z −0.69, 0.72 m, 16° below — D12 note: nearer than the 0.8 m band because
     * the seat-back screen is where it is).
     */
    fun p1(seat: Seat, side: TrafficSide): PanelPlace =
        if (seat == Seat.DRIVER) PanelPlace.upright(6.0 * side.m, 1.05, 10.0, 0.8) else PanelPlace.upright(0.0, REAR_DIST, REAR_DROP, 0.6)

    /** The 800 × 600 dp P1 (result card, scorecard): same place as P1 (upright, lower edge at −19.9°, above the dash −21.8°). */
    fun p1Tall(seat: Seat, side: TrafficSide): PanelPlace = p1(seat, side)

    /** P2 HUD strip: driver 1.10 m, yaw 0, 15° below, upright; rear: the same seat-back screen as P1. */
    fun p2(seat: Seat, side: TrafficSide): PanelPlace =
        if (seat == Seat.DRIVER) PanelPlace.upright(0.0, 1.10, 15.0, 0.8) else PanelPlace.upright(0.0, REAR_DIST, REAR_DROP, 0.6)

    /** Seat-back screen centre (design z −0.69, y −0.20 → 0.718 m, 16.2° below the eye). */
    val REAR_SCREEN = Vec3(0.0, -0.20, -0.69)
    private val REAR_DIST = REAR_SCREEN.length
    private val REAR_DROP = -Angles.pitchOf(REAR_SCREEN)

    /**
     * B-pillar turn readouts (design panels[ANG]: yaw ≈ ±95°, 200 × 80 dp, scale 0.6 at 0.55 m). DEVIATION: placed on
     * the inner trim plane just forward of each B pillar (the 0.55 m point is outside the left trim and mid-air on the
     * right), scale grown with the distance so the angular size equals the design's 0.6 at 0.55 m.
     */
    fun ang(seat: Seat, side: TrafficSide, dir: Dir): PanelPlace {
        val cabin = Cabin(seat, side)
        val x = if (dir == Dir.LEFT) cabin.innerLeft + 0.02 else cabin.innerRight - 0.02
        val z = abs(x) * tan(Math.toRadians(5.0))
        val p = Vec3(x, -0.06, z)
        // upright, turned to the eye about the vertical axis only (same rule as placeUpright)
        return PanelPlace(p, 0.6 * p.length / 0.55, Quat.yaw(Angles.yawOf(p)))
    }

    // ------------------------------------------------------------------------------------------------- guides

    /** Where the guide ring shows for a step (demo S04, hints in S06), car frame of [cabin]'s seat. */
    fun guideTarget(step: Step, cabin: Cabin): Vec3? {
        fun toward(p: Vec3, by: Double) = p + (cabin.eye - p).normalized() * by
        return when (step) {
            Step.INNER_MIRROR, Step.LEFT_MIRROR, Step.RIGHT_MIRROR, Step.CONFIRM_LEFT, Step.CONFIRM_RIGHT -> {
                val id = step.mirrorId ?: MirrorId.side(step.dir!!)
                cabin.mirror(id)?.let { toward(it.pos, 0.03) }
            }
            Step.SHOULDER_LEFT, Step.SHOULDER_RIGHT, Step.SEE_HAZARD -> blindSpotWindow(cabin, step.dir ?: Dir.LEFT.physical(cabin.side))
            Step.SIGNAL_LEFT, Step.SIGNAL_RIGHT -> cabin.stalk?.let { it + Vec3(0.0, 0.03, 0.0) }
            Step.FAR_HAND -> cabin.doorHandle + Vec3(0.0, 0.04, 0.0)
        }
    }

    /** Centre of the blind-spot window on [dir]: the side window at azimuth ±125° (driver) / ±115° (rear), 5° below eye level. */
    fun blindSpotWindow(cabin: Cabin, dir: Dir): Vec3 {
        val yaw = dir.sign * (if (cabin.seat == Seat.DRIVER) 125.0 else 115.0)
        val d = Angles.dir(yaw, -5.0)
        val plane = if (dir == Dir.LEFT) cabin.innerLeft + 0.03 else cabin.innerRight - 0.03
        val t = plane / d.x
        return d * min(t, 1.2)
    }

    // ------------------------------------------------------------------------------------------------- cabin

    fun cabin(seat: Seat, side: TrafficSide): CabinLayout = if (seat == Seat.DRIVER) driver(side) else rear(side)

    private class Parts(val m: Double) {
        val list = ArrayList<Part>()

        /** Box between design corners (x × m, re-sorted). */
        fun box(name: String, lo: Vec3, hi: Vec3, mat: PartMat): Part {
            val x0 = min(lo.x * m, hi.x * m)
            val x1 = max(lo.x * m, hi.x * m)
            val p = Part(name, Vec3((x0 + x1) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2), Vec3(x1 - x0, hi.y - lo.y, hi.z - lo.z), mat = mat)
            list += p
            return p
        }

        /** Bar of square section [thick] between design points [a] and [b]. */
        fun bar(name: String, a: Vec3, b: Vec3, thick: Double, mat: PartMat): Part {
            val pa = a.mirrorX(m)
            val pb = b.mirrorX(m)
            val d = pb - pa
            val p = Part(name, (pa + pb) * 0.5, Vec3(thick, thick, d.length), fromTo(Vec3(0.0, 0.0, 1.0), d.normalized()), mat)
            list += p
            return p
        }

        /** Slab whose top runs from (y0, z0) to (y1, z1) (in the yz plane), spanning design x [xa] … [xb]. */
        fun slab(name: String, xa: Double, xb: Double, y0: Double, z0: Double, y1: Double, z1: Double, thick: Double, mat: PartMat): Part {
            val x0 = min(xa * m, xb * m)
            val x1 = max(xa * m, xb * m)
            val top = Vec3(0.0, y1 - y0, z1 - z0)
            val len = top.length
            val dir = top.normalized()
            var rot = fromTo(Vec3(0.0, 0.0, 1.0), dir)
            // the shortest arc from +Z to a direction pointing ahead (−Z) can turn local +Y downwards: spin it
            // half a turn about the slab's own axis so its top face (and the thickness offset) is up
            if (rot.rotate(Vec3.UP).y < 0) rot = rot * Quat.axisAngle(Vec3(0.0, 0.0, 1.0), Math.PI)
            val normal = rot.rotate(Vec3.UP)
            val mid = Vec3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2) - normal * (thick / 2)
            val p = Part(name, mid, Vec3(x1 - x0, thick, len), rot, mat)
            list += p
            return p
        }

        /** A box turned to face [eye] (mirror housings), centred [back] m behind [pos] as seen from the eye. */
        fun facingBox(name: String, pos: Vec3, size: Vec3, eye: Vec3, back: Double, mat: PartMat): Part {
            val p = pos.mirrorX(m)
            val e = eye.mirrorX(m)
            val rot = facing(p, e)
            val c = p - (e - p).normalized() * back
            val part = Part(name, c, size, rot, mat)
            list += part
            return part
        }
    }

    /** Driver seat cabin (design cabinDriver, mirrors), eye frame of the driver. */
    private fun driver(side: TrafficSide): CabinLayout {
        val m = side.m
        val b = Parts(m)
        b.box("floor", Vec3(-0.50, -1.03, -1.05), Vec3(1.24, -1.00, 1.40), PartMat.TRIM)
        b.box("roof", Vec3(-0.53, 0.20, -0.47), Vec3(1.27, 0.24, 1.43), PartMat.HEADLINER)
        b.slab("dashTop", -0.50, 1.24, -0.30, -0.75, -0.42, -1.05, 0.05, PartMat.DASH)
        b.box("dashFace", Vec3(-0.50, -0.66, -0.80), Vec3(1.24, -0.30, -0.745), PartMat.DASH)
        b.box("footwell", Vec3(-0.50, -1.00, -1.05), Vec3(1.24, -0.66, -0.80), PartMat.TRIM)
        b.box("cluster", Vec3(-0.18, -0.32, -0.78), Vec3(0.18, -0.285, -0.66), PartMat.DARK)
        b.box("console", Vec3(0.25, -1.00, -0.78), Vec3(0.49, -0.62, 0.25), PartMat.TRIM)
        b.bar("gear", Vec3(0.37, -0.62, -0.30), Vec3(0.37, -0.50, -0.34), 0.03, PartMat.DARK)
        wheel(b, Vec3(0.0, -0.36, -0.42), Vec3(0.0, -0.48, -0.72))
        b.bar("stalk", Vec3(-0.06, -0.335, -0.45), Vec3(-0.20, -0.32, -0.40), 0.015, PartMat.DARK)
        b.bar("pillarA-near", Vec3(-0.50, -0.42, -1.05), Vec3(-0.40, 0.20, -0.45), 0.08, PartMat.TRIM)
        b.bar("pillarA-far", Vec3(1.18, -0.42, -1.05), Vec3(1.06, 0.20, -0.45), 0.08, PartMat.TRIM)
        b.box("pillarB-near", Vec3(-0.54, -0.22, 0.10), Vec3(-0.49, 0.20, 0.20), PartMat.TRIM)
        b.box("pillarB-far", Vec3(1.23, -0.22, 0.10), Vec3(1.28, 0.20, 0.20), PartMat.TRIM)
        b.box("pillarC-near", Vec3(-0.54, -0.22, 1.10), Vec3(-0.49, 0.20, 1.40), PartMat.TRIM)
        b.box("pillarC-far", Vec3(1.23, -0.22, 1.10), Vec3(1.28, 0.20, 1.40), PartMat.TRIM)
        b.box("rearShelf", Vec3(-0.50, -0.30, 1.05), Vec3(1.24, -0.22, 1.43), PartMat.TRIM)
        b.box("rearHeader", Vec3(-0.50, 0.17, 1.38), Vec3(1.24, 0.21, 1.44), PartMat.HEADLINER)
        b.box("rearBelow", Vec3(-0.50, -1.00, 1.38), Vec3(1.24, -0.30, 1.43), PartMat.TRIM)
        // seats: yours (below and behind the eye), the passenger's, the rear bench
        b.box("seatCushion", Vec3(-0.26, -0.80, -0.15), Vec3(0.26, -0.66, 0.35), PartMat.SEAT)
        b.box("seatBack", Vec3(-0.26, -0.66, 0.30), Vec3(0.26, -0.12, 0.40), PartMat.SEAT)
        b.box("paxCushion", Vec3(0.48, -0.80, -0.15), Vec3(1.00, -0.66, 0.35), PartMat.SEAT)
        b.box("paxBack", Vec3(0.48, -0.66, 0.30), Vec3(1.00, -0.10, 0.40), PartMat.SEAT)
        b.box("paxHeadrest", Vec3(0.61, -0.06, 0.28), Vec3(0.87, 0.14, 0.38), PartMat.SEAT)
        b.box("rearCushion", Vec3(-0.45, -0.78, 0.75), Vec3(1.19, -0.62, 1.20), PartMat.SEAT)
        b.box("rearBack", Vec3(-0.45, -0.62, 1.15), Vec3(1.19, -0.10, 1.30), PartMat.SEAT)
        // mirror housings behind the three mirror panels (design model.mirrors), arms to the roof / doors
        b.facingBox("housing-I", Vec3(0.33, 0.12, -0.48), Vec3(0.29, 0.10, 0.025), Vec3.ZERO, 0.016, PartMat.MIRROR_BACK)
        b.bar("arm-I", Vec3(0.33, 0.16, -0.50), Vec3(0.30, 0.21, -0.52), 0.02, PartMat.MIRROR_BACK)
        b.facingBox("housing-near", Vec3(-0.70, -0.17, -0.62), Vec3(0.21, 0.14, 0.06), Vec3.ZERO, 0.033, PartMat.MIRROR_BACK)
        b.bar("arm-near", Vec3(-0.66, -0.20, -0.66), Vec3(-0.54, -0.22, -0.74), 0.03, PartMat.MIRROR_BACK)
        b.facingBox("housing-far", Vec3(1.44, -0.17, -0.62), Vec3(0.21, 0.14, 0.06), Vec3.ZERO, 0.033, PartMat.MIRROR_BACK)
        b.bar("arm-far", Vec3(1.40, -0.20, -0.66), Vec3(1.28, -0.22, -0.74), 0.03, PartMat.MIRROR_BACK)
        // hood (design: z −1.10 … −2.00, y −0.42 … −0.50, colour random of 4)
        b.slab("hood", -0.54, 1.28, -0.42, -1.10, -0.50, -2.00, 0.05, PartMat.HOOD)
        b.box("fenderNear", Vec3(-0.56, -0.95, -2.02), Vec3(-0.50, -0.46, -1.08), PartMat.HOOD)
        b.box("fenderFar", Vec3(1.24, -0.95, -2.02), Vec3(1.30, -0.46, -1.08), PartMat.HOOD)
        val cabin = Cabin(Seat.DRIVER, side)
        val doors =
            listOf(
                door(m, "door-nearFront", Dir.LEFT.physical(side), cabin.doorHinge, -0.54, -0.50, -0.95, 0.20, cabin.doorHandle, opens = true),
                door(m, "door-farFront", Dir.RIGHT.physical(side), Vec3(1.28 * m, -0.35, -0.95), 1.24, 1.28, -0.95, 0.20, null, opens = false),
                door(m, "door-nearRear", Dir.LEFT.physical(side), Vec3(-0.54 * m, -0.35, 0.20), -0.54, -0.50, 0.20, 1.10, null, opens = false),
                door(m, "door-farRear", Dir.RIGHT.physical(side), Vec3(1.28 * m, -0.35, 0.20), 1.24, 1.28, 0.20, 1.10, null, opens = false),
            )
        return CabinLayout(Seat.DRIVER, side, b.list, doors)
    }

    /** Rear right passenger seat cabin (design cabinRear), eye frame of the rear seat. */
    private fun rear(side: TrafficSide): CabinLayout {
        val m = side.m
        val b = Parts(m)
        b.box("floor", Vec3(-1.24, -1.03, -1.70), Vec3(0.50, -1.00, 0.70), PartMat.TRIM)
        b.box("roof", Vec3(-1.27, 0.20, -1.12), Vec3(0.53, 0.24, 0.73), PartMat.HEADLINER)
        b.slab("dashTop", -1.24, 0.50, -0.30, -1.40, -0.42, -1.70, 0.05, PartMat.DASH)
        b.box("dashFace", Vec3(-1.24, -0.66, -1.45), Vec3(0.50, -0.30, -1.395), PartMat.DASH)
        b.box("console", Vec3(-0.49, -1.00, -1.43), Vec3(-0.25, -0.62, -0.40), PartMat.TRIM)
        wheel(b, Vec3(-0.74, -0.36, -1.07), Vec3(-0.74, -0.48, -1.37))
        b.bar("pillarA-left", Vec3(-1.24, -0.42, -1.70), Vec3(-1.14, 0.20, -1.10), 0.08, PartMat.TRIM)
        b.bar("pillarA-right", Vec3(0.44, -0.42, -1.70), Vec3(0.32, 0.20, -1.10), 0.08, PartMat.TRIM)
        b.box("pillarB-left", Vec3(-1.28, -0.22, -0.55), Vec3(-1.23, 0.20, -0.45), PartMat.TRIM)
        b.box("pillarB-right", Vec3(0.49, -0.22, -0.55), Vec3(0.54, 0.20, -0.45), PartMat.TRIM)
        b.box("pillarC-left", Vec3(-1.28, -0.22, 0.45), Vec3(-1.23, 0.20, 0.70), PartMat.TRIM)
        b.box("pillarC-right", Vec3(0.49, -0.22, 0.45), Vec3(0.54, 0.20, 0.70), PartMat.TRIM)
        b.box("rearShelf", Vec3(-1.24, -0.30, 0.42), Vec3(0.50, -0.22, 0.73), PartMat.TRIM)
        b.box("rearHeader", Vec3(-1.24, 0.17, 0.68), Vec3(0.50, 0.21, 0.74), PartMat.HEADLINER)
        b.box("rearBelow", Vec3(-1.24, -1.00, 0.68), Vec3(0.50, -0.30, 0.73), PartMat.TRIM)
        // front seats: the passenger's right ahead (back face z −0.70, x ±0.25, headrest top −0.02), the driver's beside
        for ((n, cx) in listOf("pax" to 0.0, "drv" to -0.74)) {
            b.box("${n}Cushion", Vec3(cx - 0.25, -0.80, -1.30), Vec3(cx + 0.25, -0.66, -0.85), PartMat.SEAT)
            b.box("${n}Back", Vec3(cx - 0.25, -0.66, -0.90), Vec3(cx + 0.25, -0.12, -0.70), PartMat.SEAT)
            b.box("${n}Headrest", Vec3(cx - 0.13, -0.12, -0.86), Vec3(cx + 0.13, -0.02, -0.74), PartMat.SEAT)
        }
        b.box("screenBezel", Vec3(-0.21, -0.34, -0.705), Vec3(0.21, -0.07, -0.698), PartMat.DARK)
        b.box("rearCushion", Vec3(-1.20, -0.78, -0.15), Vec3(0.46, -0.62, 0.30), PartMat.SEAT)
        b.box("rearBack", Vec3(-1.20, -0.62, 0.25), Vec3(0.46, -0.10, 0.42), PartMat.SEAT)
        val drvEye = Vec3(-0.74, 0.0, -0.65)
        b.facingBox("housing-I", Vec3(-0.41, 0.12, -1.13), Vec3(0.29, 0.10, 0.025), drvEye, 0.016, PartMat.MIRROR_BACK)
        b.facingBox("housing-near", Vec3(-1.44, -0.17, -1.27), Vec3(0.21, 0.14, 0.06), drvEye, 0.033, PartMat.MIRROR_BACK)
        b.facingBox("housing-far", Vec3(0.70, -0.17, -1.27), Vec3(0.21, 0.14, 0.06), drvEye, 0.033, PartMat.MIRROR_BACK)
        b.slab("hood", -1.28, 0.54, -0.42, -1.75, -0.50, -2.65, 0.05, PartMat.HOOD)
        val cabin = Cabin(Seat.REAR, side)
        val doors =
            listOf(
                door(m, "door-rightRear", Dir.RIGHT.physical(side), cabin.doorHinge, 0.50, 0.54, -0.55, 0.60, cabin.doorHandle, opens = true),
                door(m, "door-rightFront", Dir.RIGHT.physical(side), Vec3(0.54 * m, -0.35, -1.70), 0.50, 0.54, -1.70, -0.55, null, opens = false),
                door(m, "door-leftFront", Dir.LEFT.physical(side), Vec3(-1.28 * m, -0.35, -1.70), -1.28, -1.24, -1.70, -0.55, null, opens = false),
                door(m, "door-leftRear", Dir.LEFT.physical(side), Vec3(-1.28 * m, -0.35, -0.55), -1.28, -1.24, -0.55, 0.60, null, opens = false),
            )
        return CabinLayout(Seat.REAR, side, b.list, doors)
    }

    /** Steering wheel: ring (diameter 0.36, tilted back 25°, top towards you), hub, two spokes, column to the dash. */
    private fun wheel(b: Parts, centre: Vec3, columnEnd: Vec3) {
        val c = centre.mirrorX(b.m)
        val rot = rotX(25.0)
        b.list += Part("wheel", c, Vec3(0.36, 0.36, 0.36), rot, PartMat.DARK, PartMesh.RING)
        b.list += Part("wheelHub", c, Vec3(0.10, 0.08, 0.05), rot, PartMat.DARK)
        for (s in listOf(-1.0, 1.0)) {
            val end = c + rot.rotate(Vec3(0.17 * s, -0.02, 0.0))
            val d = end - c
            b.list += Part("wheelSpoke", (c + end) * 0.5, Vec3(0.025, 0.025, d.length), fromTo(Vec3(0.0, 0.0, 1.0), d.normalized()), PartMat.DARK)
        }
        val ce = columnEnd.mirrorX(b.m)
        val d = ce - c
        b.list += Part("column", (c + ce) * 0.5, Vec3(0.06, 0.06, d.length), fromTo(Vec3(0.0, 0.0, 1.0), d.normalized()), PartMat.DARK)
    }

    /** Lower door trim below the belt line, plus armrest and (for the opening door) the inner handle. */
    private fun door(m: Double, name: String, side: Dir, hinge: Vec3, xa: Double, xb: Double, z0: Double, z1: Double, handle: Vec3?, opens: Boolean): DoorSpec {
        val p = Parts(m)
        p.box("$name-panel", Vec3(xa, -1.00, z0), Vec3(xb, -0.22, z1), PartMat.TRIM)
        val innerX = if (abs(xa) < abs(xb)) xa else xb
        val armOut = innerX + (if (innerX < 0) 0.07 else -0.07)
        p.box("$name-arm", Vec3(min(innerX, armOut), -0.48, z0 + 0.35), Vec3(max(innerX, armOut), -0.42, z1 - 0.15), PartMat.SEAT)
        if (handle != null) {
            // handle is already physical: undo the mirror of Parts.box by passing design-frame numbers
            val h = Vec3(handle.x * m, handle.y, handle.z)
            p.box("$name-handle", h - Vec3(0.03, 0.02, 0.05), h + Vec3(0.03, 0.02, 0.05), PartMat.HANDLE)
        }
        return DoorSpec(name, side, hinge, p.list, opens)
    }

    // ------------------------------------------------------------------------------------------------- sight cone

    /**
     * D20: true when [part] (as an oriented box; the steering-wheel ring as a thin disc) cuts any line from [eye] to
     * the panel rectangle (centre, corners, edge middles) of a panel at [centre] with [rotation], [w] × [h] metres.
     */
    fun inSightCone(part: Part, eye: Vec3, centre: Vec3, rotation: Quat, w: Double, h: Double): Boolean {
        val half = if (part.mesh == PartMesh.RING) Vec3(part.size.x / 2, part.size.y / 2, 0.02) else part.size * 0.5
        val inv = part.rotation.conjugate()
        val box = OccluderBox(-half, half)
        val e = inv.rotate(eye - part.centre)
        for (sx in listOf(-0.5, 0.0, 0.5)) for (sy in listOf(-0.5, 0.0, 0.5)) {
            val p = centre + rotation.rotate(Vec3(sx * w, sy * h, 0.0))
            val end = p + (eye - p).normalized() * 0.02
            if (Cabin.segmentHitsBox(e, inv.rotate(end - part.centre), box)) return true
        }
        return false
    }

    /** Where the gaze dot lands: the cabin's inner surface when the ray hits trim, else 6 m out (entities3d gazeDot). */
    fun gazePoint(cabin: Cabin, origin: Vec3, dir: Vec3): Vec3 {
        val far = origin + dir * 6.0
        if (cabin.clearLine(origin, far)) return far
        // walk out until the line is blocked: the first blocked point is on (or just past) the trim
        var lo = 0.05
        var hi = 6.0
        if (!cabin.clearLine(origin, origin + dir * lo)) return origin + dir * lo
        repeat(16) {
            val mid = (lo + hi) / 2
            if (cabin.clearLine(origin, origin + dir * mid)) lo = mid else hi = mid
        }
        return origin + dir * (lo * 0.98)
    }
}
