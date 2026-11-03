package io.embodit.shouldercheck.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * The app theme: SpatialUI Vibrant with all 16 ColorScheme roles left at the PicoTheme / system defaults (design
 * `palette.rule`), and the Noto Sans / Noto Sans SC type family ([rememberScFontFamily]).
 */
@Composable
fun ScTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = systemColorScheme(context)
    val family = rememberScFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
