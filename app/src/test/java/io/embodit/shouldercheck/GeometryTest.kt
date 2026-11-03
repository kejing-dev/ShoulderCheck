package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.Actor
import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.ActorRole
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.BlindSpot
import io.embodit.shouldercheck.domain.Box
import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.DoorModel
import io.embodit.shouldercheck.domain.GhostRoute
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.HazardSpec
import io.embodit.shouldercheck.domain.Key
import io.embodit.shouldercheck.domain.LaneChangeModel
import io.embodit.shouldercheck.domain.MirrorAims
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.MirrorModel
import io.embodit.shouldercheck.domain.Placement
import io.embodit.shouldercheck.domain.Pose
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Road
import io.embodit.shouldercheck.domain.RoadLayout
import io.embodit.shouldercheck.domain.Scenes
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.domain.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cabin / mirror / door / lane-change geometry: U11, U12, D9 placeUpright, design numbers. */
class GeometryTest {
    private val driver = Cabin(Seat.DRIVER, TrafficSide.RIGHT_HAND)
    private val rear = Cabin(Seat.REAR, TrafficSide.RIGHT_HAND)
    private val road = RoadLayout(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)

    /** A sedan in the [dir] lane with its rear end [rearZ] m behind the eye. */
    private fun sedan(dir: Dir, rearZ: Double) = Box(Vec3(road.adjacentLaneX(dir), Road.ROAD_Y, rearZ - ActorKind.SEDAN.length / 2), ActorKind.SEDAN.length, ActorKind.SEDAN.width, ActorKind.SEDAN.height)

    @Test
    fun mirrorsMatchDesignNumbers() {
        val i = driver.mirror(MirrorId.INNER)!!
        val l = driver.mirror(MirrorId.LEFT)!!
        val r = driver.mirror(MirrorId.RIGHT)!!
        assertEquals(34.5, i.yawDeg, 0.2)
        assertEquals(11.6, i.pitchDeg, 0.2)
        assertEquals(0.60, i.distance, 0.01)
        assertEquals(-48.5, l.yawDeg, 0.2)
        assertEquals(-10.3, l.pitchDeg, 0.2)
        assertEquals(0.95, l.distance, 0.01)
        assertEquals(66.7, r.yawDeg, 0.2)
        assertEquals(-6.2, r.pitchDeg, 0.2)
        assertEquals(1.58, r.distance, 0.01)
        // default aim 5°: view from 5° inward to 15° outward
        val e = MirrorModel.virtualEye(l, Vec3.ZERO, 5.0)
        val centre = e + MirrorModel.aimDirection(l, 5.0) * 10.0
        val hit = MirrorModel.project(centre, l, Vec3.ZERO, 5.0)!!
        assertEquals(0.5, hit.u, 1e-6)
        assertEquals(0.5, hit.v, 1e-6)
        assertEquals(5.0, MirrorModel.outwardAngle(centre, l, 5.0), 1e-6)
        // E′ sits behind the glass at the mirror distance
        assertEquals(l.distance, e.distanceTo(l.pos), 1e-6)
        assertEquals(10.0, l.fovH / 2, 1e-9)
        assertTrue(driver.mirrors.size == 3)
        assertTrue(rear.mirrors.isEmpty())
    }

    @Test
    fun pillarsAndWindowsFromDesignAngles() {
        fun seen(c: Cabin, yaw: Double, pitch: Double = 0.0, dist: Double = 6.0) = c.clearLine(c.eye, Angles.dir(yaw, pitch) * dist)
        // driver: left A pillar at ~−33° mid-height, B pillar ~−106°, side windows either side of it
        assertFalse(seen(driver, Angles.yawOf(Vec3(-0.45, 0.0, -0.75)), Angles.pitchOf(Vec3(-0.45, -0.11, -0.75))))
        assertFalse(seen(driver, -106.0))
        assertTrue(seen(driver, -90.0))
        assertTrue(seen(driver, -125.0))
        assertTrue(seen(driver, 0.0))
        assertFalse(seen(driver, 0.0, -30.0, 3.0)) // below the dashboard
        assertFalse(seen(driver, -90.0, -30.0, 3.0)) // below the beltline: door panel
        // rear seat: rear side window 90°–132°, C pillar ~132°–144°, rear window beyond
        assertTrue(seen(rear, 100.0))
        assertTrue(seen(rear, 125.0))
        assertFalse(seen(rear, 138.0))
        assertTrue(seen(rear, 150.0))
        assertTrue(seen(rear, 175.0))
        assertFalse(seen(rear, 0.0, -10.0, 3.0)) // the front seat's back
    }

