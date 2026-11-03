package io.embodit.shouldercheck.domain

import kotlin.math.cos
import kotlin.math.sin

/** Rectangle in the xz plane (physical x). */
data class Slot(val xMin: Double, val xMax: Double, val zMin: Double, val zMax: Double) {
    fun overlaps(box: Box): Boolean {
        val fp = box.footprint()
        val minX = fp.minOf { it.x }
        val maxX = fp.maxOf { it.x }
        val minZ = fp.minOf { it.z }
        val maxZ = fp.maxOf { it.z }
        return maxX > xMin && minX < xMax && maxZ > zMin && minZ < zMax
    }
}

/**
 * Lane change / pull-out / right turn (design `model.laneChangeModel`). Your car never moves; on "execute" a ghost car
 * drives the route for [DURATION] s. Conflict = any road user, moving at its CURRENT speed, overlapping the slot (your
 * car's place in the target lane, ±[SLOT_MARGIN] m front and back) at any moment of the next [DURATION] s.
 */
object LaneChangeModel {
    const val DURATION = 3.0
    const val SLOT_MARGIN = 2.0

    /** Extra length of the right-turn slot ahead of the car (the turn path crosses the bike lane in front of you). */
    const val TURN_AHEAD = 2.0

    /** The conflict slot of a lane-type scene. */
    fun slot(situation: Situation, side: TrafficSide, cabin: Cabin, road: RoadLayout): Slot {
        val dir = situation.dir(side)
        val zMin = cabin.egoFrontZ - SLOT_MARGIN - (if (situation == Situation.RIGHT_TURN) TURN_AHEAD else 0.0)
        val zMax = cabin.egoRearZ + SLOT_MARGIN
        return when (situation) {
            Situation.RIGHT_TURN -> {
                val bike = road.strips.first { it.type == StripType.BIKE_LANE }
                Slot(bike.xMin, bike.xMax, zMin, zMax)
            }
            else -> {
                val cx = road.hazardX(ActorKind.SEDAN, dir)
                Slot(cx - Road.LANE_WIDTH / 2, cx + Road.LANE_WIDTH / 2, zMin, zMax)
            }
        }
    }

    /**
     * Predicted conflict of [actor] for an execute at [tExec]: seconds after [tExec] of the first overlap with [slot]
     * (0 … [DURATION]), extrapolating its position at the current speed; null = no conflict.
     */
    fun predictConflict(actor: Actor, tExec: Double, slot: Slot, step: Double = 0.02): Double? {
        val z0 = actor.centreZ(tExec)
        val vz = actor.velocityZ(tExec)
        var dt = 0.0
        while (dt <= DURATION + 1e-9) {
            val box = Box(Vec3(actor.x, Road.ROAD_Y, z0 + vz * dt), actor.kind.length, actor.kind.width, actor.kind.height)
            if (slot.overlaps(box)) return dt
            dt += step
        }
        return null
    }
}

/** Ghost car pose: box-bottom centre and heading (app yaw, + right). */
data class GhostPose(val position: Vec3, val headingDeg: Double)

/**
 * The 30 % ghost car's route over [LaneChangeModel.DURATION] s (design `laneChangeModel.execute`, D14): it starts with
 * its rear at the hood's front edge (z −2.0) and drives away — it never sweeps through your head.
 */
object GhostRoute {
    const val START_REAR_Z = -2.0
    const val SPEED = 5.0
    const val TURN_RADIUS = 7.0

    fun pose(situation: Situation, side: TrafficSide, road: RoadLayout, t: Double): GhostPose {
        val u = (t / LaneChangeModel.DURATION).coerceIn(0.0, 1.0)
        val dir = situation.dir(side)
        val len = ActorKind.SEDAN.length
        val x0 = road.egoX
        val z0 = START_REAR_Z - len / 2
        return when (situation) {
            Situation.RIGHT_TURN -> {
                val phi = u * Math.PI / 2
                val x = x0 + dir.sign * TURN_RADIUS * (1 - cos(phi))
                val z = z0 - TURN_RADIUS * sin(phi)
                GhostPose(Vec3(x, Road.ROAD_Y, z), dir.sign * Math.toDegrees(phi))
            }
            else -> {
                val target = road.hazardX(ActorKind.SEDAN, dir)
                val s = u * u * (3 - 2 * u)
                val ds = 6 * u * (1 - u) / LaneChangeModel.DURATION
                val x = x0 + (target - x0) * s
                val z = z0 - SPEED * t.coerceIn(0.0, LaneChangeModel.DURATION)
                val heading = Math.toDegrees(kotlin.math.atan2((target - x0) * ds, SPEED))
                GhostPose(Vec3(x, Road.ROAD_Y, z), heading)
            }
        }
    }
}
