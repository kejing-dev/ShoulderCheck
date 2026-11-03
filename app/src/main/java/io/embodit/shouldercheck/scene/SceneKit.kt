package io.embodit.shouldercheck.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat as SdkQuat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.RoadLayout
import io.embodit.shouldercheck.domain.StripType
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.ui.theme.ScColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------------- conversions

internal fun Vec3.sdk(): Vector3 = Vector3(x.toFloat(), y.toFloat(), z.toFloat())

internal fun Vector3.domain(): Vec3 = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

internal fun Quat.sdk(): SdkQuat = SdkQuat(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

internal fun SdkQuat.domain(): Quat = Quat(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble())

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal fun argbColor(argb: Int): Color = Color(argb)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

/**
 * Overall opacity of an entity and its children — only for the D20 sight-cone fade of cabin parts. At 1.0 the
 * component is REMOVED (a component at 1.0 still renders the entity blended: factory finding, 走进乐团 v0.1.4).
 */
internal fun Entity.setFade(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (opacity >= 0.999f) {
        if (existing != null) components.remove(OpacityControllerComponent::class.java)
        return
    }
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.hasOpacityComponent(): Boolean = components[OpacityControllerComponent::class.java] != null

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1. */
internal const val DP_M = 0.0008

/** Resources shared by many entities: made global, closed when the scene goes. */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

internal fun unlit(
    shared: SharedResources,
    color: Color,
    blending: BlendingMode = BlendingMode.OPAQUE,
    opacity: Float = 1f,
    twoSided: Boolean = false,
    depthWrite: Boolean = true,
    texture: TextureResource? = null,
): UnlitMaterial =
    shared.keep(
        UnlitMaterial.create(blending).apply {
            setBaseColor(color.copy(alpha = 1f).color4())
            if (blending != BlendingMode.OPAQUE) setOpacity(opacity)
            runCatching { setApplyToneMapping(false) }
            if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            if (!depthWrite) setDepthWrite(false)
            if (texture != null) setBaseColorTexture(texture)
        }
    )

/**
 * Matte PBR with a little emissive of its own colour: in Full immersion the only light is the app's directional light
 * (no IBL), faces turned away from it would be black (mri-rehearsal / moon-phases finding).
 */
internal fun pbr(shared: SharedResources, color: Color, roughness: Float = 0.85f, ambient: Float = AMBIENT, twoSided: Boolean = false): PhysicallyBasedMaterial =
    shared.keep(
        PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColor(color.color4())
            setRoughness(roughness)
            setMetallic(0f)
            runCatching { setEmissiveColor(Color4(color.red * ambient, color.green * ambient, color.blue * ambient, 1f)) }
            if (twoSided) setCullingMode(MaterialCullingMode.NONE)
        }
    )

internal const val AMBIENT = 0.30f

internal fun Color.shade(k: Float): Color = Color((red * k).coerceIn(0f, 1f), (green * k).coerceIn(0f, 1f), (blue * k).coerceIn(0f, 1f), alpha)

// ---------------------------------------------------------------------------------------------------- meshes

/** Collects triangles; every quad is wound counter-clockwise seen from its normals' side (auto winding, mri-rehearsal). */
internal class MeshBuilder {
    private val positions = ArrayList<Vector3>()
    private val normals = ArrayList<Vector3>()
    private val uvs = ArrayList<Vector2>()
    private val indices = ArrayList<Int>()
    private var lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    private var hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)

    fun vertex(p: Vec3, n: Vec3, u: Double, v: Double): Int {
        positions += p.sdk()
        normals += n.sdk()
        uvs += Vector2(u.toFloat(), v.toFloat())
        lo = doubleArrayOf(minOf(lo[0], p.x), minOf(lo[1], p.y), minOf(lo[2], p.z))
        hi = doubleArrayOf(maxOf(hi[0], p.x), maxOf(hi[1], p.y), maxOf(hi[2], p.z))
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int, normal: Vec3) {
        val pa = positions[a].domain()
        val pb = positions[b].domain()
        val pc = positions[c].domain()
        val face = (pb - pa).cross(pc - pa)
        if (face.dot(normal) < 0) indices += listOf(a, c, b) else indices += listOf(a, b, c)
    }

    /**
     * Quad p0 p1 p2 p3 in order round it. Texture: u 0 → 1 from p0 to p1, v 0 at p0–p1 and 1 at p3–p2. The engine
     * samples v from the bitmap's BOTTOM row (factory finding, F↑ test): v = 0 shows the bitmap's bottom.
     */
    fun quad(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, n: Vec3, u0: Double = 0.0, u1: Double = 1.0, v0: Double = 0.0, v1: Double = 1.0) {
        val a = vertex(p0, n, u0, v0)
        val b = vertex(p1, n, u1, v0)
        val c = vertex(p2, n, u1, v1)
        val d = vertex(p3, n, u0, v1)
        tri(a, b, c, n)
        tri(a, c, d, n)
    }

    fun box(lo: Vec3, hi: Vec3) {
        val x0 = lo.x
        val y0 = lo.y
        val z0 = lo.z
        val x1 = hi.x
        val y1 = hi.y
        val z1 = hi.z
        quad(Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1), Vec3(0.0, 0.0, 1.0))
        quad(Vec3(x1, y0, z0), Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x1, y1, z0), Vec3(0.0, 0.0, -1.0))
        quad(Vec3(x1, y0, z1), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(1.0, 0.0, 0.0))
        quad(Vec3(x0, y0, z0), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x0, y1, z0), Vec3(-1.0, 0.0, 0.0))
        quad(Vec3(x0, y1, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0), Vec3(x0, y1, z0), Vec3(0.0, 1.0, 0.0))
        quad(Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y0, z1), Vec3(x0, y0, z1), Vec3(0.0, -1.0, 0.0))
    }

    fun build(name: String): MeshResource {
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
        val c = Vec3((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2)
        val h = Vec3(maxOf((hi[0] - lo[0]) / 2, 0.001), maxOf((hi[1] - lo[1]) / 2, 0.001), maxOf((hi[2] - lo[2]) / 2, 0.001))
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = c.sdk(), halfExtent = h.sdk()), name = name)
    }
}

