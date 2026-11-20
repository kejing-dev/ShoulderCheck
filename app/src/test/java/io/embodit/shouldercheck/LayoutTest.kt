package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.HudLayout
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

/** Scene layout rules: 3D hotspots (D50 补), read-outs in view (D14), check lights, D25 as shipped, GLB opaque. */
class LayoutTest {
    /** Cabin parts have positive sizes in both traffic sides (left-hand traffic rebuilds x × m, never a negative scale). */
    @Test
    fun cabinPartsArePositiveInBothTrafficSides() {
        for (side in TrafficSide.values()) {
            val cab = Layout.cabin(Seat.DRIVER, side)
            for (p in cab.parts + cab.doors.flatMap { it.parts }) assertTrue("${p.name} size", p.size.x > 0 && p.size.y > 0 && p.size.z > 0)
        }
    }

    /**
     * D50 补 (brief): every 3D clickable is ≥ 3° from the default seat and two clickables that are on at the same time are
     * ≥ 2° apart (the 「走」 button, 「暂停」, the door handle in the door round; the two S54 buttons).
     */
    @Test
    fun hotspotsAreBigAndApart() {
        for (side in TrafficSide.values()) {
            val cabin = Cabin(Seat.DRIVER, side)
            val spots = HudLayout.hotspots(cabin, Layout.cabin(Seat.DRIVER, side))
            assertEquals(3, spots.size)
            for (h in spots) assertTrue("${h.target} ${HudLayout.visualAngle(h)}° ≥ 3°", HudLayout.visualAngle(h) >= 3.0)
            for (i in spots.indices) for (j in i + 1 until spots.size) {
                val g = HudLayout.gap(spots[i], spots[j])
                assertTrue("${spots[i].target}–${spots[j].target} gap $g° ≥ 2°", g >= 2.0)
            }
            val ask = HudLayout.askHotspots(side)
            for (h in ask) assertTrue(HudLayout.visualAngle(h) >= 3.0)
            assertTrue(HudLayout.gap(ask[0], ask[1]) >= 2.0)
            // design numbers: 「走」 10.4° × 5.2° at 0.77 m (collider 13.3° × 7.4°), 「暂停」 5.7°+
            assertEquals(0.77, HudLayout.GO.length, 0.01)
            assertTrue(HudLayout.visualAngle(spots.first { it.target == io.embodit.shouldercheck.domain.Target.PAUSE }) >= 5.7)
        }
    }

    /** D14 / B19: read-outs sit below the eye line and within 40° below it; the card shrinks out of the way of the road. */
    @Test
    fun readoutsBelowEyeAndInView() {
        for (p in listOf(HudLayout.GO, HudLayout.PAUSE, HudLayout.CARD_SMALL, HudLayout.COMBO, HudLayout.MINIMAP, HudLayout.MERGE_BAR)) {
            val drop = -Angles.pitchOf(p)
            assertTrue("$p drop $drop", drop in 0.0..40.0)
            assertTrue("$p yaw", kotlin.math.abs(Angles.yawOf(p)) < 35.0)
        }
        // the shrunk card leaves the road ahead free: ≥ 8° below the horizon, the big card is above the hood
        assertTrue(-Angles.pitchOf(HudLayout.CARD_SMALL) >= 8.0)
        assertTrue(-Angles.pitchOf(HudLayout.CARD_BIG) < 5.0)
        // 3D letters ≥ 0.5° (D13): small card 128 px tall line at 60 % of 0.0233 m, 0.78 m away
        val letter = 0.6 * (io.embodit.shouldercheck.scene.Hud3D.CARD_SMALL_W / 6)
        assertTrue(Math.toDegrees(2 * kotlin.math.atan(letter / 2 / HudLayout.CARD_SMALL.length)) >= 0.5)
    }

    /** The check lights sit next to the target-side mirror without covering it, both sides, both traffic sides. */
    @Test
    fun checkLightsBesideTheMirror() {
        for (side in TrafficSide.values()) {
            val cabin = Cabin(Seat.DRIVER, side)
            for (dir in Dir.values()) {
                val m = cabin.mirror(io.embodit.shouldercheck.domain.MirrorId.side(dir))!!
                val c = HudLayout.chips(cabin, dir)
                val off = Angles.between(c, m.pos)
                assertTrue("$side $dir chips $off° from the mirror", off in 3.0..12.0)
                assertTrue("between you and the mirror", kotlin.math.abs(c.x) <= kotlin.math.abs(m.pos.x) && c.length < m.pos.length)
                assertTrue(c.length >= 0.55)
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
            val role = kind.model
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
        for (role in listOf("sedan", "suv", "ebike", "bike", "moto", "tree")) {
            val (_, report) = GlbAlpha.forceOpaque(File(dir, "$role.glb").readBytes())
            assertTrue("$role has materials", report.modes.isNotEmpty())
            assertTrue("$role ${report.modes}", report.allOpaque)
            assertEquals(0, report.forced)
        }
    }
}
