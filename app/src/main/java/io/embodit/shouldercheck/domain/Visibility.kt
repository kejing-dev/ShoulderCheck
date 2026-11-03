package io.embodit.shouldercheck.domain

import kotlin.math.abs

/**
 * Visibility of road users from a seat (design `model.judging.visibility`): A / B / C pillar occlusion, window
 * regions, the headset view ±50° (±40° judge margin for "eyes can reach it"). Generator, judge and tests share it.
 */
object Visibility {
    /** Eye-movement margin used by the judge / generator: an object within ±40° of the head direction can be seen. */
    const val JUDGE_HALF_FOV = 40.0

    /** Vertical half field of view used with the horizontal limits. */
    const val VERTICAL_HALF_FOV = 50.0

    /** Sample point of a box with its direction from the eye and whether the cabin blocks it. */
    data class Part(val point: Vec3, val yawDeg: Double, val pitchDeg: Double, val unoccluded: Boolean)

    /** All sample points of [box] as seen from [eye] of [cabin]. */
    fun parts(box: Box, cabin: Cabin, eye: Vec3 = cabin.eye): List<Part> =
        box.samplePoints().map { p ->
            val d = p - eye
            Part(p, Angles.yawOf(d), Angles.pitchOf(d), cabin.clearLine(eye, p))
        }

    /**
     * `visibleParts(obj, seat, headYaw)`: the un-occluded sample points of [box] within [halfFovDeg] of the head yaw
     * (and ±50° vertically of [headPitch]). Empty = nothing of it can be seen right now.
     */
    fun visibleParts(box: Box, cabin: Cabin, headYaw: Double, halfFovDeg: Double = BlindSpot.HMD_HALF_FOV, headPitch: Double = 0.0, eye: Vec3 = cabin.eye): List<Vec3> =
        parts(box, cabin, eye)
            .filter { it.unoccluded && abs(Angles.wrap(it.yawDeg - headYaw)) <= halfFovDeg && abs(it.pitchDeg - headPitch) <= VERTICAL_HALF_FOV }
            .map { it.point }

    fun anyInView(box: Box, cabin: Cabin, headYaw: Double, halfFovDeg: Double, headPitch: Double = 0.0, eye: Vec3 = cabin.eye): Boolean {
        for (p in box.samplePoints()) {
            val d = p - eye
            if (abs(Angles.wrap(Angles.yawOf(d) - headYaw)) > halfFovDeg) continue
            if (abs(Angles.pitchOf(d) - headPitch) > VERTICAL_HALF_FOV) continue
            if (cabin.clearLine(eye, p)) return true
        }
        return false
    }

    /**
     * True when some un-occluded part of [box] has an azimuth inside [minYaw] … [maxYaw] (degrees, + right; the
     * band may extend past ±180 to wrap round behind you). The generator's "max + 40° minus pillars" band is
     * `inBand(box, cabin, −(maxL + 40), maxR + 40)`.
     */
    fun inBand(box: Box, cabin: Cabin, minYaw: Double, maxYaw: Double): Boolean {
        for (p in box.samplePoints()) {
            val d = p - cabin.eye
            val yaw = Angles.yawOf(d)
            val inside = (yaw in minYaw..maxYaw) || (yaw + 360 in minYaw..maxYaw) || (yaw - 360 in minYaw..maxYaw)
            if (!inside || abs(Angles.pitchOf(d)) > VERTICAL_HALF_FOV) continue
            if (cabin.clearLine(cabin.eye, p)) return true
        }
        return false
    }

    /** What the user can reach with head + eyes for a turn range: band or (driver) any mirror. */
    fun reachable(box: Box, cabin: Cabin, range: TurnRange, aims: MirrorAims): Boolean =
        inBand(box, cabin, -(range.maxLeft + JUDGE_HALF_FOV), range.maxRight + JUDGE_HALF_FOV) ||
            MirrorModel.anyMirrorSees(box, cabin, aims) != null
}