internal object Meshes {
    /** Unit cube −0.5 … 0.5, scaled per entity (`createBox` renders 2× on the emulator: factory finding). */
    fun unitCube(): MeshResource = MeshBuilder().apply { box(Vec3(-0.5, -0.5, -0.5), Vec3(0.5, 0.5, 0.5)) }.build("unitCube")

    /** Sphere of diameter 1. [inward] = normals and winding to the inside (the sky dome); v 0 at the bottom pole. */
    fun sphere(name: String, inward: Boolean = false, rings: Int = 14, sectors: Int = 24): MeshResource {
        val b = MeshBuilder()
        for (i in 0 until rings) {
            val t0 = PI * i / rings
            val t1 = PI * (i + 1) / rings
            for (j in 0 until sectors) {
                val p0 = 2 * PI * j / sectors
                val p1 = 2 * PI * (j + 1) / sectors
                fun pt(t: Double, p: Double) = Vec3(sin(t) * cos(p), cos(t), sin(t) * sin(p)) * 0.5
                val a = pt(t0, p0)
                val bb = pt(t0, p1)
                val c = pt(t1, p1)
                val d = pt(t1, p0)
                val mid = (a + bb + c + d) * 0.25
                val n = if (inward) -mid.normalized() else mid.normalized()
                val va = b.vertex(a, if (inward) -a.normalized() else a.normalized(), j / sectors.toDouble(), 1.0 - i / rings.toDouble())
                val vb = b.vertex(bb, if (inward) -bb.normalized() else bb.normalized(), (j + 1) / sectors.toDouble(), 1.0 - i / rings.toDouble())
                val vc = b.vertex(c, if (inward) -c.normalized() else c.normalized(), (j + 1) / sectors.toDouble(), 1.0 - (i + 1) / rings.toDouble())
                val vd = b.vertex(d, if (inward) -d.normalized() else d.normalized(), j / sectors.toDouble(), 1.0 - (i + 1) / rings.toDouble())
                b.tri(va, vb, vc, n)
                b.tri(va, vc, vd, n)
            }
        }
        return b.build(name)
    }

