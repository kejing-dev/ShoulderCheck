package io.embodit.shouldercheck.platform.tracking

/**
 * Debug switches on the trackers' own outputs (`eye=off`, `lost=1`, `blocked=…`): the frame system copies them into
 * [EyeTracker.forceOff] / [HeadTracker.forceLost] every frame, so the app reacts exactly as to a real dropout.
 */
object TrackerForce {
    @Volatile var eyeOff = false

    @Volatile var headLost = false

    /** Debug `blocked=<reason>`: the head tracker's block reason (e.g. not-in-full-space); null = the real one. */
    @Volatile var blocked: String? = null
}