    @Test
    fun u12_blindSpotFunction() {
        val aims = MirrorAims()
        val left = driver.mirror(MirrorId.LEFT)!!
        // aim 5°: rear 12 m behind → in the left mirror
        assertTrue(MirrorModel.sees(sedan(Dir.LEFT, 12.0), left, driver, 5.0))
        assertFalse(BlindSpot.isInBlindSpot(sedan(Dir.LEFT, 12.0), Dir.LEFT, aims, driver))
        // rear 4 m, nose less than 1.9 m ahead → not in the mirror, not in the forward view
        val b4 = sedan(Dir.LEFT, 4.0)
        assertTrue(b4.front.z > -1.9)
        assertFalse(MirrorModel.sees(b4, left, driver, 5.0))
        assertTrue(Visibility.visibleParts(b4, driver, 0.0).isEmpty())
        assertTrue(BlindSpot.isInBlindSpot(b4, Dir.LEFT, aims, driver))
        // aim 15°: rear 2.5 m → in the mirror again
        assertTrue(MirrorModel.sees(sedan(Dir.LEFT, 2.5), left, driver, 15.0))
        assertFalse(BlindSpot.isInBlindSpot(sedan(Dir.LEFT, 2.5), Dir.LEFT, aims.with(Dir.LEFT, 15.0), driver))
        // the forward edge: the near front corner enters the ±50° view at z ≈ −1.87
        val ahead = sedan(Dir.LEFT, -1.95 + ActorKind.SEDAN.length)
        assertFalse(Visibility.visibleParts(ahead, driver, 0.0).isEmpty())
    }

    @Test
    fun blindSpotLengthLesson1() {
        val l5 = BlindSpot.length(Dir.LEFT, 5.0, driver)
        assertEquals(4.5, l5, 0.2) // design: "约 0–4.5 m"
        val l15 = BlindSpot.length(Dir.LEFT, 15.0, driver)
        assertTrue("aim 15° shrinks it: $l15", l15 < 2.5 && l15 > 1.5)
        val r5 = BlindSpot.length(Dir.RIGHT, 5.0, driver)
        assertTrue(r5 in 3.5..5.0)
        // monotonic in the aim
        var prev = Double.MAX_VALUE
        for (a in 0..15) {
            val x = BlindSpot.length(Dir.LEFT, a.toDouble(), driver)
            assertTrue(x <= prev + 1e-9)
            prev = x
        }
    }

    @Test
    fun mirrorProjectionHasParallaxAndOrientation() {
        val left = driver.mirror(MirrorId.LEFT)!!
        val p = Vec3(-3.13, -0.6, 15.0)
        val h0 = MirrorModel.project(p, left, Vec3.ZERO, 5.0)!!
        val h1 = MirrorModel.project(p, left, Vec3(0.1, 0.0, 0.0), 5.0)!!
        assertTrue(kotlin.math.abs(h0.u - h1.u) > 1e-3) // head moves → the picture moves
        // own car body on the inner (right) side of the left mirror, the next lane further out (left)
        val body = MirrorModel.project(Vec3(driver.bodyLeft, -0.4, 2.0), left, Vec3.ZERO, 5.0)!!
        assertTrue(body.u > h0.u)
        assertTrue(body.u > 0.7)
        // the road far behind is in the lower half
        val roadFar = MirrorModel.project(Vec3(-3.13, Road.ROAD_Y, 30.0), left, Vec3.ZERO, 5.0)!!
        assertTrue(roadFar.v < 0.5)
        assertNull(MirrorModel.project(Vec3(-3.0, 0.0, -10.0), left, Vec3.ZERO, 5.0)) // ahead: not in a mirror
    }

