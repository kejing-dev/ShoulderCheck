package io.embodit.shouldercheck.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.shouldercheck.domain.Pose
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog

/**
 * HMD pose source (`HMDTrackingProvider.latestData.hmdPose`, Full Space only), read once per frame from the ECS
 * system (HeadPoseSystem). The last valid pose is kept so that a missing sample does not make anything jump; 500 ms
 * without a valid sample = tracking lost (design S18).
 *
 * A first sample of (0,0,0) + identity is "not settled yet" and is dropped (factory rule; [Pose.isValid]).
 *
 * The provider's support state is `NOT_IN_FULL_SPACE` while the Stage is still opening and turns to `SUPPORTED` by
 * itself (SDK 6.1.9 on the emulator): [blockReason] reads it live; the controller gives it a grace period (S19 after
 * 2 s).
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: Pose? = null
    private var lastValidMs = 0L

    /** Debug: tracking counts as lost (`lost=1`). */
    var forceLost = false

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        val support = runCatching { p.supportState }.getOrNull()
        ScLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=$support")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        last = null
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { p -> runCatching { p.supportState }.getOrNull() }

    /** Null while the head can be tracked (or it is not yet known); else why not. */
    fun blockReason(): String? =
        when (supportState()) {
            DataProvider.SupportState.NOT_IN_FULL_SPACE -> "not-in-full-space"
            DataProvider.SupportState.DEVICE_NOT_SUPPORTED -> "device-not-supported"
            else -> null
        }

    /** True when tracking has given no valid pose for [LOST_AFTER_MS] (or debug `lost=1`). */
    fun lost(nowMs: Long): Boolean = forceLost || (provider != null && last != null && nowMs - lastValidMs > LOST_AFTER_MS)

    /** True once a valid (settled) pose has arrived. */
    fun ready(): Boolean = last != null

    /** Current head pose in Stage space, or the last valid one, or null before the first valid sample. */
    fun pose(): Pose? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val position = pose.position
        val rotation = pose.rotation
        val sample =
            Pose(
                Vec3(position.x.toDouble(), position.y.toDouble(), position.z.toDouble()),
                Quat(rotation.x.toDouble(), rotation.y.toDouble(), rotation.z.toDouble(), rotation.w.toDouble()).normalized(),
            )
        if (!sample.isValid()) return last
        last = sample
        lastValidMs = SystemClock.elapsedRealtime()
        return sample
    }

    companion object {
        const val LOST_AFTER_MS = 500L
    }
}
