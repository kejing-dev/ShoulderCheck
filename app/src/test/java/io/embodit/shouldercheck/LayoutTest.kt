package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.GlbAlpha
import io.embodit.shouldercheck.domain.Layout
import io.embodit.shouldercheck.domain.MiniJson
import io.embodit.shouldercheck.domain.ModelCalibration
import io.embodit.shouldercheck.domain.MiniJson.num
import io.embodit.shouldercheck.domain.Placement
import io.embodit.shouldercheck.domain.Pose
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Scene layout rules: four-direction seating (D9 / D10), panels fixed to the car, D20 sight cone, D25 as shipped. */
class LayoutTest {
    /** The panel's front normal (+Z of its rotation) in the world. */
    private fun normal(q: Quat) = q.rotate(Vec3(0.0, 0.0, 1.0))

    @Test
    fun fourDirectionSeatingFacesTheUser() {
        // the user sits facing east, south, west, north (with a sloppy head pitch / roll); P1 / P2 must face them
        for (yaw in listOf(90.0, 180.0, -90.0, 0.0)) {
            for ((pitch, roll) in listOf(0.0 to 0.0, -30.0 to 0.0, 30.0 to 0.0, 10.0 to 20.0)) {
                val head = Pose(Vec3(1.0, 1.3, -2.0), Quat.yawPitchRoll(yaw, pitch, roll))
                val car = Placement.seatFrame(head)
                for (side in TrafficSide.values()) {
                    for (place in listOf(Layout.p1(Seat.DRIVER, side), Layout.p2(Seat.DRIVER, side), Layout.p1Tall(Seat.DRIVER, side))) {
                        // exactly what CarScene writes: car root (seatFrame) x panel holder (place.rotation)
                        val world = car.rotation * place.rotation
                        val pos = Placement.toWorld(car, place.centre)
                        val toUser = head.position - pos
                        val n = normal(world)
                        assertTrue("yaw=$yaw p=$pitch r=$roll side=$side", kotlin.math.abs(Angles.wrap(Angles.yawOf(n) - Angles.yawOf(toUser))) < 10.0)
                        assertTrue("upright", Angles.between(world.rotate(Vec3.UP), Vec3.UP) < 1.0)
                        assertEquals(0.0, world.rotate(Vec3.RIGHT).y, 1e-9)
                        assertTrue(place.scale > 0)
                    }
                }
            }
        }
    }

    /** The runtime panel poses (Layout -> CarScene, no other rotation) obey the yaw-only placeUpright rule. */
    @Test
    fun runtimePanelPosesAreUprightBelowEyeInBand() {
        for (side in TrafficSide.values()) {
            for (place in listOf(Layout.p1(Seat.DRIVER, side), Layout.p2(Seat.DRIVER, side), Layout.p1Tall(Seat.DRIVER, side))) {
                assertTrue(Angles.between(place.rotation.rotate(Vec3.UP), Vec3.UP) < 1.0)
                val drop = -Angles.pitchOf(place.centre)
                assertTrue("drop $drop", drop in 10.0..20.0)
                assertTrue(place.centre.length in 0.8..1.2)
                // the panel front (+Z) points back at the eye horizontally
                assertEquals(0.0, Angles.wrap(Angles.yawOf(place.centre) + 180.0 - Angles.yawOf(normal(place.rotation))), 0.5)
            }
            // seat-back screen: upright, 16 deg below; 0.72 m is the design's own seat-back distance (D12 note)
            for (place in listOf(Layout.p1(Seat.REAR, side), Layout.p2(Seat.REAR, side))) {
                assertTrue(Angles.between(place.rotation.rotate(Vec3.UP), Vec3.UP) < 1e-6)
                assertEquals(0.718, place.centre.length, 0.005)
                assertTrue(-Angles.pitchOf(place.centre) in 10.0..20.0)
            }
            for (seat in Seat.values()) for (d in Dir.values()) {
                val a = Layout.ang(seat, side, d)
                assertTrue(Angles.between(a.rotation.rotate(Vec3.UP), Vec3.UP) < 1e-6)
            }
        }
    }

    @Test
    fun panelsSitBelowEyeLevelAtDesignDistances() {
        for (side in TrafficSide.values()) {
            val p1 = Layout.p1(Seat.DRIVER, side).centre
            val p2 = Layout.p2(Seat.DRIVER, side).centre
            assertEquals(1.05, p1.length, 1e-9)
            assertEquals(-10.0, Angles.pitchOf(p1), 1e-6)
            assertEquals(6.0 * side.m, Angles.yawOf(p1), 1e-6)
            assertEquals(1.10, p2.length, 1e-9)
            assertEquals(-15.0, Angles.pitchOf(p2), 1e-6)
            // tall P1, upright: its lower edge stays above the dashboard line (-21.8 deg)
            val tall = Layout.p1Tall(Seat.DRIVER, side).centre
            val lowerY = tall.y - 600 * 0.0008 * 0.8 / 2
            assertTrue(Math.toDegrees(kotlin.math.atan2(-lowerY, kotlin.math.hypot(tall.x, tall.z))) < 21.0)
        }
    }