    @Test
    fun u11_laneChangePredictiveConflict() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val slot = s.slot!!
        val len = ActorKind.SEDAN.length
        // 12 m back, closing at +6 m/s → inside the slot within 3 s
        val fast = Scenes.hazardActor("a", HazardSpec(ActorKind.SEDAN, Dir.LEFT, 12.0 + len, 6.0), s.road)
        assertNotNull(LaneChangeModel.predictConflict(fast, 0.0, slot))
        // 6 m back, falling behind at −2 m/s → never
        val slow = Scenes.hazardActor("b", HazardSpec(ActorKind.SEDAN, Dir.LEFT, 6.0 + len, -2.0), s.road)
        assertNull(LaneChangeModel.predictConflict(slow, 0.0, slot))
        // a car in the other lane never conflicts with a change to the left
        val other = Scenes.hazardActor("c", HazardSpec(ActorKind.SEDAN, Dir.RIGHT, 3.0, 0.0), s.road)
        assertNull(LaneChangeModel.predictConflict(other, 0.0, slot))
        // slot = your place in the target lane ± 2 m
        assertEquals(s.cabin.egoFrontZ - 2.0, slot.zMin, 1e-9)
        assertEquals(s.cabin.egoRearZ + 2.0, slot.zMax, 1e-9)
    }

    @Test
    fun ghostCarNeverSweepsYourHead() {
        for (sit in listOf(Situation.LANE_LEFT, Situation.LANE_RIGHT, Situation.PULL_OUT, Situation.RIGHT_TURN)) {
            for (side in TrafficSide.values()) {
                val r = RoadLayout(sit, side)
                var t = 0.0
                while (t <= 3.0) {
                    val g = GhostRoute.pose(sit, side, r, t)
                    val b = Box(g.position, ActorKind.SEDAN.length, ActorKind.SEDAN.width, ActorKind.SEDAN.height, g.headingDeg)
                    assertTrue(b.samplePoints().all { it.distanceTo(Vec3.ZERO) > 1.5 })
                    t += 0.1
                }
                val end = GhostRoute.pose(sit, side, r, 3.0)
                if (sit != Situation.RIGHT_TURN) assertEquals(r.hazardX(ActorKind.SEDAN, sit.dir(side)), end.position.x, 1e-6)
                else assertEquals(sit.dir(side).sign * 90.0, end.headingDeg, 1e-6)
            }
        }
    }

    @Test
    fun doorModelAngles() {
        val d = DoorModel(driver)
        d.update(0.1, Hand.RIGHT, 0.0)
        assertEquals(0.0, d.angle, 1e-9) // holding alone does not move the door
        repeat(30) { d.update(1.0 / 72, Hand.RIGHT, 0.03) }
        assertEquals(8.0, d.angle, 1e-9)
        assertFalse(d.executed)
        var t = 0.0
        while (d.angle < 59.99 && t < 2.0) {
            d.update(1.0 / 72, Hand.RIGHT, 0.10)
            t += 1.0 / 72
        }
        assertEquals(0.5, t, 0.05) // 8° → 60° in 0.5 s
        assertTrue(d.executed)
        assertEquals(1.0, d.edgeOut(), 0.01)
        // release mid-swing: stays
        val d2 = DoorModel(driver)
        repeat(20) { d2.update(1.0 / 72, Hand.LEFT, 0.10) }
        val a = d2.angle
        repeat(20) { d2.update(1.0 / 72, null, 0.0) }
        assertEquals(a, d2.angle, 1e-9)
        assertEquals(Hand.LEFT, d2.lastGrabHand)
        d2.close()
        assertEquals(0.0, d2.angle, 1e-9)
        // reach sphere and push along the outward normal (−X for the driver's door)
        assertTrue(DoorModel.inReach(driver, driver.doorHandle + Vec3(0.05, 0.05, 0.05)))
        assertFalse(DoorModel.inReach(driver, driver.doorHandle + Vec3(0.2, 0.0, 0.0)))
        assertEquals(0.10, DoorModel.pushDistance(driver.doorHandle, driver.doorHandle + Vec3(-0.10, 0.0, 0.0), driver.doorNormal), 1e-9)
        assertEquals(Dir.LEFT, driver.doorSide)
        assertEquals(Hand.RIGHT, driver.farHand)
        assertEquals(Hand.LEFT, rear.farHand)
        assertEquals(Hand.LEFT, Cabin(Seat.DRIVER, TrafficSide.LEFT_HAND).farHand)
    }

    @Test
    fun leftHandTrafficRegeneratesGeometryWithoutNegativeScale() {
        val l = Cabin(Seat.DRIVER, TrafficSide.LEFT_HAND)
        assertEquals(-0.37, l.carCentreX, 1e-9)
        assertTrue(l.bodyLeft < l.bodyRight)
        assertEquals(-1.28, l.bodyLeft, 1e-9)
        assertEquals(0.54, l.bodyRight, 1e-9)
        assertEquals(Dir.RIGHT, l.doorSide)
        assertEquals(48.5, l.mirror(MirrorId.RIGHT)!!.yawDeg, 0.2)
        assertEquals(-66.7, l.mirror(MirrorId.LEFT)!!.yawDeg, 0.2)
        val lr = RoadLayout(Situation.LANE_LEFT, TrafficSide.LEFT_HAND)
        assertTrue(lr.strips.zipWithNext().all { (a, b) -> a.xMax <= b.xMin + 1e-9 && a.xMin < a.xMax })
        // mirrored blind-spot length is the same
        assertEquals(BlindSpot.length(Dir.LEFT, 5.0, driver), BlindSpot.length(Dir.RIGHT, 5.0, l), 1e-6)
    }

    @Test
    fun actorScriptsInterpolateAndExtrapolate() {
        val a = Actor("x", ActorKind.BIKE, 1.0, listOf(Key(0.0, 10.0), Key(2.0, 0.0)), ActorRole.HAZARD)
        assertEquals(5.0, a.centreZ(1.0), 1e-9)
        assertEquals(-5.0, a.centreZ(3.0), 1e-9)
        assertEquals(5.0, a.relativeSpeed(1.0), 1e-6)
        val parked = Actor("p", ActorKind.SEDAN, 1.0, listOf(Key(0.0, 3.0)), ActorRole.PARKED)
        assertEquals(3.0, parked.centreZ(12.0), 1e-9)
    }

    @Test
    fun placeUprightIsYawOnly() {
        val headPos = Vec3(0.3, 1.25, -0.4)
        for ((pitch, roll) in listOf(30.0 to 0.0, -30.0 to 0.0, 0.0 to 20.0, 30.0 to 20.0, -30.0 to -20.0)) {
            val head = Pose(headPos, Quat.yawPitchRoll(37.0, pitch, roll))
            for (pose in listOf(Placement.seatFrame(head), Placement.placeUpright(head, 1.0, 15.0))) {
                assertTrue("up tilt p=$pitch r=$roll", Angles.between(pose.up, Vec3.UP) < 1.0)
                assertEquals(37.0, Angles.yawOfRotation(pose.rotation), 0.5)
                assertEquals(0.0, Angles.pitchOfRotation(pose.rotation), 1e-6)
            }
            val panel = Placement.placeUpright(head, 1.0, 15.0)
            val d = panel.position - headPos
            assertTrue(d.length in 0.8..1.2)
            val drop = -Angles.pitchOf(d)
            assertTrue(drop in 10.0..20.0)
            assertEquals(37.0, Angles.yawOf(d), 0.5)
        }
        // the car frame: eye = head position, a point 1 m ahead in the car lands 1 m ahead along the head yaw
        val head = Pose(headPos, Quat.yawPitchRoll(-20.0, 25.0, 10.0))
        val car = Placement.seatFrame(head)
        val ahead = Placement.toWorld(car, Vec3(0.0, 0.0, -1.0))
        assertEquals(headPos.y, ahead.y, 1e-9)
        assertEquals(-20.0, Angles.yawOf(ahead - headPos), 1e-6)
        assertEquals(0.0, Placement.toCar(car, ahead).x, 1e-9)
        // an unsettled first sample is invalid
        assertFalse(Pose(Vec3.ZERO, Quat.IDENTITY).isValid())
        assertTrue(head.isValid())
    }

    @Test
    fun yawConventionPlusIsRight() {
        assertEquals(90.0, Angles.yawOf(Vec3(1.0, 0.0, 0.0)), 1e-9)
        assertEquals(-90.0, Angles.yawOf(Vec3(-1.0, 0.0, 0.0)), 1e-9)
        assertEquals(180.0, Angles.yawOf(Vec3(0.0, 0.0, 1.0)), 1e-9)
        assertEquals(64.0, Angles.yawOfRotation(Quat.yaw(64.0)), 1e-9)
        val d = Angles.dir(-64.0)
        assertTrue(d.x < 0)
        assertEquals(-64.0, Angles.wrap(296.0), 1e-9)
    }
}
