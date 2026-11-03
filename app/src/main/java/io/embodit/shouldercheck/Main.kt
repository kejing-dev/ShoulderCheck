package io.embodit.shouldercheck

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.shouldercheck.ui.ShoulderScreen
import io.embodit.shouldercheck.ui.theme.ScTheme

/**
 * App entry (design `container`: STAGE_FULL). The default Stage is Full (AndroidManifest `pico.spatial.stage.style=3`):
 * opening the app puts you in the driver's seat of a life-size car on a city street; there is no Shared Space window
 * and no passthrough.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { ScTheme { ShoulderScreen() } }
    }
