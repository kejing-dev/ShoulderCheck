package io.embodit.shouldercheck.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog
import java.util.Locale

/**
 * The pre-built panel pool (rehearsal-hall / pruning-class PanelRig, SDK 6.1.9 emulator findings): every attachment
 * panel is declared once with a fixed size and gets a holder entity here, below the car (all panels are fixed to the
 * car: the car is your seat). A panel appears by moving its holder into place and disappears by stowing it.
 *
 * 1. A panel parented under a disabled holder never appears: a slot stays enabled, parked at 1/1000 size out of view,
 *    until its panel is bound and has had 6 frames / 0.6 s to come up.
 * 2. Hidden = holder stowed (40 m under the road) AND disabled, so a parked panel never takes the system pointer.
 * 3. A panel composes its content only two frames after it came into place ([onVisibility]); what it draws while
 *    parked stays broken.
 * 4. Pose is written only when it changed (a holder rewritten every frame lost presses now and then).
 */
internal class PanelPool(private val parent: Entity, private val stageRoot: Entity, private val onVisibility: (id: String, shown: Boolean) -> Unit) {
    enum class Rest { SHOWN, PARKED, STOWED }

    private var frame = 0L

    inner class Slot(val id: String, val holder: Entity, val widthDp: Double, val heightDp: Double) {
        var panel: Entity? = null
            private set
        var shown = false
            private set
        var position: Vec3 = Vec3.ZERO
            private set
        var rotation: Quat = Quat.IDENTITY
            private set
        var scale = 1.0
            private set
        var settled = false
            private set
        var rest = Rest.PARKED
            private set
        private var boundFrame = 0L
        private var boundMs = 0L
        private var writtenPos: Vec3? = null
        private var writtenRot: Quat? = null
        private var writtenScale = -1.0
        private var switchOn = true
        private var reported: Boolean? = null
        private var shownFrame = 0L

        /** Width / height of the shown panel in metres. */
        val widthM: Double
            get() = widthDp * DP_M * scale
        val heightM: Double
            get() = heightDp * DP_M * scale

        internal fun bind(entity: Entity, nowMs: Long) {
            settled = false
            apply()
            holder.addChild(entity)
            panel = entity
            boundFrame = frame
            boundMs = nowMs
            runCatching { entity.setName("panel:$id") }
        }

        internal fun settle(nowMs: Long) {
            if (reported != shown) report()
            if (settled || panel == null) return
            if (frame - boundFrame < SETTLE_FRAMES || nowMs - boundMs < SETTLE_MS) return
            settled = true
            apply()
        }

        private fun report() {
            val visible = shown && frame - shownFrame >= SHOW_FRAMES
            if (reported != visible) {
                reported = visible
                onVisibility(id, visible)
            }
        }

        /** Shows the panel at a car-frame pose. */
        fun place(at: Vec3, rot: Quat, k: Double) {
            position = at
            rotation = rot
            scale = k
            if (!shown) {
                shown = true
                shownFrame = frame
            }
            apply()
        }

        fun hide() {
            if (!shown) return
            shown = false
            apply()
        }

        internal fun apply() {
            val wanted =
                when {
                    shown -> Rest.SHOWN
                    !settled -> Rest.PARKED
                    else -> Rest.STOWED
                }
            val away = wanted != Rest.SHOWN
            val p = if (away) STOW else position
            val k = if (away) PARK_SCALE else scale
            val r = if (away) Quat.IDENTITY else rotation
            val wantSwitch = wanted != Rest.STOWED
            if (!wantSwitch && switchOn) {
                holder.enabled = false
                switchOn = false
            }
            val pw = writtenPos
            if (pw == null || (pw - p).length > 1e-4) {
                holder.setLocal(position = p)
                writtenPos = p
            }
            val rw = writtenRot
            if (rw == null || abs4(rw, r) < 0.999999) {
                holder.setLocal(rotation = r)
                writtenRot = r
            }
            if (kotlin.math.abs(k - writtenScale) > 1e-6) {
                holder.setLocal(scale = Vec3(k, k, k))
                writtenScale = k
            }
            if (wantSwitch && !switchOn) {
                holder.enabled = true
                switchOn = true
            }
            if (wanted != rest) {
                if (wanted == Rest.SHOWN) shownFrame = frame
                rest = wanted
            }
            report()
        }

        fun enginePosition(): Vec3? = runCatching { holder.convertPositionTo(Vector3.ZERO, stageRoot).domain() }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun abs4(a: Quat, b: Quat): Double = kotlin.math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w)

    private val slots = LinkedHashMap<String, Slot>()

    val all: Collection<Slot>
        get() = slots.values

    operator fun get(id: String): Slot? = slots[id]

    fun onFrame(nowMs: Long) {
        frame++
        for (s in slots.values) s.settle(nowMs)
    }

    fun slot(id: String, widthDp: Double, heightDp: Double): Slot {
        val holder = Entity().named("holder:$id")
        parent.addChild(holder)
        return Slot(id, holder, widthDp, heightDp).also {
            slots[id] = it
            it.apply()
        }
    }

    fun bind(attachments: SpatialViewAttachments, nowMs: Long): Boolean {
        var complete = true
        for (s in slots.values) {
            if (s.panel != null) continue
            val e = attachments.entity(s.id)
            if (e == null) {
                complete = false
                continue
            }
            s.bind(e, nowMs)
            ScLog.i("PANEL bound id=${s.id}")
        }
        return complete
    }

    fun release() {
        for (s in slots.values) runCatching { s.holder.removeAllChildren() }
        slots.clear()
    }

    fun dump(): List<String> =
        slots.values.map { s ->
            String.format(Locale.US, "PANEL id=%s rest=%s shown=%s bound=%s settled=%s pos=%s scale=%.3f size=%.0fx%.0f world=%s", s.id, s.rest, s.shown, s.panel != null, s.settled, ScLog.pos(s.position), s.scale, s.widthDp, s.heightDp, ScLog.pos(s.enginePosition()))
        }

    companion object {
        private const val PARK_SCALE = 0.001
        private val STOW = Vec3(0.0, -40.0, 30.0)
        private const val SHOW_FRAMES = 2L
        private const val SETTLE_FRAMES = 6L
        private const val SETTLE_MS = 600L
    }
}
