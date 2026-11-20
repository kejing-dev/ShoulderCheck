package io.embodit.shouldercheck.domain

/**
 * The four real-world moments of v0.2.0 (design `scenarios`; gate B 2026-11-19: 4 levels × 4 situations). [id] is the
 * debug `scene=` name; [designDir] is the direction in right-hand traffic, [dir] the physical one (left-hand traffic
 * flips every side: a pull-out to the left becomes a pull-out to the right).
 */
enum class Situation(val id: String, val designDir: Dir) {
    /** City expressway, three lanes, you in the middle one, change to the left lane. */
    LANE_LEFT("LC_L", Dir.LEFT),

    /** Same, to the right lane. */
    LANE_RIGHT("LC_R", Dir.RIGHT),

    /** Parked at the kerb, pull out into the lane beside you. */
    PULL_OUT("PO", Dir.LEFT),

    /** Ramp acceleration lane: merge into the main road before the lane ends. */
    MERGE("MG", Dir.LEFT),

    /** Parked, engine off: check behind before you open the driver's door. */
    DOOR("DR", Dir.LEFT),
    ;

    fun dir(side: TrafficSide): Dir = designDir.physical(side)

    /** Lane-type move (lane change, pull out, merge) as opposed to the door. */
    val lane: Boolean
        get() = this != DOOR

    /** Your car rolls along at 36 km/h in this situation (unless comfort mode): lane change and merge. */
    val cruising: Boolean
        get() = this == LANE_LEFT || this == LANE_RIGHT || this == MERGE

    /** The scene family (debug `scene=LC|PO|MG|DR`). */
    val family: String
        get() = id.take(2)

    companion object {
        fun byId(id: String): Situation? = values().firstOrNull { it.id.equals(id, ignoreCase = true) }

        /** Debug `scene=LC side=L|R` → the situation. */
        fun parse(scene: String, side: String?): Situation? =
            when (scene.uppercase()) {
                "LC" -> if (side?.uppercase() == "R") LANE_RIGHT else LANE_LEFT
                "LC_L" -> LANE_LEFT
                "LC_R" -> LANE_RIGHT
                "PO" -> PULL_OUT
                "MG" -> MERGE
                "DR" -> DOOR
                else -> null
            }
    }
}
