package io.embodit.shouldercheck.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
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
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.shouldercheck.app.AppController
import io.embodit.shouldercheck.app.DebugCommands
import io.embodit.shouldercheck.app.Ev
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.Target
import io.embodit.shouldercheck.domain.targetOfName
import io.embodit.shouldercheck.platform.DebugHooks
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.platform.backKeys
import io.embodit.shouldercheck.scene.CarScene
import io.embodit.shouldercheck.scene.PanelIds
import io.embodit.shouldercheck.scene.ScFrameSystem
import io.embodit.shouldercheck.ui.components.LocalScResources
import io.embodit.shouldercheck.ui.components.localizedResources
import io.embodit.shouldercheck.ui.input.BoardInputConfig
import io.embodit.shouldercheck.ui.input.BoardInputListener
import io.embodit.shouldercheck.ui.input.BoardInputLog
import io.embodit.shouldercheck.ui.input.InputSource
import io.embodit.shouldercheck.ui.input.TargetResolver
import io.embodit.shouldercheck.ui.input.boardInput
import io.embodit.shouldercheck.ui.input.resolveUp
import io.embodit.shouldercheck.ui.panels.MirrorPanel
import io.embodit.shouldercheck.ui.window.WindowCtl
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Stage: wires the process-wide [AppController] (all behaviour), the [CarScene] (entities, per-frame work, one
 * registered ECS system) and the three mirror panels. All 3D tap targets (「走」, the door handle, 「暂停」, the S54
 * buttons) go through ONE board-input layer (D50: controller ray, hand ray, eye-hand — one tap each). Lifecycle:
 * background pauses the round (pause card on return); quitting closes the window and the Stage, removes the task and
 * ends the process (D26).
 */
@Composable
fun ShoulderScreen() {
    val context = LocalContext.current
    remember(context) {
        AppController.start(context)
        BoardInputLog.attach(context.applicationContext)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) DebugHooks.handler = DebugCommands::handle
        true
    }
    val state by AppController.state.collectAsStateWithLifecycle()
    val scene = remember { CarScene(context.applicationContext) }
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    WindowCtl.navigator = navigator

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
        DebugCommands.tapReplay = { target, src -> replayTap(scene, target, src) }
        AppController.onQuit = {
            // D26 / D44: close the window AND the Stage, remove every task, then end the process
            ScLog.i("QUIT close window + stage + finishAndRemoveTask + exitProcess")
            WindowCtl.close("quit")
            WindowCtl.cancel()
            scene.detach()
            AppController.release()
            scope.launch {
                runCatching { navigator.closeStage() }.onFailure { ScLog.w("QUIT closeStage: $it") }
                runCatching {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.appTasks.forEach { t -> runCatching { t.finishAndRemoveTask() } }
                }
                runCatching { (context as? Activity)?.finishAndRemoveTask() }
                delay(400)
                exitProcess(0)
            }
        }
        ScLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            DebugCommands.tapReplay = null
            unregisterSystem<ScFrameSystem>()
            scene.detach()
            AppController.release()
            AppController.onQuit = null
            ScLog.i("STAGE disposed")
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalScResources provides resources) {
        Stage(scene)
    }
}

@Composable
private fun Stage(scene: CarScene) {
    val bindTick = scene.bindTick.intValue
    val listener = remember(scene) { TapListener(scene) }
    SpatialView(
        // D50 (board-input template): every 3D target takes one tap from the controller ray, the hand ray or eye-hand;
        // this layer only listens to hits on our targets, so the mirror panels keep their own input
        modifier = Modifier.fillMaxSize().backKeys("stage").boardInput(TargetResolverByName, listener, TARGET_INPUT),
        attachments = {
            for (id in MirrorId.values()) {
                val pid = PanelIds.mirrorPanel(id)
                val (w, h) = PanelIds.SIZES.getValue(pid)
                AttachmentPanel(pid) { Pool(pid, scene) { MirrorPanel(id, w, h) } }
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
 * and composed afresh a few times after it comes into view (the idle emulator can keep a half-drawn first frame).
 */
@Composable
private fun Pool(id: String, scene: CarScene, content: @Composable () -> Unit) {
    val inView = scene.panelShown[id] == true
    var gen by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    LaunchedEffect(inView) {
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

private val REFRESH_STEPS_MS = longArrayOf(1_500L, 2_500L, 4_000L)

/** Hit entity (a target holder or any child named `<id>/…`) → its target. Top-level function reference (bonsai NPE lesson). */
private val TargetResolverByName: TargetResolver<Target> = TargetResolver.byName(::targetOfName)

private val TARGET_INPUT = BoardInputConfig(logTag = "ShoulderCheck", accept = { e -> resolveUp(e, TargetResolverByName).first != null })

/** Every 3D tap — real input and the debug replay — goes through here into [AppController.onEvent]. */
private class TapListener(private val scene: CarScene) : BoardInputListener<Target> {
    override fun onTap(target: Target?, source: InputSource, position: Offset3D?) {
        if (target != null) AppController.onEvent(Ev.Tap(target, source.label))
    }

    override fun onHover(target: Target?, source: InputSource) {
        scene.hover(target)
    }
}

/**
 * Debug `tap btn:go|door:handle|pause:chip|ask:on|ask:off [src=controller|hand-ray|eye-hand]`: takes the REAL entity of
 * the target (its holder and deepest child), resolves it up the parent chain like a system hit, and hands it to the
 * same listener a real tap reaches.
 */
private fun replayTap(scene: CarScene, target: Target, src: String): String {
    val holder = scene.targetEntity(target) ?: return "no entity for ${target.id}"
    val mesh = runCatching { holder.getChildren().lastOrNull() }.getOrNull() ?: holder
    val source = InputSource.values().firstOrNull { it.label == src } ?: InputSource.CONTROLLER
    val (hit, path) = resolveUp(mesh, TargetResolverByName)
    val line = "REPLAY src=${source.label} path=$path -> ${hit?.id ?: "-"}"
    BoardInputLog.write("ShoulderCheck", line)
    if (hit != null) AppController.onEvent(Ev.Tap(hit, source.label))
    return line
}
