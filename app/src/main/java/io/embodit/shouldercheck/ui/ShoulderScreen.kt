package io.embodit.shouldercheck.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.shouldercheck.app.AppController
import io.embodit.shouldercheck.app.DebugCommands
import io.embodit.shouldercheck.app.Ev
import io.embodit.shouldercheck.app.UiState
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.platform.DebugHooks
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.scene.CarScene
import io.embodit.shouldercheck.scene.PanelIds
import io.embodit.shouldercheck.scene.ScFrameSystem
import io.embodit.shouldercheck.ui.components.LocalScResources
import io.embodit.shouldercheck.ui.components.localizedResources
import io.embodit.shouldercheck.ui.panels.AngPanel
import io.embodit.shouldercheck.ui.panels.MirrorPanel
import io.embodit.shouldercheck.ui.panels.P1Panel
import io.embodit.shouldercheck.ui.panels.P2Panel
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one screen: wires the process-wide [AppController] (all behaviour), the [CarScene] (entities, per-frame work, one
 * registered ECS system) and the panel pool (P1, P2, two B-pillar readouts, three mirrors). Lifecycle: background stops
 * traffic and sound and returns to S05 of the same question on foreground; quitting releases trackers and audio.
 */
@Composable
fun ShoulderScreen() {
    val context = LocalContext.current
    remember(context) {
        AppController.start(context)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) DebugHooks.handler = DebugCommands::handle
        true
    }
    val state by AppController.state.collectAsStateWithLifecycle()
    val scene = remember { CarScene(context.applicationContext) }
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<ScFrameSystem>()
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        scene.onBackground()
                        AppController.onBackground()
                    }
                    Lifecycle.Event.ON_START -> {
                        scene.onForeground()
                        AppController.onForeground()
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        AppController.onQuit = {
            // D26: leave the task, release tracking / audio, end the process
            ScLog.i("QUIT finishAndRemoveTask")
            scene.detach()
            AppController.release()
            scope.launch {
                runCatching { navigator.closeStage() }
                runCatching { (context as? Activity)?.finishAndRemoveTask() }
                exitProcess(0)
            }
        }
        ScLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            unregisterSystem<ScFrameSystem>()
            scene.detach()
            // the Stage went away without 退出应用: free the SoundPool too (a new one comes with the next start)
            AppController.release()
            AppController.onQuit = null
            ScLog.i("STAGE disposed")
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalScResources provides resources) {
        Stage(state, scene, AppController::onEvent)
    }
}

@Composable
private fun Stage(state: UiState, scene: CarScene, onEvent: (Ev) -> Unit) {
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        attachments = {
            AttachmentPanel(PanelIds.P1) { Pool(PanelIds.P1, scene, listOf(state.page, state.language, state.progress, state.confirmExit, state.confirmClear, state.showAbout, state.range, state.eyeOff, state.lessonFocus, state.replayDemo, state.muted)) { P1Panel(state, false, onEvent) } }
            AttachmentPanel(PanelIds.P1_TALL) { Pool(PanelIds.P1_TALL, scene, listOf(state.page, state.language, state.result, state.progress, state.confirmClear)) { P1Panel(state, true, onEvent) } }
            AttachmentPanel(PanelIds.P2) { Pool(PanelIds.P2, scene, listOf(state.base, state.language, state.hud.situation, state.hud.action, state.hud.message, state.lost)) { P2Panel(state, onEvent) } }
            AttachmentPanel(PanelIds.ANG_L) { Pool(PanelIds.ANG_L, scene, listOf(state.base, state.language)) { AngPanel(state.angLeft, Dir.LEFT) } }
            AttachmentPanel(PanelIds.ANG_R) { Pool(PanelIds.ANG_R, scene, listOf(state.base, state.language)) { AngPanel(state.angRight, Dir.RIGHT) } }
            for (id in MirrorId.values()) {
                val pid = PanelIds.mirrorPanel(id)
                val (w, h) = PanelIds.SIZES.getValue(pid)
                AttachmentPanel(pid) { Pool(pid, scene, null) { MirrorPanel(id, w, h) } }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: its content is composed only once the panel is in view (drawn while parked it stays broken),
 * and composed afresh 1.5 / 4 / 8 / 12 / 20 s after it comes into view or [refresh] changes (the idle emulator can keep
 * a half-drawn first frame: factory finding).
 */
@Composable
private fun Pool(id: String, scene: CarScene, refresh: Any?, content: @Composable () -> Unit) {
    val inView = scene.panelShown[id] == true
    var gen by remember { mutableIntStateOf(0) }
    LaunchedEffect(inView, refresh) {
        if (!inView) return@LaunchedEffect
        for (wait in REFRESH_STEPS_MS) {
            delay(wait)
            gen++
        }
    }
    val (w, h) = PanelIds.SIZES.getValue(id)
    Box(modifier = Modifier.size(w.dp, h.dp)) {
        if (inView) key(gen) { content() }
    }
}

private val REFRESH_STEPS_MS = longArrayOf(1_500L, 2_500L, 4_000L, 4_000L, 8_000L)
