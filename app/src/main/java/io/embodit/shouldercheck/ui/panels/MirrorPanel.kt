package io.embodit.shouldercheck.ui.panels

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.embodit.shouldercheck.domain.MiniJson
import io.embodit.shouldercheck.domain.MiniJson.list
import io.embodit.shouldercheck.domain.MiniJson.num
import io.embodit.shouldercheck.domain.MiniJson.obj
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.scene.MPoly
import io.embodit.shouldercheck.scene.MirrorFrame
import io.embodit.shouldercheck.scene.MirrorStore
import io.embodit.shouldercheck.ui.theme.ScColors

/** One pre-rendered silhouette (assets/silhouettes, front views for mirrors) with its scale and ground point. */
class Silhouette(val image: ImageBitmap, val pxPerM: Float, val groundX: Float, val groundY: Float)

/** Loads the silhouettes once per process. */
object MirrorArt {
    @Volatile private var cache: Map<String, Silhouette>? = null

    fun get(context: Context): Map<String, Silhouette> {
        cache?.let { return it }
        val out = HashMap<String, Silhouette>()
        runCatching {
            val json = context.assets.open("silhouettes/silhouettes.json").bufferedReader().use { it.readText() }
            val images = (MiniJson.parse(json) as? Map<*, *>)?.obj("images") ?: emptyMap<String, Any?>()
            for ((file, meta) in images) {
                val m = meta as? Map<*, *> ?: continue
                val name = file.toString().removeSuffix(".png")
                if (!(name.endsWith("_front") || name.endsWith("_fl") || name.endsWith("_fr"))) continue
                val bmp = context.assets.open("silhouettes/$file").use { BitmapFactory.decodeStream(it) } ?: continue
                val g = m.list("groundPx")
                out[name] = Silhouette(bmp.asImageBitmap(), (m.num("pxPerM") ?: 140.0).toFloat(), (g?.getOrNull(0) as? Number)?.toFloat() ?: bmp.width / 2f, (g?.getOrNull(1) as? Number)?.toFloat() ?: bmp.height.toFloat())
            }
        }.onFailure { ScLog.e("MIRROR silhouettes failed: $it", it) }
        ScLog.i("MIRROR silhouettes loaded ${out.size}")
        cache = out
        return out
    }
}

/**
 * A mirror (design panels[MIR]): an opaque attachment panel with a Canvas, not clickable (no clickable content). The
 * picture comes from the scene ~20 Hz as a [MirrorFrame]: sky / ground, road strips, lane lines, kerb, buildings,
 * the road users' silhouettes, your own car's flank (side mirrors) or the rear window (inner mirror). v = 0 is the
 * bottom of the glass. Silhouettes are drawn mirror-reversed (they are camera renders).
 */
@Composable
fun MirrorPanel(id: MirrorId, widthDp: Int, heightDp: Int) {
    val context = LocalContext.current
    val art = remember { MirrorArt.get(context) }
    val state = MirrorStore.frames.getValue(id)
    Canvas(modifier = Modifier.size(widthDp.dp, heightDp.dp).clip(RoundedCornerShape(if (id == MirrorId.INNER) 18.dp else 24.dp))) {
        val f = state.value
        MirrorStore.draws++
        val w = size.width
        val h = size.height
        val horizonY = ((1f - (f?.horizonV ?: 0.55f)) * h).coerceIn(-h, 2 * h)
        drawRect(Brush.verticalGradient(listOf(ScColors.SkyHigh, ScColors.SkyLow), startY = 0f, endY = horizonY.coerceAtLeast(1f)), size = Size(w, horizonY.coerceIn(0f, h)))
        if (horizonY < h) drawRect(ScColors.Sidewalk.copy(alpha = 1f), topLeft = Offset(0f, horizonY.coerceAtLeast(0f)), size = Size(w, h - horizonY.coerceAtLeast(0f)))
        if (f == null) return@Canvas
        for (p in f.polys) poly(p, w, h)
        for (s in f.sprites) {
            val sil = art[s.image] ?: continue
            val pxPerCanvas = s.vPerM * h / sil.pxPerM
            if (pxPerCanvas <= 0f || pxPerCanvas > 20f) continue
            val iw = sil.image.width * pxPerCanvas
            val ih = sil.image.height * pxPerCanvas
            val gx = s.u * w
            val gy = (1f - s.v) * h
            // mirror-reversed: the ground point x measured from the right edge
            val left = gx - (sil.image.width - sil.groundX) * pxPerCanvas
            val top = gy - sil.groundY * pxPerCanvas
            withTransform({ scale(-1f, 1f, Offset(left + iw / 2, top + ih / 2)) }) {
                drawImage(sil.image, IntOffset.Zero, IntSize(sil.image.width, sil.image.height), IntOffset(left.toInt(), top.toInt()), IntSize(iw.toInt().coerceAtLeast(1), ih.toInt().coerceAtLeast(1)))
            }
            if (s.highlight) {
                drawRoundRect(ScColors.Miss, Offset(left - 4f, top - 4f), Size(iw + 8f, ih + 8f), CornerRadius(8f, 8f), style = Stroke(width = 5f))
            }
        }
        for (p in f.front) poly(p, w, h)
        f.window?.let { win ->
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
                moveTo(win[0] * w, (1f - win[1]) * h)
                var i = 2
                while (i < win.size) {
                    lineTo(win[i] * w, (1f - win[i + 1]) * h)
                    i += 2
                }
                close()
            }
            drawPath(path, ScColors.Headliner.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(it, ScColors.Trim, 0.55f) })
        }
        // the glass edge
        drawRoundRect(ScColors.MirrorFrame, Offset.Zero, size, CornerRadius(16f, 16f), style = Stroke(width = 6f))
    }
}

private fun DrawScope.poly(p: MPoly, w: Float, h: Float) {
    val pts = p.pts
    if (pts.size < 6) return
    val path = Path()
    path.moveTo(pts[0] * w, (1f - pts[1]) * h)
    var i = 2
    while (i < pts.size) {
        path.lineTo(pts[i] * w, (1f - pts[i + 1]) * h)
        i += 2
    }
    path.close()
    drawPath(path, p.color)
}

@Suppress("unused")
private fun MirrorFrame.debug(): String = "seq=$seq polys=${polys.size} sprites=${sprites.size}"
