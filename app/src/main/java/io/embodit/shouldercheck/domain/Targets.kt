package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * The 3D tap targets of v0.2.0 (design `tapTargets`, profile D50: every 3D clickable works with ONE tap from the
 * controller ray + trigger, the hand ray + pinch and eye-hand gaze + pinch). Each one is an entity named after its
 * [Target.id] that carries Collision + Interactable + the system HoverEffect on its own visible mesh; the board-input
 * layer resolves a hit up the parent chain with [targetOfName] (a TOP-LEVEL function: the resolver must not reference
 * an object that is still being built — bonsai v0.2.0 NPE, lessons/build.md).
 */
enum class Target(val id: String) {
    /** 「走」 / 「继续」 / 「跳过」 — the one big 3D button on the dash. */
    GO("btn:go"),

    /** The inner handle of your door (DR only; the click box covers the door trim so a shaky ray still lands). */
    DOOR("door:handle"),

    /** 「暂停」 chip above the steering wheel (between games it opens the main window). */
    PAUSE("pause:chip"),

    /** S54: turn eye tracking on. */
    ASK_ON("ask:on"),

    /** S54: no thanks. */
    ASK_OFF("ask:off"),
    ;

    companion object {
        fun parse(s: String): Target? = values().firstOrNull { it.id == s || it.name.equals(s, ignoreCase = true) }
    }
}

/** Entity name → its tap target; children of a target are named `<id>/<part>` and resolve to the same target. */
fun targetOfName(name: String): Target? {
    val head = name.substringBefore('/')
    return Target.values().firstOrNull { it.id == head }
}

/**
 * Where the 3D targets and read-outs sit, car frame of the driver's eye (design `tapTargets`, `hud3d`,
 * `spatialLayout`; x × m for left-hand traffic, never a negative scale). Sizes in metres.
 */
object HudLayout {
    /** 「走」 button: capsule 0.14 × 0.07, collider 0.18 × 0.10 (covers the timer ring), on the dash, tilted back 20°. */
    val GO = Vec3(0.10, -0.26, -0.72)
    val GO_SIZE = Vec3(0.14, 0.07, 0.03)
    val GO_COLLIDER = Vec3(0.18, 0.10, 0.05)
    const val GO_TILT_DEG = 20.0

    /**
     * 「暂停」 chip ⌀ 0.06 (collider 0.08). DEVIATION (within tolerance): design (0, −0.24, −0.55) puts its collider inside
     * the 「走」 button's (they overlap in both yaw and pitch); moved 0.10 m to the left of the wheel centre line so the
     * two clickables are ≥ 2° apart (D50 补).
     */
    val PAUSE = Vec3(-0.10, -0.245, -0.55)
    const val PAUSE_D = 0.06
    val PAUSE_COLLIDER = Vec3(0.08, 0.08, 0.04)

    /** Scenario card, first second: above the hood, 0.50 m wide; then one line ≤ 8° above the 「走」 button. */
    val CARD_BIG = Vec3(0.10, -0.05, -1.60)
    const val CARD_BIG_W = 0.50
    val CARD_SMALL = Vec3(0.10, -0.13, -0.78)
    const val CARD_SMALL_W = 0.11

    /** Combo + session score, 0.07 m above the button. */
    val COMBO = Vec3(0.10, -0.19, -0.72)

    /** Blind-spot mini map (replays only), right of the button, above the dash top (design y −0.27 sank it into the dash). */
    val MINIMAP = Vec3(0.42, -0.19, -0.76)
    const val MINIMAP_W = 0.24
    const val MINIMAP_H = 0.16

    /** S54 card and its two 3D buttons (0.16 × 0.07 each, 1.2 m: 7.6° × 3.3°, 6.7° apart). */
    val ASK_CARD = Vec3(0.10, 0.03, -1.60)
    val ASK_ON = Vec3(-0.05, -0.10, -1.20)
    val ASK_OFF = Vec3(0.25, -0.10, -1.20)
    val ASK_SIZE = Vec3(0.16, 0.07, 0.03)

    /** Merge countdown bar on the hood's front edge (−14°), not straight ahead. */
    val MERGE_BAR = Vec3(0.37, -0.42, -1.70)

    fun mirrored(p: Vec3, side: TrafficSide): Vec3 = p.mirrorX(side.m)

