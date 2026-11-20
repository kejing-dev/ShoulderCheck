package io.embodit.shouldercheck.scene

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.ui.theme.scTypeface

/**
 * Text for the 3D HUD (design `hud3d`: during a round there is no 2D panel, mustPass M7): words are drawn on Canvas
 * bitmaps with the app's own type family (Noto Sans + Noto Sans SC subset) and shown on unlit quads in the car. The
 * engine samples v = 0 at the bitmap's bottom row, which the quad mesh already accounts for (F↑ test, v0.1.0).
 */
internal object TextPaint {
    private val cache = HashMap<Int, Typeface>()

    fun face(res: Resources, weight: Int): Typeface = cache.getOrPut(weight) { scTypeface(res, weight) ?: Typeface.create(Typeface.DEFAULT, weight, false) }

    fun paint(res: Resources, sizePx: Float, color: Int, weight: Int = 600, align: Paint.Align = Paint.Align.CENTER): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face(res, weight)
            textSize = sizePx
            this.color = color
            textAlign = align
            isSubpixelText = true
        }

    /** Largest size ≤ [sizePx] at which [text] fits [maxW] px (labels never clip: D13). */
    fun fit(p: Paint, text: String, maxW: Float, sizePx: Float): Paint {
        p.textSize = sizePx
        while (p.measureText(text) > maxW && p.textSize > 8f) p.textSize *= 0.94f
        return p
    }

    /** Draws [text] centred on (cx, baseline y) fitted to [maxW]. */
    fun line(c: Canvas, res: Resources, text: String, cx: Float, y: Float, sizePx: Float, color: Int, maxW: Float, weight: Int = 600) {
        val p = fit(paint(res, sizePx, color, weight), text, maxW, sizePx)
        c.drawText(text, cx, y, p)
    }

    fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, p)
    }

    fun argb(a: Int, r: Int, g: Int, b: Int): Int = android.graphics.Color.argb(a, r, g, b)
}

/**
 * One textured quad label: its own transparent unlit material, the texture redrawn only when its [key] changes; the old
 * texture is closed a few frames later (never while a frame might still sample it). Resources are made global and kept
 * by the scene (D59).
 */
internal class TexLabel(
    private val shared: SharedResources,
    quad: MeshResource,
    name: String,
    parent: Entity?,
    private val wPx: Int,
    private val hPx: Int,
    /** Opaque labels (button faces) write depth; floating text does not. */
    opaque: Boolean = false,
) {
    val material: UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(if (opaque) BlendingMode.OPAQUE else BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                if (!opaque) {
                    setOpacity(1f)
                    setDepthWrite(false)
                }
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )
    val entity: Entity = ModelEntity(quad, material).named(name).also { parent?.addChild(it); it.enabled = false }
    private var key: String? = null
    private var current: TextureResource? = null
    private val retiring = ArrayDeque<Pair<Long, TextureResource>>()

    /** Redraws when [k] differs from the last key. */
    fun set(res: Resources, k: String, frame: Long, draw: (Canvas, Resources, Int, Int) -> Unit) {
        retire(frame)
        if (k == key) return
        key = k
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), res, wPx, hPx)
        val tex = runCatching { TextureResource.create(bmp).also { it.toGlobal() } }.onFailure { ScLog.e("HUD texture failed: $it") }.getOrNull() ?: return
        material.setBaseColorTexture(tex)
        current?.let { retiring.addLast(frame to it) }
        current = tex
    }

    private fun retire(frame: Long) {
        while (retiring.isNotEmpty() && frame - retiring.first().first > 4) runCatching { retiring.removeFirst().second.close() }
    }

    fun show(on: Boolean) {
        if (entity.enabled != on) entity.enabled = on
    }

    fun place(pos: Vec3, rot: Quat, w: Double, h: Double) {
        entity.setLocal(position = pos, rotation = rot, scale = Vec3(w, h, 1.0))
    }

    fun close() {
        runCatching { current?.close() }
        for ((_, t) in retiring) runCatching { t.close() }
        retiring.clear()
        current = null
        key = null
    }
}
