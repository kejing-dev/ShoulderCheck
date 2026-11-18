package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tap-to-open door (v0.1.1, profile D50, user on the headset 2026-11-17: 「push 门那个用手柄点不了，应该点一下门就行」).
 * One tap on the opening door — controller ray + trigger, hand ray + pinch, eye-hand (gaze + pinch) — swings it open on
 * its own ([DoorModel.tap]); the hand that tapped counts for the far-hand step. Pure: names, resolution, the door's
 * click box and the controller-ray fallback test, unit-tested in DoorTapTest.
 */
object DoorTargets {
    /** Holder entity of each seat's opening door: Collision + Interactable + system HoverEffect, door meshes below it. */
    private const val PREFIX = "tap:door:"

    fun holder(seat: Seat): String = PREFIX + seat.name

    /** Entity name -> the seat whose opening door it is; null = not ours (walk up with resolveUp / resolvePath). */
    fun resolveName(name: String): Seat? = if (name.startsWith(PREFIX)) Seat.values().firstOrNull { it.name == name.removePrefix(PREFIX) } else null

    /** Extra margin around the door trim so a shaky long ray still lands (m). */
    const val PAD = 0.03

    /** Click box of the closed opening door (car frame, axis aligned: the door parts are unrotated boxes), padded. */
    fun box(layout: CabinLayout): OccluderBox? {
        val d = layout.openingDoor ?: return null
        var lo = Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        var hi = Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (p in d.parts) {
            val h = p.size * 0.5
            lo = Vec3(min(lo.x, p.centre.x - h.x), min(lo.y, p.centre.y - h.y), min(lo.z, p.centre.z - h.z))
            hi = Vec3(max(hi.x, p.centre.x + h.x), max(hi.y, p.centre.y + h.y), max(hi.z, p.centre.z + h.z))
        }
        val pad = Vec3(PAD, PAD, PAD)
        return OccluderBox(lo - pad, hi + pad)
    }

    /** Distance along the ray to [box] (slab test), or null when it misses / the box is behind. [dir] need not be unit. */
    fun rayHit(origin: Vec3, dir: Vec3, box: OccluderBox): Double? {
        val n = dir.normalized()
        var t0 = 0.0
        var t1 = Double.POSITIVE_INFINITY
        val o = doubleArrayOf(origin.x, origin.y, origin.z)
        val d = doubleArrayOf(n.x, n.y, n.z)
        val lo = doubleArrayOf(box.min.x, box.min.y, box.min.z)
        val hi = doubleArrayOf(box.max.x, box.max.y, box.max.z)
        for (i in 0..2) {
            if (abs(d[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) return null
                continue
            }
            var a = (lo[i] - o[i]) / d[i]
            var b = (hi[i] - o[i]) / d[i]
            if (a > b) a = b.also { b = a }
            t0 = max(t0, a)
            t1 = min(t1, b)
            if (t0 > t1) return null
        }
        return t0
    }

    /** Smallest angle the closed door subtends from [eye] (degrees): D17 wants ≥ 2° for every clickable thing. */
    fun minVisualAngle(box: OccluderBox, eye: Vec3): Double {
        val c = (box.min + box.max) * 0.5
        val dist = c.distanceTo(eye)
        val s = box.max - box.min
        val smallest = listOf(s.x, s.y, s.z).sorted()[1] // the thin trim depth does not count: its face is what you see
        return Math.toDegrees(2.0 * kotlin.math.atan(smallest / 2.0 / dist))
    }

    /** How long after a trigger press / pinch the tap is still credited to that hand (the system taps on release). */
    const val HAND_WINDOW_MS = 1500L

    /**
     * Which hand tapped: the one whose trigger (controller) or pinch (hand ray / eye-hand) went down most recently
     * within [HAND_WINDOW_MS]. None seen -> the far hand, flagged `assumed` (logged; the user's tap still opens the door).
     */
    fun tapHand(recentPressMs: Map<Hand, Long>, nowMs: Long, far: Hand): Pair<Hand, Boolean> {
        val h = recentPressMs.filterValues { nowMs - it in 0..HAND_WINDOW_MS }.maxByOrNull { it.value }?.key
        return if (h != null) h to false else far to true
    }

    /** A system tap and the controller-ray fallback for the same press: only the first one counts. */
    const val DEDUPE_MS = 800L
}
