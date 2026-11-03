package io.embodit.shouldercheck.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.delay

/**
 * Keeps a glass panel's backdrop fresh (rehearsal-hall GlassRefresh, from Qianliyan QA q2-1): on the emulator a frame
 * drawn before the backdrop is ready comes up black, and a static panel never draws again. Re-records the material
 * layer every [REFRESH_MS] while the panel is composed.
 */
@Composable
internal fun glassRefresh(): Modifier {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            tick.intValue++
        }
    }
    return Modifier.drawBehind {
        @Suppress("UNUSED_VARIABLE") val n = tick.intValue
    }
}

private const val REFRESH_MS = 500L
