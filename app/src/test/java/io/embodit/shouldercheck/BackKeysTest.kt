package io.embodit.shouldercheck

import io.embodit.shouldercheck.platform.BackKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D60: BACK / controller B go to ONE handler once per press; A / X / Y / ENTER / DPAD_CENTER are eaten (never click a focused button). */
class BackKeysTest {
    @Test
    fun backFiresOncePerPressAndFaceKeysAreEaten() {
        val fired = ArrayList<String>()
        BackKeys.handler = { fired += it }
        BackKeys.reset()
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_DOWN, 100L, false, "a"))
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_UP, 100L, false, "a"))
        // the same press seen by a second layer
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_UP, 100L, false, "b"))
        assertEquals(1, fired.size)
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BUTTON_B, BackKeys.ACTION_UP, 200L, false, "c"))
        assertEquals(2, fired.size)
        // a cancelled press does not fire
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_UP, 300L, true, "d"))
        assertEquals(2, fired.size)
        for (k in listOf(96, 99, 100, 66, 23)) assertTrue("key $k eaten", BackKeys.route(k, BackKeys.ACTION_UP, 400L + k, false, "e"))
        assertEquals(2, fired.size)
        // other keys pass through
        assertFalse(BackKeys.route(29, BackKeys.ACTION_UP, 500L, false, "f"))
    }
}