    /** Torus in the local XY plane (facing +Z): ring diameter 1, tube radius [tube] (the steering wheel). */
    fun ring(name: String, tube: Double = 0.05, seg: Int = 40, sides: Int = 8): MeshResource {
        val b = MeshBuilder()
        val r = 0.5
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            for (j in 0 until sides) {
                val s0 = 2 * PI * j / sides
                val s1 = 2 * PI * (j + 1) / sides
                fun pt(a: Double, s: Double): Pair<Vec3, Vec3> {
                    val radial = Vec3(cos(a), sin(a), 0.0)
                    val n = radial * cos(s) + Vec3(0.0, 0.0, sin(s))
                    return (radial * r + n * tube) to n
                }
                val (p00, n00) = pt(a0, s0)
                val (p10, n10) = pt(a1, s0)
                val (p11, n11) = pt(a1, s1)
                val (p01, n01) = pt(a0, s1)
                val v0 = b.vertex(p00, n00, 0.0, 0.0)
                val v1 = b.vertex(p10, n10, 1.0, 0.0)
                val v2 = b.vertex(p11, n11, 1.0, 1.0)
                val v3 = b.vertex(p01, n01, 0.0, 1.0)
                val n = (n00 + n10 + n11 + n01).normalized()
                b.tri(v0, v1, v2, n)
                b.tri(v0, v2, v3, n)
            }
        }
        return b.build(name)
    }

    /** Flat disc of diameter 1 in local XY facing +Z; UVs span the texture (radial glow). Two-sided by material. */
    fun disc(name: String, seg: Int = 32): MeshResource {
        val b = MeshBuilder()
        val n = Vec3(0.0, 0.0, 1.0)
        val c = b.vertex(Vec3.ZERO, n, 0.5, 0.5)
        val rim = (0..seg).map { i ->
            val a = 2 * PI * i / seg
            b.vertex(Vec3(0.5 * cos(a), 0.5 * sin(a), 0.0), n, 0.5 + 0.5 * cos(a), 0.5 + 0.5 * sin(a))
        }
        for (i in 0 until seg) b.tri(c, rim[i], rim[i + 1], n)
        return b.build(name)
    }

    /**
     * Road surface: local x 0 … 1 (scaled to the road width), z from [zFrom] to [zTo] in [segment]-metre quads, each
     * with the full texture (one lane-dash cycle) so no texture repeat is needed. Facing +Y.
     */
    fun road(name: String, zFrom: Double, zTo: Double, segment: Double): MeshResource {
        val b = MeshBuilder()
        val up = Vec3(0.0, 1.0, 0.0)
        var z = zFrom
        while (z < zTo - 1e-6) {
            val z1 = minOf(zTo, z + segment)
            // v = 0 at the far (-z) end of a segment: a texture drawn "F↑" with ↑ toward the bitmap top points to -Z
            b.quad(Vec3(0.0, 0.0, z1), Vec3(1.0, 0.0, z1), Vec3(1.0, 0.0, z), Vec3(0.0, 0.0, z), up, v0 = 0.0, v1 = (z1 - z) / segment)
            z = z1
        }
        return b.build(name)
    }

    /** Upright quad, local XY, facing +Z, centred, 1 × 1 (the F↑ wall test card). */
    fun quad(name: String): MeshResource =
        MeshBuilder().apply { quad(Vec3(-0.5, -0.5, 0.0), Vec3(0.5, -0.5, 0.0), Vec3(0.5, 0.5, 0.0), Vec3(-0.5, 0.5, 0.0), Vec3(0.0, 0.0, 1.0)) }.build(name)
}

// ---------------------------------------------------------------------------------------------------- textures

internal object Textures {
    /*
     * The engine samples v = 0 at the bitmap's BOTTOM row (factory finding, F↑ test card): bitmaps are drawn as they
     * should look (top row = top) and every mesh puts v = 0 on its bottom / near edge. On the road mesh v grows
     * towards −Z, so the bitmap top points ahead. Checked on the emulator with debug `testtex 1`.
     */

