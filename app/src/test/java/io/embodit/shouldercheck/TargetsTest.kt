package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.DoorModel
import io.embodit.shouldercheck.domain.DoorTargets
import io.embodit.shouldercheck.domain.HudLayout
import io.embodit.shouldercheck.domain.Layout
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.Target
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.domain.hotspotBox
import io.embodit.shouldercheck.domain.targetOfName
import io.embodit.shouldercheck.ui.input.parseLoggedPath
import io.embodit.shouldercheck.ui.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D50 (user on the headset 2026-11-17: 「门 push 不了，手柄、眼手交互都是」): every 3D target resolves from a real hit
 * path (the deepest mesh up to the holder, board-input 命中回放), the door opens with ONE tap, and the controller-ray
 * fallback only fires when the ray points at the target.
 */
class TargetsTest {
    /** Hit paths as the board-input layer logs them (`path=` field): child mesh first, then its parents. */
    @Test
    fun everyHitPathResolvesToItsTarget() {
        val cases =
            mapOf(
                "btn:go/body < btn:go < car < stageRoot" to Target.GO,
                "btn:go/label < btn:go < car" to Target.GO,
                "btn:go/ring7 < btn:go < car" to Target.GO,
                "door:handle/door-nearFront-handle < door:handle < door-nearFront < cabin < car" to Target.DOOR,
                "door:handle/door-nearFront-panel < door:handle < door-nearFront < cabin" to Target.DOOR,
                "pause:chip/chip < pause:chip < car" to Target.PAUSE,
                "ask:on/body < ask:on < car" to Target.ASK_ON,
                "ask:off/label < ask:off < car" to Target.ASK_OFF,
            )
        for ((path, want) in cases) assertEquals(path, want, resolvePath(parseLoggedPath(path), ::targetOfName))
        // the cabin, mirrors, road users and the sky are not targets
        for (path in listOf("dashTop < cabin < car", "panel:mirL < holder:mirL < car", "actor:sedan < car", "skyDome < car", "door-farFront-panel < door-farFront < cabin")) {
            assertNull(path, resolvePath(parseLoggedPath(path), ::targetOfName))
        }
        for (t in Target.values()) assertEquals(t, Target.parse(t.id))
    }

    @Test
    fun oneTapOpensTheDoor() {
        // the door is time-based: a tap at t0 → 35° one second later, no push, no grip
        assertEquals(0.0, DoorModel.angleAt(null), 1e-9)
        assertEquals(0.0, DoorModel.angleAt(0.0), 1e-9)
        assertTrue(DoorModel.angleAt(0.5) in 10.0..25.0)
        assertEquals(DoorModel.OPEN_DEG, DoorModel.angleAt(1.0), 1e-9)
        assertEquals(DoorModel.OPEN_DEG, DoorModel.angleAt(5.0), 1e-9)
        // close call: the door stops at 12°
        assertEquals(DoorModel.STOP_DEG, DoorModel.angleAt(1.0, DoorModel.STOP_DEG), 1e-9)
        val cabin = Cabin(Seat.DRIVER, TrafficSide.RIGHT_HAND)
        assertEquals(0.66, DoorModel.edgeOut(cabin, DoorModel.OPEN_DEG), 0.01)
    }

    @Test
    fun doorClickBoxCoversTheHandleAndIsBigEnough() {
        for (side in TrafficSide.values()) {
            val layout = Layout.cabin(Seat.DRIVER, side)
            val box = DoorTargets.box(layout)!!
            val handle = Cabin(Seat.DRIVER, side).doorHandle
            assertTrue("$side handle inside", handle.x in box.min.x..box.max.x && handle.y in box.min.y..box.max.y && handle.z in box.min.z..box.max.z)
            val h = HudLayout.hotspots(Cabin(Seat.DRIVER, side), layout).first { it.target == Target.DOOR }
            assertTrue(HudLayout.visualAngle(h) >= 3.0)
        }
    }

    @Test
    fun controllerRayFallbackHitsOnlyWhenPointedAtTheTarget() {
        val cabin = Cabin(Seat.DRIVER, TrafficSide.RIGHT_HAND)
        val spots = HudLayout.hotspots(cabin, Layout.cabin(Seat.DRIVER, TrafficSide.RIGHT_HAND))
        val hand = Vec3(0.18, -0.45, -0.30) // right controller, resting in the lap
        for (h in spots) {
            val toward = (h.centre - hand).normalized()
            assertNotNull("${h.target} pointed at", DoorTargets.rayHit(hand, toward, hotspotBox(h)))
            val away = Vec3(-toward.x, toward.y, -toward.z)
            assertNull("${h.target} pointed away", DoorTargets.rayHit(hand, away, hotspotBox(h)))
        }
        // pointing at 「走」 does not hit 「暂停」 (they are apart)
        val go = spots.first { it.target == Target.GO }
        val pause = spots.first { it.target == Target.PAUSE }
        assertNull(DoorTargets.rayHit(hand, (go.centre - hand).normalized(), hotspotBox(pause)))
    }
}
