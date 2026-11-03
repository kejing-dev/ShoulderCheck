package io.embodit.shouldercheck.platform.tracking

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import io.embodit.shouldercheck.domain.Pose
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog

/**
 * Eye gaze source (`EyeTrackingProvider`, SDK doc "Eye tracking": Full Space only, runtime permission
 * `com.picovr.permission.EYE_TRACKING`). Read once per frame from the ECS system (GazeSystem), like [HeadTracker].
 * The gaze pose is in global (Stage) space with the gaze along local -Z.
 *
 * [pose] is null whenever the eyes cannot be used (no permission, device not supporting it, no fresh sample): the
 * judge then applies the head rules for that sample (design `model.judging.mode`).
 */
class EyeTracker {
    private var provider: EyeTrackingProvider? = null
    private var lastStamp = 0L
    private var lastFreshMs = 0L

    /** Debug: pretend the eyes are unavailable (`eye=off`). */
    var forceOff = false

    fun start() {
        if (provider != null) return
        val p = EyeTrackingProvider()
        val result = runCatching { p.start() }
        ScLog.i("TRACK eye start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        lastStamp = 0L
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { p -> runCatching { p.supportState }.getOrNull() }

    /** Why the eyes are not used now: null = eyes in use; else "permission", "unsupported", "no-data", "off". */
    fun unavailableReason(nowMs: Long): String? {
        if (forceOff) return "off"
        return when (supportState()) {
            DataProvider.SupportState.SUPPORTED -> if (nowMs - lastFreshMs > STALE_MS) "no-data" else null
            DataProvider.SupportState.WITHOUT_PERMISSION -> "permission"
            null -> "no-data"
            else -> "unsupported"
        }
    }

    /** Latest gaze pose (Stage space; gaze along local -Z), or null when not usable. */
    fun pose(nowMs: Long): Pose? {
        val p = provider ?: return null
        if (forceOff || runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        if (data.timestamp == 0L) return null
        if (data.timestamp != lastStamp) {
            lastStamp = data.timestamp
            lastFreshMs = nowMs
        }
        if (nowMs - lastFreshMs > STALE_MS) return null
        val pos = data.eyePose.position
        val rot = data.eyePose.rotation
        val pose =
            Pose(
                Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
                Quat(rot.x.toDouble(), rot.y.toDouble(), rot.z.toDouble(), rot.w.toDouble()).normalized(),
            )
        if (!pose.position.isFinite() || pose.position.length < 1e-4) return null
        return pose
    }

    private companion object {
        const val STALE_MS = 1_000L
    }
}
