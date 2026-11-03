package io.embodit.shouldercheck.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerAction
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.Pose
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What one controller delivers in one frame (Stage space; the ray / pointing direction is the pose's local -Z). */
data class ControllerState(
    val hand: Hand,
    /** Null when this controller has no tracked pose this frame. */
    val pose: Pose?,
    val trigger: Float,
    val triggerPressed: Boolean,
    /** Grip ("握把"): SDK 6.1.9 `ControllerAction.gripPressed` / `gripValue` (0..1). Design `doorModel.grab`. */
    val grip: Float,
    val gripPressed: Boolean,
    /** Thumbstick -1 (left / down) .. 1 (right / up). */
    val stickX: Float,
    val stickY: Float,
)

/**
 * Both controllers: poses from `ControllerTrackingProvider.latestData` (read once per frame on the main thread), buttons
 * from `ControllerActionListener`. The listener runs on the data source's thread: it only copies values into atomics
 * and counts press edges, which the frame system takes (pattern of figure-model / Spatial Ruler).
 *
 * SDK 6.1.9 `ControllerAction` (javap of tracking-6.1.9.aar): `triggerPressed/Touched/Value`, `gripPressed`,
 * `gripValue`, `thumbstickPressed/Touched/Value(x, y)`, `aButtonPressed/Touched`, `bButtonPressed/Touched`,
 * `xButtonPressed/Touched`, `yButtonPressed/Touched`. **There is no menu-button field** (the system keeps the menu
 * key): the design's "左手柄菜单键 = 暂停" (T9) falls back to the P2 pause button (and the Y button, see [takeY]).
 *
 * Use in this app (design `interactions`): trigger = click UI only (system pointer), A (right) = execute (T6),
 * B (right) = close the door (T7), grip = hold the door handle (T7), left stick X = turn signal (T5; lesson 1: left
 * mirror aim, T15), right stick X = right mirror aim in lesson 1.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    private class Side {
        val trigger = AtomicInteger(0)
        val triggerPressed = AtomicBoolean(false)
        val grip = AtomicInteger(0)
        val gripPressed = AtomicBoolean(false)
        val stickX = AtomicInteger(0)
        val stickY = AtomicInteger(0)
    }

    private val right = Side()
    private val left = Side()

    private class Edge {
        private val down = AtomicBoolean(false)
        private val presses = AtomicInteger(0)

        fun feed(pressed: Boolean) {
            if (pressed && !down.getAndSet(true)) presses.incrementAndGet()
            if (!pressed) down.set(false)
        }

        fun take(): Int = presses.getAndSet(0)
    }

    private val aEdge = Edge()
    private val bEdge = Edge()
    private val xEdge = Edge()
    private val yEdge = Edge()

    @Volatile var actionCount = 0L
        private set

    private fun copy(a: ControllerAction?, s: Side) {
        if (a == null) return
        s.trigger.set(java.lang.Float.floatToIntBits(a.triggerValue))
        s.triggerPressed.set(a.triggerPressed)
        s.grip.set(java.lang.Float.floatToIntBits(runCatching { a.gripValue }.getOrDefault(0f)))
        s.gripPressed.set(runCatching { a.gripPressed }.getOrDefault(false))
        s.stickX.set(java.lang.Float.floatToIntBits(runCatching { a.thumbstickValue.x }.getOrDefault(0f)))
        s.stickY.set(java.lang.Float.floatToIntBits(runCatching { a.thumbstickValue.y }.getOrDefault(0f)))
    }

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { action: ControllerActionData ->
            val r: ControllerAction? = runCatching { action.right }.getOrNull()
            val l: ControllerAction? = runCatching { action.left }.getOrNull()
            copy(r, right)
            copy(l, left)
            aEdge.feed(r?.aButtonPressed == true)
            bEdge.feed(r?.bButtonPressed == true)
            xEdge.feed(l?.xButtonPressed == true)
            yEdge.feed(l?.yButtonPressed == true)
            actionCount++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { ScLog.w("TRACK controller actions: $it") }
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        ScLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        aEdge.take()
        bEdge.take()
        xEdge.take()
        yEdge.take()
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actionCount"

    /** A (right controller) presses since the last call: execute (T6) / lesson-1 confirm (T18). */
    fun takeA(): Int = aEdge.take()

    /** B (right) presses since the last call: close the door, retry (T7). */
    fun takeB(): Int = bEdge.take()

    /** X (left) presses since the last call. */
    fun takeX(): Int = xEdge.take()

    /** Y (left) presses since the last call: stands in for the missing menu key (pause, T9). */
    fun takeY(): Int = yEdge.take()

    /** Once per frame on the main thread: both controllers (pose null when untracked). Null when not supported. */
    fun sample(): Pair<ControllerState, ControllerState>? {
        val p = provider ?: return null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            ScLog.i("TRACK controller support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull()
        fun pose(hand: Hand): Pose? {
            val c = (if (hand == Hand.RIGHT) data?.right else data?.left) ?: return null
            val position = Vec3(c.position.x.toDouble(), c.position.y.toDouble(), c.position.z.toDouble())
            val rotation = Quat(c.rotation.x.toDouble(), c.rotation.y.toDouble(), c.rotation.z.toDouble(), c.rotation.w.toDouble()).normalized()
            val pose = Pose(position, rotation)
            return if (pose.isValid()) pose else null
        }
        fun state(hand: Hand, s: Side) =
            ControllerState(
                hand = hand,
                pose = pose(hand),
                trigger = java.lang.Float.intBitsToFloat(s.trigger.get()),
                triggerPressed = s.triggerPressed.get(),
                grip = java.lang.Float.intBitsToFloat(s.grip.get()),
                gripPressed = s.gripPressed.get(),
                stickX = java.lang.Float.intBitsToFloat(s.stickX.get()),
                stickY = java.lang.Float.intBitsToFloat(s.stickY.get()),
            )
        return state(Hand.LEFT, left) to state(Hand.RIGHT, right)
    }
}
