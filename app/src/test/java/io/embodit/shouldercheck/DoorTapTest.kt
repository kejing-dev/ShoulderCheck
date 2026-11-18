package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.DoorTargets
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.Layout
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.ui.input.parseLoggedPath
import io.embodit.shouldercheck.ui.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D50 / board-input template: hit -> door replay, the click box, the controller-ray fallback, which hand tapped. */
class DoorTapTest {
    private val resolve = DoorTargets::resolveName

    @Test
    fun everyDoorHitResolvesToItsSeat() {
        for (seat in Seat.values()) {
            val holder = DoorTargets.holder(seat)
            // the holder itself, and each door mesh hanging below it (as the system reports a mesh hit)
            for (mesh in listOf("door-nearFront-panel", "door-nearFront-arm", "door-nearFront-handle", "door-rightRear-handle")) {
                assertEquals(seat, resolvePath(listOf(mesh, holder, "door-nearFront", "cabin:$seat", "car", "stageRoot"), resolve))
            }
            assertEquals(seat, resolvePath(listOf(holder), resolve))
        }
        // logged paths (board-input TAP / REPLAY lines) replay to the same seat
        assertEquals(Seat.DRIVER, resolvePath(parseLoggedPath("door-nearFront-handle < tap:door:DRIVER < door-nearFront < cabin:DRIVER"), resolve))
        assertEquals(Seat.REAR, resolvePath(parseLoggedPath("door-rightRear-panel < tap:door:REAR < door-rightRear < cabin:REAR"), resolve))
        // not a door: other cabin parts, the fixed doors, nonsense
        assertNull(resolvePath(listOf("door-farFront-panel", "door-farFront", "cabin:DRIVER"), resolve))
        assertNull(resolvePath(listOf("dashTop", "cabin:DRIVER"), resolve))
        assertNull(resolve("tap:door:PASSENGER"))
    }

    @Test
    fun clickBoxCoversTheHandleAndIsBigEnough() {
        for (side in TrafficSide.values()) for (seat in Seat.values()) {
            val box = DoorTargets.box(Layout.cabin(seat, side))
            assertNotNull(box)
            val cabin = Cabin(seat, side)
            val h = cabin.doorHandle
            assertTrue("$seat $side handle in box", h.x in box!!.min.x..box.max.x && h.y in box.min.y..box.max.y && h.z in box.min.z..box.max.z)
            // D17: ≥ 2° from the eye (origin of the seat frame); the door is a metre-long panel half a metre away
            val deg = DoorTargets.minVisualAngle(box, Vec3.ZERO)
            assertTrue("$seat $side ${"%.1f".format(deg)}°", deg >= 2.0)
        }
    }

    @Test
    fun controllerRayFallbackHitsTheDoorOnlyWhenPointedAtIt() {
        val side = TrafficSide.RIGHT_HAND
        val box = DoorTargets.box(Layout.cabin(Seat.DRIVER, side))!!
        val cabin = Cabin(Seat.DRIVER, side)
        // right controller in the lap, aimed across the body at the left door handle
        val ctrl = Vec3(0.20, -0.45, -0.35)
        assertNotNull(DoorTargets.rayHit(ctrl, cabin.doorHandle - ctrl, box))
        // aimed ahead through the windscreen, or at the far (right) door: no tap
        assertNull(DoorTargets.rayHit(ctrl, Vec3(0.0, 0.0, -1.0), box))
        assertNull(DoorTargets.rayHit(ctrl, Vec3(1.0, 0.0, 0.0), box))
        // pointing away from the door with the door behind the controller: no tap
        assertNull(DoorTargets.rayHit(ctrl, ctrl - cabin.doorHandle, box))
    }

    @Test
    fun tapCountsTheHandThatPressedLast() {
        val now = 10_000L
        assertEquals(Hand.LEFT to false, DoorTargets.tapHand(mapOf(Hand.RIGHT to now - 900, Hand.LEFT to now - 200), now, Hand.RIGHT))
        assertEquals(Hand.RIGHT to false, DoorTargets.tapHand(mapOf(Hand.RIGHT to now - 100), now, Hand.LEFT))
        // stale press (> 1.5 s) or none: far hand, flagged as assumed
        assertEquals(Hand.RIGHT to true, DoorTargets.tapHand(mapOf(Hand.LEFT to now - 5_000), now, Hand.RIGHT))
        assertEquals(Hand.LEFT to true, DoorTargets.tapHand(emptyMap(), now, Hand.LEFT))
    }
}
