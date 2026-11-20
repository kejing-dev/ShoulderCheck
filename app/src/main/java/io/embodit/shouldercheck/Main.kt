package io.embodit.shouldercheck

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.shouldercheck.ui.ShoulderScreen
import io.embodit.shouldercheck.ui.theme.ScTheme
import io.embodit.shouldercheck.ui.window.GameWindow
import io.embodit.shouldercheck.ui.window.WindowCtl
import io.embodit.shouldercheck.ui.window.WindowSize

/**
 * App entry (design `container`: STAGE_FULL). The default Stage is Full (AndroidManifest `pico.spatial.stage.style=3`):
 * opening the app puts you in the driver's seat of a life-size car on a city street and the first round starts.
 * Between rounds the 2D pages live in ONE system Planar window (D21 / D8: the system moves / closes it; TabBar 练习 ·
 * 成绩 · 设置), opened from the Stage; a round on screen has no 2D panel.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { ScTheme { ShoulderScreen() } }
        WindowContainer(
            WindowCtl.ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = WindowSize.W.dp, height = WindowSize.H.dp),
        ) { ScTheme { GameWindow() } }
    }