    private fun Color.argb(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    const val PX_PER_M = 24.0
    const val DASH_CYCLE_M = 9.0

    /**
     * One 9 m cycle of the road between [xMin] and [xMax] (physical x): lane paint, green bike lane, parking strip,
     * dashed (3 m on / 6 m off) and solid lines from the layout. Top of the bitmap = ahead (−Z).
     */
    fun road(layout: RoadLayout, xMin: Double, xMax: Double): Bitmap {
        val w = ((xMax - xMin) * PX_PER_M).toInt().coerceIn(16, 1024)
        val h = (DASH_CYCLE_M * PX_PER_M).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ScColors.Road.argb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun px(x: Double) = ((x - xMin) / (xMax - xMin) * w).toFloat()
        for (s in layout.strips) {
            when (s.type) {
                StripType.BIKE_LANE -> {
                    p.color = ScColors.BikeLane.argb()
                    c.drawRect(px(s.xMin), 0f, px(s.xMax), h.toFloat(), p)
                }
                StripType.PARKING -> {
                    p.color = ScColors.Road.shade(0.93f).argb()
                    c.drawRect(px(s.xMin), 0f, px(s.xMax), h.toFloat(), p)
                }
                else -> Unit
            }
        }
        p.color = ScColors.LaneLine.argb()
        val lw = (0.15 * PX_PER_M).toFloat().coerceAtLeast(2f)
        for (x in layout.solidLines) c.drawRect(px(x) - lw / 2, 0f, px(x) + lw / 2, h.toFloat(), p)
        val dash = (3.0 * PX_PER_M).toFloat()
        for (x in layout.dashedLines) c.drawRect(px(x) - lw / 2, (h - dash) / 2f, px(x) + lw / 2, (h + dash) / 2f, p)
        // bike symbol arrow on the bike lane: an up arrow (points ahead) — the asymmetric mark that also shows the v direction
        layout.strips.firstOrNull { it.type == StripType.BIKE_LANE }?.let { s ->
            val cx = (px(s.xMin) + px(s.xMax)) / 2
            val path = android.graphics.Path().apply {
                moveTo(cx, h * 0.18f)
                lineTo(cx - 0.35f * PX_PER_M.toFloat(), h * 0.18f + 0.6f * PX_PER_M.toFloat())
                lineTo(cx + 0.35f * PX_PER_M.toFloat(), h * 0.18f + 0.6f * PX_PER_M.toFloat())
                close()
            }
            p.color = ScColors.LaneLine.copy(alpha = 0.85f).argb()
            c.drawPath(path, p)
            c.drawRect(cx - 0.06f * PX_PER_M.toFloat(), h * 0.18f + 0.6f * PX_PER_M.toFloat(), cx + 0.06f * PX_PER_M.toFloat(), h * 0.18f + 1.6f * PX_PER_M.toFloat(), p)
        }
        return bmp
    }

    /** The asymmetric test card "F↑" (taste D10, debug `testtex 1`). */
    fun orientationTest(size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(255, 241, 214))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(23, 50, 77)
            textSize = size * 0.55f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("F↑", size / 2f, size * 0.68f, paint)
        paint.color = android.graphics.Color.rgb(200, 40, 40)
        canvas.drawRect(0f, 0f, size * 0.15f, size * 0.15f, paint)
        return bitmap
    }

    /** Vertical gradient (bottom colour [low] → top colour [high]). */
    fun gradient(low: Color, high: Color, h: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(4, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val t = 1f - y / (h - 1f) // 1 at the top row
            val c = Color(low.red + (high.red - low.red) * t, low.green + (high.green - low.green) * t, low.blue + (high.blue - low.blue) * t, 1f)
            for (x in 0 until 4) bmp.setPixel(x, y, c.argb())
        }
        return bmp
    }

    /** Sky dome: horizon colour below / at the horizon, lighter overhead (palette street.sky #BFD9EE → #EAF3FA). */
    fun sky(h: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(4, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val elev = 90.0 - 180.0 * y / (h - 1) // top row = zenith
            val t = (elev / 60.0).coerceIn(0.0, 1.0).toFloat()
            val low = ScColors.SkyLow
            val high = ScColors.SkyHigh
            val c = if (elev < -2) ScColors.Sidewalk.shade(0.8f) else Color(low.red + (high.red - low.red) * t, low.green + (high.green - low.green) * t, low.blue + (high.blue - low.blue) * t, 1f)
            for (x in 0 until 4) bmp.setPixel(x, y, c.argb())
        }
        return bmp
    }

    /** White disc fading out to the rim (gaze dot glow, guide rings). */
    fun radial(size: Int = 64, ring: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = (size - 1) / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val r = kotlin.math.hypot(x - c, y - c) / c
            val a =
                if (ring) (if (r > 1f) 0f else (1f - kotlin.math.abs(r - 0.78f) / 0.22f).coerceIn(0f, 1f))
                else if (r >= 1f) 0f else Math.pow((1.0 - r), 1.4).toFloat()
            bmp.setPixel(x, y, android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255))
        }
        return bmp
    }
}