    @Test
    fun noCabinPartInTheDefaultSightConesAndNoNegativeScale() {
        for (side in TrafficSide.values()) {
            for (seat in Seat.values()) {
                val cab = Layout.cabin(seat, side)
                for (p in cab.parts + cab.doors.flatMap { it.parts }) {
                    assertTrue("${p.name} size", p.size.x > 0 && p.size.y > 0 && p.size.z > 0)
                }
                if (seat != Seat.DRIVER) continue
                // P1 800 × 520 dp, P2 760 × 176 dp, tall P1 800 × 600 dp (0.8 mm per dp at scale 1)
                for ((place, dims) in listOf(Layout.p1(seat, side) to (800 to 520), Layout.p2(seat, side) to (760 to 176), Layout.p1Tall(seat, side) to (800 to 600))) {
                    val rot = place.rotation
                    for (p in cab.parts) {
                        if (p.name == "hood" || p.name.startsWith("fender")) continue
                        assertTrue("${p.name} in cone ($side ${dims.first}x${dims.second})", !Layout.inSightCone(p, Vec3.ZERO, place.centre, rot, dims.first * 0.0008 * place.scale, dims.second * 0.0008 * place.scale))
                    }
                }
            }
        }
        // a box right on the line of sight is detected
        val blocker = io.embodit.shouldercheck.domain.Part("blocker", Vec3(0.0, -0.1, -0.5), Vec3(0.2, 0.2, 0.05), mat = io.embodit.shouldercheck.domain.PartMat.TRIM)
        val p1 = Layout.p1(Seat.DRIVER, TrafficSide.RIGHT_HAND)
        assertTrue(Layout.inSightCone(blocker, Vec3.ZERO, p1.centre, p1.rotation, 0.64, 0.33))
    }

    @Test
    fun angReadoutsSitOnTheirSideFacingYou() {
        for (side in TrafficSide.values()) for (seat in Seat.values()) {
            for (d in Dir.values()) {
                val a = Layout.ang(seat, side, d)
                assertEquals(d.sign.toDouble(), Math.signum(a.centre.x), 0.0)
                assertEquals(95.0, kotlin.math.abs(Angles.yawOf(a.centre)), 0.5)
                // angular size equals the design's 0.6 at 0.55 m
                assertEquals(0.6 / 0.55, a.scale / a.centre.length, 1e-9)
            }
        }
    }

    @Test
    fun d25_scaleBalancedAsShipped() {
        val file = listOf(File("src/main/assets/models/calibration.json"), File("app/src/main/assets/models/calibration.json")).firstOrNull { it.exists() } ?: return fail("calibration.json not found")
        val text = file.readText()
        val root = MiniJson.parse(text) as Map<*, *>
        val all = ModelCalibration.parseAll(text)
        for (kind in ActorKind.values()) {
            val role = kind.model ?: continue
            val entry = root[role] as Map<*, *>
            val c = all.getValue(role).withScale(entry.num("scaleBalanced")!!)
            val f = ModelCalibration.axisVector(entry["forwardAxis"] as String)!!
            assertTrue("$role centre ±1 cm", c.bottomCentre().length < 0.01)
            assertTrue("$role heading < 2°", c.forwardError(f) < 2.0)
            val size = c.size()
            assertTrue("$role length < 10 %", ModelCalibration.relError(size.z, kind.length) < 0.10)
            assertTrue("$role height < 10 %", ModelCalibration.relError(size.y, kind.height) < 0.10)
            assertEquals(0.0, c.calibratedBounds().first.y, 0.01)
        }
    }

    // -------------------------------------------------------------------------------------- GLB OPAQUE guard

    private fun glb(json: String): ByteArray {
        var j = json.toByteArray()
        while (j.size % 4 != 0) j += ' '.code.toByte()
        val bin = byteArrayOf(1, 2, 3, 4)
        val total = 12 + 8 + j.size + 8 + bin.size
        val b = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46546C67).putInt(2).putInt(total).putInt(j.size).putInt(0x4E4F534A).put(j)
        b.putInt(bin.size).putInt(0x004E4942).put(bin)
        return b.array()
    }

    @Test
    fun glbAlphaGuardForcesOpaque() {
        val src = glb("{\"asset\":{\"version\":\"2.0\"},\"materials\":[{\"name\":\"a\"},{\"name\":\"b\",\"alphaMode\":\"BLEND\"},{\"name\":\"c\",\"alphaMode\":\"MASK\",\"alphaCutoff\":0.5}]}")
        assertEquals(listOf("OPAQUE", "BLEND", "MASK"), GlbAlpha.alphaModes(src))
        val (out, report) = GlbAlpha.forceOpaque(src)
        assertEquals(2, report.forced)
        assertEquals(listOf("OPAQUE", "OPAQUE", "OPAQUE"), GlbAlpha.alphaModes(out))
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(out.size, bb.getInt(8)) // header length rewritten
        assertEquals(0, bb.getInt(12) % 4) // JSON chunk padded to 4 bytes
        assertFalse(String(out, 20, bb.getInt(12)).contains("alphaCutoff"))
        assertEquals(listOf<Byte>(1, 2, 3, 4), out.takeLast(4)) // BIN chunk carried over byte for byte
        val ok = glb("{\"asset\":{\"version\":\"2.0\"},\"materials\":[{\"name\":\"a\",\"alphaMode\":\"OPAQUE\"}]}")
        assertTrue(GlbAlpha.forceOpaque(ok).first === ok) // already opaque: the same bytes
    }

    @Test
    fun shippedGlbsAreOpaque() {
        val dir = listOf(File("src/main/assets/models"), File("app/src/main/assets/models")).firstOrNull { it.isDirectory } ?: return fail("models folder not found")
        for (role in listOf("sedan", "suv", "ebike", "bike", "moto")) {
            val (_, report) = GlbAlpha.forceOpaque(File(dir, "$role.glb").readBytes())
            assertTrue("$role has materials", report.modes.isNotEmpty())
            assertTrue("$role ${report.modes}", report.allOpaque)
            assertEquals(0, report.forced)
        }
    }
}
