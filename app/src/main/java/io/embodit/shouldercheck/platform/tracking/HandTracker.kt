package io.embodit.shouldercheck.platform.tracking

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandPinch
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog

/** One hand's pinch this frame (Stage space). */
data class Pinch(val hand: Hand, val position: Vec3, val value: Float)

/**
 * Hand tracking for the gesture-mode door (design doorModel.gesture, N6): `HandTrackingProvider` (Full Space only)
 * gives each hand's pinch pose and pinch value; "hand within 0.15 m of the handle + pinch held" = holding the door,
 * and the hand that pinches decides the far-hand step. Read once per frame from the ECS system.
 */
class HandTracker {
    private var provider: HandTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    fun start() {
        if (provider != null) return
        val p = HandTrackingProvider()
        val r = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        ScLog.i("TRACK hand start=${r.getOrNull() ?: r.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { runCatching { it.supportState }.getOrNull() }

    /** Pinches of both hands, empty when hands are not tracked. */
    fun pinches(): List<Pinch> {
        val p = provider ?: return emptyList()
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            ScLog.i("TRACK hand support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return emptyList()
        val data = runCatching { p.latestData }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pinch>(2)
        fun add(hand: Hand, pinch: HandPinch?) {
            if (pinch == null || !runCatching { pinch.isReady }.getOrDefault(false)) return
            val pos = pinch.pinchPose.position
            val v = Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            if (!v.isFinite() || v.length < 1e-4) return
            out += Pinch(hand, v, pinch.pinchValue)
        }
        add(Hand.LEFT, runCatching { data.leftPinch }.getOrNull())
        add(Hand.RIGHT, runCatching { data.rightPinch }.getOrNull())
        return out
    }
}
