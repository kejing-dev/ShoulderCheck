package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.RoadLayout
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.scene.MirrorFrames
import io.embodit.shouldercheck.scene.TexLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.2.2 QA / showcase fixes: texture cache key carries the locale; level 4 mirrors show the dusk, not the day. */
class V022Test {
    @Test
    fun textureKeyChangesWithLanguage() {
        val k = "pop|CLEAR|120|3|2|true|true|0.8|null|false"
        assertNotEquals(TexLabel.cacheKey("zh-CN", k), TexLabel.cacheKey("en", k))
        assertEquals(TexLabel.cacheKey("en", k), TexLabel.cacheKey("en", k))
    }

    @Test
    fun duskMirrorIsNotDaylight() {
        val cabin = Cabin(Seat.DRIVER, TrafficSide.RIGHT_HAND)
        val road = RoadLayout(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val eye = Vec3.ZERO
        for (m in cabin.mirrors) {
            val day = MirrorFrames.build(m, cabin, road, eye, 0.0, emptyList(), null, androidx.compose.ui.graphics.Color.Gray)
            val dusk = MirrorFrames.build(m, cabin, road, eye, 0.0, emptyList(), null, androidx.compose.ui.graphics.Color.Gray, dusk = true)
            assertTrue(dusk.dusk)
            assertEquals(day.polys.size, dusk.polys.size)
            for (i in day.polys.indices) {
                val a = day.polys[i].color
                val b = dusk.polys[i].color
                assertTrue("${m.id} poly $i dimmed", b.red + b.green + b.blue < a.red + a.green + a.blue || a.red + a.green + a.blue == 0f)
            }
        }
        assertNotEquals(MirrorFrames.sky(false), MirrorFrames.sky(true))
        assertNotEquals(MirrorFrames.ground(false), MirrorFrames.ground(true))
        assertNull(MirrorFrames.spriteTint(false))
        assertTrue(MirrorId.values().isNotEmpty())
    }
}
