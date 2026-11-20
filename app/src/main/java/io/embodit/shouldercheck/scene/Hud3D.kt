package io.embodit.shouldercheck.scene

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import io.embodit.shouldercheck.R
import io.embodit.shouldercheck.app.AppController
import io.embodit.shouldercheck.app.GoLabel
import io.embodit.shouldercheck.app.HudState
import io.embodit.shouldercheck.app.ResultPop
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.HudLayout
import io.embodit.shouldercheck.domain.Layout
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Road
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Target
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.ui.theme.ScColors
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The 3D HUD of a round (design `hud3d`, `tapTargets`): the 「走」 button with its timer ring, the 「暂停」 chip, the
 * scenario card, the 「镜」「回头」 lights, combo + score, the result pop, the replay mini map / arrow / hazard marker, the
 * S54 eye question with its two 3D buttons, the merge countdown, the game-over card and the lane-change vignette.
 *
 * Every tap target is a holder named after its [Target] id that carries Collision + Interactable + the system
 * HoverEffect only while it takes taps (board-input README 「Hover 落地」: the hover lights the holder's visible child
 * meshes, never covers their colour). Car frame of the driver's eye; x × m in left-hand traffic.
 */
internal class Hud3D(
    private val shared: SharedResources,
    private val carRoot: Entity,
    private val worldRoot: Entity,
    private val physics: PhysicsMaterialResource,
) {
    private val c = AppController
    private val quad = shared.keep(Meshes.quad("hudQuad"))
    private val disc = shared.keep(Meshes.disc("hudDisc", seg = 40))
    private val goMesh = shared.keep(Meshes.stadium("goBody", HudLayout.GO_SIZE.x, HudLayout.GO_SIZE.y, HudLayout.GO_SIZE.z))
    private val askMesh = shared.keep(Meshes.stadium("askBody", HudLayout.ASK_SIZE.x, HudLayout.ASK_SIZE.y, HudLayout.ASK_SIZE.z))
    private val bodyMat = pbr(shared, ScColors.GoBody, roughness = 0.45f)
    private val ringOn = unlit(shared, Color.White)
    private val ringLate = unlit(shared, ScColors.TimerLate)

    private val holders = HashMap<Target, Entity>()
    private val shapes = HashMap<Target, ShapeResource>()
    private val tapOn = HashMap<Target, Boolean>()

    // 「走」
    private val goRoot = holder(Target.GO, HudLayout.GO_COLLIDER)
    private val goBody = ModelEntity(goMesh, bodyMat).named("${Target.GO.id}/body").also { goRoot.addChild(it) }
    private val goLabel = TexLabel(shared, quad, "${Target.GO.id}/label", goRoot, 512, 256, opaque = true)
    private val ring = ArrayList<Entity>()

    // 「暂停」 / 「菜单」 chip
    private val pauseRoot = holder(Target.PAUSE, HudLayout.PAUSE_COLLIDER)
    private val pauseChip = TexLabel(shared, disc, "${Target.PAUSE.id}/chip", pauseRoot, 256, 256, opaque = false)

    // S54
    private val askOn = holder(Target.ASK_ON, HudLayout.ASK_SIZE)
    private val askOff = holder(Target.ASK_OFF, HudLayout.ASK_SIZE)
    private val askOnBody = ModelEntity(askMesh, bodyMat).named("${Target.ASK_ON.id}/body").also { askOn.addChild(it) }
    private val askOffBody = ModelEntity(askMesh, bodyMat).named("${Target.ASK_OFF.id}/body").also { askOff.addChild(it) }
    private val askOnLabel = TexLabel(shared, quad, "${Target.ASK_ON.id}/label", askOn, 512, 256, opaque = true)
    private val askOffLabel = TexLabel(shared, quad, "${Target.ASK_OFF.id}/label", askOff, 512, 256, opaque = true)
    private val askCard = TexLabel(shared, quad, "askCard", carRoot, 1024, 256)

    // read-outs
    private val card = TexLabel(shared, quad, "card", carRoot, 1024, 256)
    private val cardSmall = TexLabel(shared, quad, "cardSmall", carRoot, 768, 128)
    private val cardSub = TexLabel(shared, quad, "cardSub", carRoot, 1024, 256)
    private val chipMirror = TexLabel(shared, quad, "chipMirror", carRoot, 256, 128)
    private val chipShoulder = TexLabel(shared, quad, "chipShoulder", carRoot, 256, 128)
    private val combo = TexLabel(shared, quad, "combo", carRoot, 512, 128)
    private val pop = TexLabel(shared, quad, "pop", carRoot, 1024, 384)
    private val lookForward = TexLabel(shared, quad, "lookForward", carRoot, 512, 128)
    private val minimap = TexLabel(shared, quad, "minimap", carRoot, 384, 256, opaque = true)
    private val arrow = TexLabel(shared, quad, "replayArrow", carRoot, 512, 256)
    private val marker = TexLabel(shared, quad, "hazardMarker", carRoot, 256, 256)
    private val hazardRing: Entity
    private val mergeBar = TexLabel(shared, quad, "mergeBar", carRoot, 1024, 128)
    private val summary = TexLabel(shared, quad, "summary", carRoot, 1024, 384)
    private val counter = TexLabel(shared, quad, "replayCount", carRoot, 256, 128)

    // head-locked vignette (the only head-locked element)
    private val vignetteMat = unlit(shared, Color.Black, BlendingMode.TRANSPARENT, opacity = 0f, twoSided = true, depthWrite = false, texture = shared.keep(com.pico.spatial.core.ecs.resource.TextureResource.create(Textures.vignette())))
    private val vignette: Entity = ModelEntity(quad, vignetteMat).named("vignette").also { worldRoot.addChild(it); it.enabled = false }
    private var vignetteLevel = 0f

    private var side = TrafficSide.RIGHT_HAND
    private var hovered: Target? = null
    private var hoverUntilMs = 0L
    private var lastMapMs = 0L

    init {
        for (i in 0 until RING_SEGMENTS) {
            val m = shared.keep(Meshes.stadiumBand("ring$i", HudLayout.GO_COLLIDER.x, HudLayout.GO_COLLIDER.y, 0.008, i.toDouble() / RING_SEGMENTS + 0.002, (i + 1.0) / RING_SEGMENTS - 0.002))
            ring += ModelEntity(m, ringOn).named("${Target.GO.id}/ring$i").also { goRoot.addChild(it) }
        }
        goBody.setLocal(position = Vec3.ZERO)
        goLabel.place(Vec3(0.0, 0.0, HudLayout.GO_SIZE.z / 2 + 0.002), Quat.IDENTITY, HudLayout.GO_SIZE.x - HudLayout.GO_SIZE.y * 0.55, HudLayout.GO_SIZE.y * 0.8)
        goLabel.show(true)
        pauseChip.place(Vec3.ZERO, Quat.IDENTITY, HudLayout.PAUSE_D, HudLayout.PAUSE_D)
        pauseChip.show(true)
        for ((l, root) in listOf(askOnLabel to askOn, askOffLabel to askOff)) {
            l.place(Vec3(0.0, 0.0, HudLayout.ASK_SIZE.z / 2 + 0.002), Quat.IDENTITY, HudLayout.ASK_SIZE.x - HudLayout.ASK_SIZE.y * 0.55, HudLayout.ASK_SIZE.y * 0.8)
            l.show(true)
            root.enabled = false
        }
        val ringTex = shared.keep(com.pico.spatial.core.ecs.resource.TextureResource.create(Textures.radial(96, ring = true)))
        hazardRing = ModelEntity(disc, unlit(shared, ScColors.Hazard, BlendingMode.TRANSPARENT, opacity = 0.95f, twoSided = true, depthWrite = false, texture = ringTex)).named("hazardRing").also { carRoot.addChild(it); it.enabled = false }
        goRoot.enabled = false
        pauseRoot.enabled = false
    }

    private fun holder(t: Target, collider: Vec3): Entity {
        val e = Entity().named(t.id)
        carRoot.addChild(e)
        holders[t] = e
        // D59: the shape is cached across frames → global (shared.keep calls toGlobal)
        shapes[t] = shared.keep(ShapeResource.createBox(collider.sdk()))
        return e
    }

    fun holderOf(t: Target): Entity? = holders[t]

    /** Hover from board-input (supplement to the system HoverEffect; the system may not report hover in Full Space). */
    fun onHover(t: Target?, nowMs: Long) {
        if (t != null) {
            hovered = t
            hoverUntilMs = nowMs + 1500
        }
    }

    fun update(res: Resources, hud: HudState, cabin: Cabin, head: Vec3, headYaw: Double, nowMs: Long, frame: Long) {
        side = cabin.side
        val m = side.m
        val eye = Vec3.ZERO
        // ---- taps: colliders only while the target takes taps
        for (t in listOf(Target.GO, Target.PAUSE, Target.ASK_ON, Target.ASK_OFF)) setTappable(t, c.tappable(t))
        val hoverGo = hovered == Target.GO && nowMs < hoverUntilMs
        // ---- 「走」
        val goPos = HudLayout.GO.mirrorX(m)
        val shake = if (nowMs - hud.goShakeMs < 400) kotlin.math.sin((nowMs - hud.goShakeMs) / 400.0 * Math.PI * 6) * 0.006 else 0.0
        if (goRoot.enabled != hud.goVisible) goRoot.enabled = hud.goVisible
        if (hud.goVisible) {
            goRoot.setLocal(position = goPos + Vec3(shake, 0.0, 0.0), rotation = Layout.facing(goPos, eye), scale = (if (hoverGo) 1.05 else 1.0).let { Vec3(it, it, it) })
            val labelText = goText(res, hud)
            val lit = hud.goLit
            goLabel.set(res, "$labelText|$lit|${res.configuration.locales[0]}", frame) { cv, r, w, h ->
                cv.drawColor(ScColors.GoBody.toArgb())
                TextPaint.line(cv, r, labelText, w / 2f, h * 0.66f, h * 0.52f, if (lit) ScColors.Go.toArgb() else ScColors.GoIdle.toArgb(), w * 0.92f, 700)
            }
            val segs = (hud.timerLeft * RING_SEGMENTS).toInt().coerceIn(0, RING_SEGMENTS)
            val late = hud.timerLeft < 0.3
            val showRing = hud.goLabel == GoLabel.GO
            for ((i, e) in ring.withIndex()) {
                val on = showRing && i < segs
                if (e.enabled != on) e.enabled = on
                if (on) e.setMaterial(if (late) ringLate else ringOn)
            }
        }
        // ---- pause / menu chip
        val pausePos = HudLayout.PAUSE.mirrorX(m)
        val chipOn = hud.pauseVisible || hud.menuChip
        if (pauseRoot.enabled != chipOn) pauseRoot.enabled = chipOn
        if (chipOn) {
            val hoverP = hovered == Target.PAUSE && nowMs < hoverUntilMs
            pauseRoot.setLocal(position = pausePos, rotation = Layout.facing(pausePos, eye), scale = (if (hoverP) 1.08 else 1.0).let { Vec3(it, it, it) })
            val word = res.getString(if (hud.menuChip) R.string.menu else R.string.pause)
            pauseChip.set(res, "chip|$word", frame) { cv, r, w, h ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ScColors.GoBody.toArgb() }
                cv.drawCircle(w / 2f, h / 2f, w / 2f - 2f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 8f
                p.color = TextPaint.argb(230, 235, 238, 242)
                cv.drawCircle(w / 2f, h / 2f, w / 2f - 8f, p)
                TextPaint.line(cv, r, word, w / 2f, h * 0.60f, h * 0.30f, TextPaint.argb(255, 245, 246, 248), w * 0.80f, 700)
            }
        }
        // ---- scenario card (big for the first second, then one line above the button)
        val sit = hud.situation
        val title = sit?.let { scenarioText(res, it, hud.dir) } ?: ""
        val sub = when {
            hud.firstHint -> res.getString(R.string.first_hint)
            hud.eyeOffLine -> res.getString(R.string.line_eye_off)
            else -> ""
        }
        val bigOn = hud.cardVisible && hud.cardBig
        card.show(bigOn)
        if (bigOn) {
            val p = HudLayout.CARD_BIG.mirrorX(m)
            card.place(p, Layout.facing(p, eye), HudLayout.CARD_BIG_W, HudLayout.CARD_BIG_W / 4)
            card.set(res, "big|$title|$sub", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 40f, TextPaint.argb(200, 18, 21, 25))
                TextPaint.line(cv, r, title, w / 2f, if (sub.isEmpty()) h * 0.64f else h * 0.50f, 92f, TextPaint.argb(255, 255, 255, 255), w * 0.92f, 700)
                if (sub.isNotEmpty()) TextPaint.line(cv, r, sub, w / 2f, h * 0.84f, 46f, TextPaint.argb(255, 236, 239, 242), w * 0.94f, 500)
            }
        }
        val smallOn = hud.cardVisible && !hud.cardBig && sit != null
        cardSmall.show(smallOn)
        if (smallOn) {
            val p = HudLayout.CARD_SMALL.mirrorX(m)
            cardSmall.place(p, Layout.facing(p, eye), CARD_SMALL_W, CARD_SMALL_W / 6)
            cardSmall.set(res, "small|$title", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 28f, TextPaint.argb(190, 18, 21, 25))
                TextPaint.line(cv, r, title, w / 2f, h * 0.70f, h * 0.60f, TextPaint.argb(255, 255, 255, 255), w * 0.94f, 700)
            }
        }
        // the guide / eye-off line: its own two-line label above the small card (readable, ≥ 0.5° letters, D13)
        val subOn = smallOn && sub.isNotEmpty()
        cardSub.show(subOn)
        if (subOn) {
            val p = (HudLayout.CARD_SMALL + Vec3(0.0, CARD_SMALL_W / 12 + SUB_W / 8 + 0.006, 0.0)).mirrorX(m)
            cardSub.place(p, Layout.facing(p, eye), SUB_W, SUB_W / 4)
            cardSub.set(res, "sub|$sub", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 36f, TextPaint.argb(175, 18, 21, 25))
                wrap(cv, r, sub, w, h, 70f)
            }
        }
        // ---- 「先看回前面」 above the button
        val lf = nowMs < hud.lookForwardUntilMs
        lookForward.show(lf)
        if (lf) {
            val p = (HudLayout.COMBO + Vec3(0.0, 0.035, 0.0)).mirrorX(m)
            lookForward.place(p, Layout.facing(p, eye), 0.16, 0.04)
            val word = res.getString(R.string.look_forward)
            lookForward.set(res, "lf|$word", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 30f, ScColors.TimerLate.toArgb())
                TextPaint.line(cv, r, word, w / 2f, h * 0.70f, h * 0.58f, TextPaint.argb(255, 0, 0, 0), w * 0.92f, 700)
            }
        }
        // ---- the two check lights next to the target-side mirror
        val chips = hud.chipsVisible && sit != null
        chipMirror.show(chips)
        chipShoulder.show(chips)
        if (chips) {
            val centre = HudLayout.chips(cabin, hud.dir)
            val face = Layout.facing(centre, eye)
            val along = face.rotate(Vec3(1.0, 0.0, 0.0)) * (HudLayout.CHIP_GAP / 2)
            // the shoulder light sits on the outer side (towards the blind spot), the mirror light inside it
            val outer = if (hud.dir == Dir.LEFT) -1.0 else 1.0
            chipLabel(res, chipMirror, R.string.chip_mirror, hud.mirrorLit, centre - along * outer, face, frame)
            chipLabel(res, chipShoulder, R.string.chip_shoulder, hud.shoulderLit, centre + along * outer, face, frame)
        }
        // ---- combo + score (only once there is something to show)
        val comboOn = hud.goVisible && !hud.replay && (hud.combo >= 2 || hud.score > 0)
        combo.show(comboOn)
        if (comboOn) {
            val p = HudLayout.COMBO.mirrorX(m)
            combo.place(p, Layout.facing(p, eye), 0.16, 0.04)
            val text = if (hud.combo >= 2) res.getString(R.string.hud_combo_score, hud.combo, fmt(hud.score)) else res.getString(R.string.res_score, fmt(hud.score))
            combo.set(res, "combo|$text", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 30f, TextPaint.argb(170, 18, 21, 25))
                TextPaint.line(cv, r, text, w / 2f, h * 0.70f, h * 0.58f, if (hud.combo >= 3) ScColors.ComboHot.toArgb() else TextPaint.argb(255, 255, 255, 255), w * 0.92f, 700)
            }
        }
        // ---- result pop: flies up from the button to the card place (0.35 s), stays ~1.2 s
        val pp = hud.pop
        pop.show(pp != null)
        if (pp != null) {
            val u = ((nowMs - pp.sinceMs) / 350.0).coerceIn(0.0, 1.0)
            val e = u * u * (3 - 2 * u)
            val from = HudLayout.GO.mirrorX(m)
            val to = (if (hud.replay) HudLayout.CARD_BIG + Vec3(0.0, 0.10, 0.0) else HudLayout.CARD_BIG).mirrorX(m)
            val p = from.lerp(to, e)
            val k = 0.35 + 0.65 * e
            pop.place(p, Layout.facing(p, eye), POP_W * k, POP_W * k * 0.375)
            pop.set(res, "pop|${pp.outcome}|${pp.points}|${pp.stars}|${pp.combo}|${pp.mirrorOk}|${pp.shoulderOk}|${pp.reaction}|${pp.trapSide}|${hud.replay}", frame) { cv, r, w, h -> drawPop(cv, r, w, h, pp) }
        }
        // ---- replay: mini map, arrow at the side of your head, hazard ring + marker, counter
        updateReplay(res, hud, cabin, head, headYaw, nowMs, frame)
        // ---- S54
        val ask = hud.askVisible
        askOn.enabled = ask
        askOff.enabled = ask
        askCard.show(ask)
        if (ask) {
            val pc = HudLayout.ASK_CARD.mirrorX(m)
            askCard.place(pc, Layout.facing(pc, eye), 0.50, 0.125)
            val q = res.getString(R.string.eye_ask)
            askCard.set(res, "ask|$q", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 40f, TextPaint.argb(205, 18, 21, 25))
                wrap(cv, r, q, w, h, 56f)
            }
            for ((root, pos, label, word) in listOf(Quad4(askOn, HudLayout.ASK_ON, askOnLabel, R.string.eye_on), Quad4(askOff, HudLayout.ASK_OFF, askOffLabel, R.string.eye_no))) {
                val pm = pos.mirrorX(m)
                val t = targetOf(root)
                val hv = hovered == t && nowMs < hoverUntilMs
                root.setLocal(position = pm, rotation = Layout.facing(pm, eye), scale = (if (hv) 1.05 else 1.0).let { Vec3(it, it, it) })
                val text = res.getString(word)
                label.set(res, "ask|$text", frame) { cv, r, w, h ->
                    cv.drawColor(ScColors.GoBody.toArgb())
                    TextPaint.line(cv, r, text, w / 2f, h * 0.66f, h * 0.48f, if (word == R.string.eye_on) ScColors.Go.toArgb() else TextPaint.argb(255, 240, 242, 245), w * 0.92f, 700)
                }
            }
        }
        // ---- merge countdown on the hood's front edge
        val mm = hud.mergeMetres
        mergeBar.show(mm != null)
        if (mm != null) {
            val p = HudLayout.MERGE_BAR.mirrorX(m)
            mergeBar.place(p, Layout.facing(p, eye), 0.50, 0.0625)
            val step = (mm / 5) * 5
            val text = res.getString(R.string.merge_left, step)
            mergeBar.set(res, "merge|$step", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 32f, TextPaint.argb(170, 18, 21, 25))
                val frac = (step / 80f).coerceIn(0f, 1f)
                TextPaint.roundRect(cv, 8f, h * 0.70f, 8f + (w - 16f) * frac, h - 10f, 12f, if (frac < 0.3f) ScColors.TimerLate.toArgb() else ScColors.Go.toArgb())
                TextPaint.line(cv, r, text, w / 2f, h * 0.58f, h * 0.48f, TextPaint.argb(255, 255, 255, 255), w * 0.9f, 700)
            }
        }
        // ---- game over card until the window shows
        val sum = hud.summary
        summary.show(sum != null)
        if (sum != null) {
            val p = HudLayout.CARD_BIG.mirrorX(m)
            summary.place(p, Layout.facing(p, eye), 0.50, 0.1875)
            val stars = "★".repeat(sum.stars) + "☆".repeat(3 - sum.stars)
            val line = res.getString(R.string.summary_3d, fmt(sum.total), stars)
            val second = if (sum.newBest) res.getString(R.string.result_record) else res.getString(R.string.result_best, fmt(sum.best))
            summary.set(res, "sum|$line|$second", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 40f, TextPaint.argb(205, 18, 21, 25))
                TextPaint.line(cv, r, line, w / 2f, h * 0.48f, 100f, TextPaint.argb(255, 255, 255, 255), w * 0.92f, 700)
                TextPaint.line(cv, r, second, w / 2f, h * 0.80f, 56f, ScColors.Go.toArgb(), w * 0.92f, 600)
            }
        }
        // ---- vignette while your car moves sideways (25 %, design scenarios[].anim)
        val want = if (c.egoMoving()) 0.25f else 0f
        vignetteLevel += (want - vignetteLevel) * 0.15f
        val vOn = vignetteLevel > 0.01f
        if (vignette.enabled != vOn) vignette.enabled = vOn
        if (vOn) {
            val hp = c.effHead
            vignetteMat.setOpacity(vignetteLevel / 0.25f * 0.6f)
            vignette.setLocal(position = head + Angles.dir(headYaw, hp?.pitch ?: 0.0) * 0.35, rotation = Layout.facing(head + Angles.dir(headYaw, hp?.pitch ?: 0.0) * 0.35, head), scale = Vec3(1.1, 1.1, 1.0))
        }
    }

    private data class Quad4(val root: Entity, val pos: Vec3, val label: TexLabel, val word: Int)

    private fun targetOf(e: Entity): Target? = holders.entries.firstOrNull { it.value === e }?.key

    private fun chipLabel(res: Resources, l: TexLabel, word: Int, lit: Boolean, pos: Vec3, face: Quat, frame: Long) {
        l.place(pos, face, HudLayout.CHIP_W, HudLayout.CHIP_H)
        val text = res.getString(word)
        l.set(res, "chip|$text|$lit", frame) { cv, r, w, h ->
            TextPaint.roundRect(cv, 4f, 4f, w - 4f, h - 4f, h / 2f - 4f, if (lit) ScColors.ChipOn.toArgb() else TextPaint.argb(225, 64, 68, 74))
            TextPaint.line(cv, r, (if (lit) "✓ " else "") + text, w / 2f, h * 0.68f, h * 0.50f, TextPaint.argb(255, 255, 255, 255), w * 0.86f, 700)
        }
    }

    private fun setTappable(t: Target, on: Boolean) {
        if (tapOn[t] == on) return
        tapOn[t] = on
        val h = holders[t] ?: return
        val shape = shapes[t] ?: return
        if (on) {
            h.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = physics))
            h.components.set(InteractableComponent())
            h.components.set(HoverEffectComponent())
        } else {
            h.components.remove(HoverEffectComponent::class.java)
            h.components.remove(InteractableComponent::class.java)
            h.components.remove(CollisionComponent::class.java)
        }
        ScLog.i("HUD tappable ${t.id}=$on")
    }

    private fun goText(res: Resources, hud: HudState): String =
        when (hud.goLabel) {
            GoLabel.CONTINUE -> res.getString(R.string.go_continue)
            GoLabel.SKIP -> res.getString(R.string.go_skip)
            GoLabel.GO -> when (hud.situation) {
                Situation.LANE_LEFT, Situation.LANE_RIGHT -> res.getString(if (hud.dir == Dir.LEFT) R.string.go_lc_l else R.string.go_lc_r)
                Situation.PULL_OUT -> res.getString(if (hud.dir == Dir.LEFT) R.string.go_po else R.string.go_po_r)
                Situation.MERGE -> res.getString(if (hud.dir == Dir.LEFT) R.string.go_mg else R.string.go_mg_r)
                Situation.DOOR -> res.getString(R.string.go_dr)
                null -> res.getString(R.string.go_continue)
            }
        }

    private fun scenarioText(res: Resources, s: Situation, dir: Dir): String =
        when (s) {
            Situation.LANE_LEFT, Situation.LANE_RIGHT -> res.getString(if (dir == Dir.LEFT) R.string.scn_lc_l else R.string.scn_lc_r)
            Situation.PULL_OUT -> res.getString(if (dir == Dir.LEFT) R.string.scn_po else R.string.scn_po_r)
            Situation.MERGE -> res.getString(if (dir == Dir.LEFT) R.string.scn_mg else R.string.scn_mg_r)
            Situation.DOOR -> res.getString(R.string.scn_dr)
        }

    private fun drawPop(cv: Canvas, r: Resources, w: Int, h: Int, p: ResultPop) {
        val (word, colour) = when (p.outcome) {
            Outcome.SAFE -> r.getString(R.string.out_safe) to ScColors.Go.toArgb()
            Outcome.WAITED -> r.getString(R.string.out_waited) to ScColors.Go.toArgb()
            Outcome.LUCKY -> r.getString(R.string.out_lucky) to ScColors.Lucky.toArgb()
            Outcome.MISS -> r.getString(R.string.out_miss) to ScColors.MissLight.toArgb()
            Outcome.TIMEOUT -> r.getString(R.string.out_timeout) to ScColors.GoIdle.toArgb()
        }
        TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 48f, TextPaint.argb(215, 16, 18, 22))
        val stars = if (p.outcome == Outcome.TIMEOUT || p.outcome == Outcome.MISS) "" else "★".repeat(p.stars) + "☆".repeat(3 - p.stars) + "  "
        val points = if (p.points > 0) "  " + r.getString(R.string.res_points, p.points) else ""
        TextPaint.line(cv, r, stars + word + points, w / 2f, h * 0.42f, 118f, colour, w * 0.94f, 700)
        val line = when (p.outcome) {
            Outcome.SAFE -> if (p.mirrorOk && p.shoulderOk) r.getString(R.string.res_reason_ok, p.reaction?.let { String.format(Locale.US, "%.1f", it) } ?: "–") else ""
            Outcome.WAITED -> r.getString(R.string.line_waited)
            Outcome.LUCKY -> if (!p.mirrorOk && !p.shoulderOk) r.getString(R.string.res_no_both) else if (!p.shoulderOk) r.getString(R.string.res_no_shoulder) else r.getString(R.string.res_no_mirror)
            Outcome.MISS -> when {
                p.door -> r.getString(R.string.line_miss_door)
                p.seenInMirror -> r.getString(R.string.line_miss_seen)
                p.trapSide == Dir.RIGHT -> r.getString(R.string.line_miss_trap_right)
                else -> r.getString(R.string.line_miss_trap_left)
            }
            Outcome.TIMEOUT -> r.getString(R.string.line_timeout)
        }
        if (line.isNotEmpty()) TextPaint.line(cv, r, line, w / 2f, h * 0.70f, 62f, TextPaint.argb(255, 240, 242, 245), w * 0.94f, 500)
        // rule hint, small, bottom line (design copy.tone: the source of the rule goes last, small)
        val hint = when {
            p.outcome == Outcome.LUCKY -> r.getString(R.string.line_lucky)
            p.door -> r.getString(R.string.line_door)
            p.outcome == Outcome.SAFE || p.outcome == Outcome.WAITED -> ""
            else -> r.getString(R.string.line_signal)
        }
        if (hint.isNotEmpty()) TextPaint.line(cv, r, hint, w / 2f, h * 0.91f, 46f, TextPaint.argb(255, 215, 219, 224), w * 0.94f, 500)
        if (p.combo >= 3) TextPaint.line(cv, r, r.getString(R.string.res_combo, p.combo), w - 120f, 60f, 40f, ScColors.Go.toArgb(), 220f, 700)
    }

    private fun wrap(cv: Canvas, r: Resources, text: String, w: Int, h: Int, size: Float) {
        val p = TextPaint.paint(r, size, TextPaint.argb(255, 255, 255, 255), 600)
        val maxW = w * 0.92f
        if (p.measureText(text) <= maxW) {
            cv.drawText(text, w / 2f, h * 0.60f, p)
            return
        }
        // two lines: split at the space / punctuation nearest the middle
        val mid = text.length / 2
        val cuts = text.indices.filter { text[it] == ' ' || text[it] == '，' || text[it] == '？' || text[it] == ',' || text[it] == '?' }
        val cut = cuts.minByOrNull { kotlin.math.abs(it - mid) } ?: mid
        val a = text.substring(0, cut + 1).trim()
        val b = text.substring(cut + 1).trim()
        TextPaint.fit(p, if (p.measureText(a) > p.measureText(b)) a else b, maxW, size)
        cv.drawText(a, w / 2f, h * 0.42f, p)
        cv.drawText(b, w / 2f, h * 0.82f, p)
    }

    private fun updateReplay(res: Resources, hud: HudState, cabin: Cabin, head: Vec3, headYaw: Double, nowMs: Long, frame: Long) {
        val m = side.m
        val on = hud.replay
        minimap.show(on)
        counter.show(on && hud.replayCount > 1)
        val hid = c.highlightId()
        val boxes = c.actorBoxes()
        val hz = boxes.firstOrNull { it.first.id == hid }?.second
        val ego = c.egoOffset()
        val showHazard = on && hz != null
        hazardRing.enabled = showHazard
        marker.show(showHazard)
        arrow.show(showHazard)
        if (!on) return
        val pm = HudLayout.MINIMAP.mirrorX(m)
        val face = Layout.facing(pm, Vec3.ZERO)
        minimap.place(pm, face, HudLayout.MINIMAP_W, HudLayout.MINIMAP_H)
        if (nowMs - lastMapMs > 150) {
            lastMapMs = nowMs
            val rd = c.displayRound()
            minimap.set(res, "map|$frame", frame) { cv, _, w, h -> drawMap(cv, w, h, rd.road.strips.map { it.type to (it.xMin to it.xMax) }, boxes.map { (a, b) -> Triple(b, a.id == hid, a.kind.rider) }, cabin, ego.first) }
        }
        if (hud.replayCount > 1) {
            val pc = (HudLayout.MINIMAP + Vec3(0.0, 0.10, 0.0)).mirrorX(m)
            counter.place(pc, Layout.facing(pc, Vec3.ZERO), 0.08, 0.04)
            val text = res.getString(R.string.replay_count, hud.replayIndex, hud.replayCount)
            counter.set(res, "cnt|$text", frame) { cv, r, w, h ->
                TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 30f, TextPaint.argb(180, 18, 21, 25))
                TextPaint.line(cv, r, text, w / 2f, h * 0.70f, h * 0.56f, TextPaint.argb(255, 255, 255, 255), w * 0.9f, 700)
            }
        }
        if (hz == null) return
        val centre = hz.centre - Vec3(ego.first, 0.0, ego.second)
        hazardRing.setLocal(position = Vec3(centre.x, Road.ROAD_Y + 0.03, centre.z), rotation = Layout.rotX(-90.0), scale = Vec3(max(hz.length, hz.width) * 1.5, max(hz.length, hz.width) * 1.5, 1.0))
        val top = centre + Vec3(0.0, hz.height + 0.6, 0.0)
        val pulse = 1.0 + 0.1 * kotlin.math.sin(nowMs / 160.0)
        marker.place(top, Layout.facing(top, head), 0.7 * pulse, 0.7 * pulse)
        marker.set(res, "marker", frame) { cv, _, w, h ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ScColors.Hazard.toArgb() }
            val path = Path().apply { moveTo(w / 2f, h - 10f); lineTo(20f, 40f); lineTo(w - 20f, 40f); close() }
            cv.drawPath(path, p)
        }
        // arrow 0.65 m from your head (D14 ≥ 0.6 m), on the hazard's side, turned at most 70° off the nose so it is in view
        val yawTo = Angles.yawOf(centre + Vec3(0.0, 0.8, 0.0) - head)
        val shown = yawTo.coerceIn(-70.0, 70.0)
        val ap = head + Angles.dir(shown, -12.0) * 0.65
        arrow.place(ap, Layout.facing(ap, head), 0.26, 0.13)
        val pointsLeft = yawTo < shown - 1 || (yawTo < 0 && shown <= -69.0)
        val pointsRight = yawTo > shown + 1 || (yawTo > 0 && shown >= 69.0)
        val word = res.getString(R.string.replay_here)
        val label = when {
            pointsLeft -> "← $word"
            pointsRight -> "$word →"
            else -> "↓ $word"
        }
        arrow.set(res, "arrow|$label", frame) { cv, r, w, h ->
            TextPaint.roundRect(cv, 0f, 0f, w.toFloat(), h.toFloat(), 50f, ScColors.Hazard.toArgb())
            TextPaint.line(cv, r, label, w / 2f, h * 0.68f, h * 0.48f, TextPaint.argb(255, 0, 0, 0), w * 0.92f, 700)
        }
    }

    /** Top view of the moment: lanes, your car, the two blind-spot sectors (orange), the road users (hazard ringed). */
    private fun drawMap(cv: Canvas, w: Int, h: Int, strips: List<Pair<io.embodit.shouldercheck.domain.StripType, Pair<Double, Double>>>, boxes: List<Triple<io.embodit.shouldercheck.domain.Box, Boolean, Boolean>>, cabin: Cabin, egoDx: Double) {
        cv.drawColor(TextPaint.argb(255, 30, 33, 37))
        val ppm = 17.0
        val cx = w / 2.0 - cabin.carCentreX * ppm - egoDx * ppm
        val zTop = -4.0 // metres ahead at the top edge
        fun px(x: Double) = (cx + x * ppm).toFloat()
        fun py(z: Double) = ((z - zTop) * ppm).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((t, xr) in strips) {
            p.color = when (t) {
                io.embodit.shouldercheck.domain.StripType.BIKE_LANE -> TextPaint.argb(255, 60, 105, 70)
                io.embodit.shouldercheck.domain.StripType.SIDEWALK -> TextPaint.argb(255, 110, 108, 104)
                io.embodit.shouldercheck.domain.StripType.PARKING -> TextPaint.argb(255, 70, 73, 77)
                else -> TextPaint.argb(255, 82, 86, 90)
            }
            cv.drawRect(px(xr.first), 0f, px(xr.second), h.toFloat(), p)
        }
        // blind spots (default mirror aim 5°): beside / behind you, outside your body, 0.5 – 3.5 m out, 0 – 4.5 m back
        p.color = TextPaint.argb(150, 255, 138, 31)
        val bodyL = cabin.bodyLeft + egoDx
        val bodyR = cabin.bodyRight + egoDx
        cv.drawRect(px(bodyL - 3.5), py(-0.5), px(bodyL - 0.5), py(4.5), p)
        cv.drawRect(px(bodyR + 0.5), py(-0.5), px(bodyR + 3.5), py(4.5), p)
        // your car
        p.color = TextPaint.argb(255, 250, 250, 250)
        cv.drawRoundRect(RectF(px(bodyL), py(cabin.egoFrontZ), px(bodyR), py(cabin.egoRearZ)), 10f, 10f, p)
        for ((b, hl, rider) in boxes) {
            p.color = if (hl) ScColors.Hazard.toArgb() else if (rider) TextPaint.argb(255, 120, 200, 255) else TextPaint.argb(255, 200, 205, 212)
            val x0 = px(b.centre.x - b.width / 2)
            val x1 = px(b.centre.x + b.width / 2)
            val z0 = py(b.centre.z - b.length / 2)
            val z1 = py(b.centre.z + b.length / 2)
            if (z1 < 0 || z0 > h) continue
            cv.drawRoundRect(RectF(x0, max(z0, 0f), x1, min(z1, h.toFloat())), 6f, 6f, p)
            if (hl) {
                val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = TextPaint.argb(255, 255, 255, 255) }
                cv.drawRoundRect(RectF(x0 - 4, max(z0, 0f) - 4, x1 + 4, min(z1, h.toFloat()) + 4), 8f, 8f, s)
            }
        }
    }

    fun close() {
        listOf(goLabel, cardSub, pauseChip, askOnLabel, askOffLabel, askCard, card, cardSmall, chipMirror, chipShoulder, combo, pop, lookForward, minimap, arrow, marker, mergeBar, summary, counter).forEach { it.close() }
    }

    companion object {
        const val RING_SEGMENTS = 24
        const val POP_W = 0.70

        /** DEVIATION (within tolerance): 0.14 m instead of ≤ 0.11 m so the English line keeps a readable letter height. */
        const val CARD_SMALL_W = 0.14

        /** Guide / eye-off line above the small card: 0.26 m wide, two lines (≈ 1° letters at 0.75 m). */
        const val SUB_W = 0.26

        private fun fmt(n: Int): String = String.format(Locale.US, "%,d", n)
    }
}