    /**
     * The two check lights (「镜」「回头」) next to the target-side mirror: left of the car 6 cm inside the near mirror
     * (design (−0.58, −0.10, −0.64)); on the far side the mirror is 1.58 m away behind the A pillar, so the lights float
     * on your line of sight 6° inside it, 0.95 m from the eye. Returns the centre of the pair; the two lights sit
     * [CHIP_GAP] apart along the car's x axis.
     */
    fun chips(cabin: Cabin, dir: Dir): Vec3 {
        val mirror = cabin.mirror(MirrorId.side(dir)) ?: return Vec3(-0.58, -0.10, -0.64)
        return if (abs(mirror.pos.x) < 1.0) Vec3(-0.58 * cabin.side.m, -0.10, -0.64)
        else Angles.dir(mirror.yawDeg - 6.0 * dir.sign, -4.0) * 0.95
    }

    const val CHIP_W = 0.06
    const val CHIP_H = 0.03
    const val CHIP_GAP = 0.07

    /**
     * A target's click area: [centre], the (width, height) it shows to the eye, and its axis-aligned [box] in the car
     * frame (used for the spacing test and the controller-ray fallback).
     */
    data class Hotspot(val target: Target, val centre: Vec3, val width: Double, val height: Double, val box: OccluderBox) {
        constructor(target: Target, centre: Vec3, width: Double, height: Double) : this(target, centre, width, height, OccluderBox(centre - Vec3(width / 2, height / 2, width / 2), centre + Vec3(width / 2, height / 2, width / 2)))
    }

    fun hotspots(cabin: Cabin, layout: CabinLayout?): List<Hotspot> {
        val m = cabin.side.m
        val out = ArrayList<Hotspot>()
        out += Hotspot(Target.GO, GO.mirrorX(m), GO_COLLIDER.x, GO_COLLIDER.y)
        out += Hotspot(Target.PAUSE, PAUSE.mirrorX(m), PAUSE_COLLIDER.x, PAUSE_COLLIDER.y)
        layout?.let { DoorTargets.box(it) }?.let { b ->
            val s = b.max - b.min
            out += Hotspot(Target.DOOR, (b.min + b.max) * 0.5, s.z, s.y, b) // seen from the side: length along z × height
        }
        return out
    }

    fun askHotspots(side: TrafficSide): List<Hotspot> =
        listOf(Hotspot(Target.ASK_ON, ASK_ON.mirrorX(side.m), ASK_SIZE.x, ASK_SIZE.y), Hotspot(Target.ASK_OFF, ASK_OFF.mirrorX(side.m), ASK_SIZE.x, ASK_SIZE.y))

    /** Smaller of the two angles a hotspot subtends from [eye] (degrees). */
    fun visualAngle(h: Hotspot, eye: Vec3 = Vec3.ZERO): Double {
        val d = h.centre.distanceTo(eye)
        return Math.toDegrees(2.0 * atan(min(h.width, h.height) / 2.0 / d))
    }

    /** Points over a box (5 × 5 × 5 grid, surface and inside) for angular tests. */
    private fun samples(b: OccluderBox): List<Vec3> {
        val out = ArrayList<Vec3>(125)
        for (i in 0..4) for (j in 0..4) for (k in 0..4) out += Vec3(b.min.x + (b.max.x - b.min.x) * i / 4, b.min.y + (b.max.y - b.min.y) * j / 4, b.min.z + (b.max.z - b.min.z) * k / 4)
        return out
    }

    /** Smallest angle between any part of two hotspots' click boxes seen from [eye] (degrees). */
    fun gap(a: Hotspot, b: Hotspot, eye: Vec3 = Vec3.ZERO): Double {
        val sa = samples(a.box).map { (it - eye).normalized() }
        val sb = samples(b.box).map { (it - eye).normalized() }
        var best = 180.0
        for (p in sa) for (q in sb) best = min(best, Angles.between(p, q))
        return best
    }
}

/**
 * The door's click box (v0.1.1 kept): the opening door's trim + armrest + handle, padded, car frame. The holder entity
 * named [Target.DOOR] sits at its centre with a box collider of its size.
 */
object DoorTargets {
    /** Extra margin around the door trim so a shaky long ray still lands (m). */
    const val PAD = 0.03

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

    /** A system tap and the controller-ray fallback for the same press: only the first one counts. */
    const val DEDUPE_MS = 800L
}

/** Box of a hotspot for the controller-ray fallback (axis aligned, thin along the view direction). */
fun hotspotBox(h: HudLayout.Hotspot): OccluderBox = h.box
