package io.embodit.shouldercheck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.shouldercheck.ui.theme.ScColors
import java.util.concurrent.ConcurrentHashMap

/** Minimum height of every control (profile B19: < 48 dp is never within tolerance). */
internal val MinTouch = 48.dp

/**
 * Debug-only button registry: every worded button registers its click under a stable name while composed, so the
 * debug hook `tap <name>` presses exactly what a pointer press would (same callback).
 */
object DebugProbe {
    private val buttons = ConcurrentHashMap<String, () -> Unit>()

    fun register(name: String, onClick: () -> Unit) {
        buttons[name] = onClick
    }

    fun unregister(name: String, onClick: () -> Unit) {
        buttons.remove(name, onClick)
    }

    fun press(name: String): Boolean {
        val f = buttons[name] ?: return false
        f()
        return true
    }

    fun names(): List<String> = buttons.keys.sorted()
}

@Composable
internal fun Probe(name: String, onClick: () -> Unit) {
    val latest = rememberUpdatedState(onClick)
    DisposableEffect(name) {
        val f = { latest.value() }
        DebugProbe.register(name, f)
        onDispose { DebugProbe.unregister(name, f) }
    }
}

/** Glass root of a panel at its full fixed size (a glass that hugs its content leaves the rest black: factory finding). */
@Composable
internal fun Glass(width: Dp, height: Dp, corner: Dp = 32.dp, padding: Dp = 24.dp, spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(corner)).backgroundMaterial(enable = true, style = Material.Regular).then(glassRefresh()).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
    }
}

internal object ScType {
    val task: TextStyle
        @Composable get() = PicoTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)

    /** Step blocks: labelLarge raised to 18 (design typography: 9 mm at 1.10 m). */
    val chip: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val metric: TextStyle
        @Composable get() = PicoTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum, lnum")

    /** B-pillar readout: 32 dp, tabular figures. */
    val ang: TextStyle
        @Composable get() = PicoTheme.typography.displaySmall.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val button: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
}

/**
 * A worded button, 48 dp high: [primary] = the one emphasised action (interaction fill, ink text), else the tertiary
 * fill. Icon buttons always carry their words (B19).
 */
@Composable
internal fun WordButton(
    name: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    iconRes: Int? = null,
    minWidth: Dp = 96.dp,
    // 52 dp: the anti-aliased edge of a 48 dp capsule measured 45–46 dp in the HD emulator shot (ui-audit c)
    height: Dp = 52.dp,
) {
    Probe(name, onClick)
    val container =
        when {
            danger -> PicoTheme.colorScheme.error
            primary -> PicoTheme.colorScheme.interaction
            else -> PicoTheme.colorScheme.fillTertiary
        }
    val content = if (primary || danger) ScColors.Ink else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        size = ButtonDefaults.Regular.copy(height = height, minWidth = minWidth),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (iconRes != null) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ScType.button)
    }
}

@Composable
internal fun LinkButton(name: String, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Probe(name, onClick)
    // labelPrimary + underline: the default link blue measured 1.8–2.1 : 1 on the light glass (ui-audit b)
    Link(onClick = onClick, modifier = modifier.heightIn(min = MinTouch)) {
        Text(text, maxLines = 1, style = PicoTheme.typography.labelLarge.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary)
    }
}

/** Title row: title on the left, optional trailing buttons. */
@Composable
internal fun TitleRow(title: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(modifier = modifier.fillMaxWidth().heightIn(min = MinTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (leading != null) leading()
        Text(text = title, style = PicoTheme.typography.headlineSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Status shown with icon + words (D13: never by colour alone): a coloured icon disc with a light rim, labelPrimary text. */
@Composable
internal fun StatusLabel(iconRes: Int, text: String, color: Color, style: TextStyle = PicoTheme.typography.titleMedium, iconSize: Dp = 22.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(iconSize + 4.dp)) { drawCircle(ScColors.IconDisc) }
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(iconSize), tint = color)
        }
        Text(text, style = style, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A SpatialUI segment control (language, traffic side, volume detents, replay speed). */
@Composable
internal fun Segments(name: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit, width: Dp, height: Dp = MinTouch) {
    val itemWidth = width / items.size
    SegmentControl(modifier = Modifier.width(width).height(height), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, text ->
            val click = { onSelect(i) }
            Probe("$name$i", click)
            SegmentItem(
                selected = selected == i,
                onClick = click,
                modifier = Modifier.width(itemWidth).height(height),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.interaction.copy(alpha = 0.30f),
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { Text(text = text, maxLines = 1, style = PicoTheme.typography.labelLarge) },
            )
        }
    }
}

/** A labelled settings row, ≥ 56 dp (design settings). */
@Composable
internal fun SettingRow(label: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
        control()
    }
}

@Composable
internal fun Dim(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    // Medium weight: regular-weight small text measured 3.9–4.4 : 1 on the glass in the HD shot (anti-aliasing)
    Text(text, style = PicoTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = PicoTheme.colorScheme.labelPrimary, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
internal fun Disabled(enabled: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (enabled) 1f else 0.45f)) { content() }
}
