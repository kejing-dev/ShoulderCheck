package io.embodit.shouldercheck.ui.window

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.shouldercheck.R
import io.embodit.shouldercheck.app.AppController
import io.embodit.shouldercheck.app.Confirm
import io.embodit.shouldercheck.app.Ev
import io.embodit.shouldercheck.app.Page
import io.embodit.shouldercheck.app.Screen
import io.embodit.shouldercheck.app.UiState
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Levels
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.VolumeLevel
import io.embodit.shouldercheck.ui.components.Dim
import io.embodit.shouldercheck.ui.components.LocalScResources
import io.embodit.shouldercheck.ui.components.MinTouch
import io.embodit.shouldercheck.ui.components.Probe
import io.embodit.shouldercheck.ui.components.Segments
import io.embodit.shouldercheck.ui.components.SettingRow
import io.embodit.shouldercheck.ui.components.WordButton
import io.embodit.shouldercheck.ui.components.fits
import io.embodit.shouldercheck.ui.components.localizedResources
import io.embodit.shouldercheck.ui.components.rs
import java.util.Locale
import kotlin.math.roundToInt

/** Window size (dp): every page fits without scrolling (D16 / B19: taller window first, no item below the fold). */
object WindowSize {
    const val W = 820
    const val H = 540
}

/**
 * The main window's content (design `panels[P1]`, v0.2.0): TabBar 练习 · 成绩 · 设置. The 练习 tab shows the game over
 * card after a game (S20), the pause card while a game is paused (S50), or the practice page (S30).
 */
@Composable
fun GameWindow() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val state by AppController.state.collectAsStateWithLifecycle()
    val onEvent = AppController::onEvent
    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalScResources provides resources) {
        // the TabBar is its own system window: labels resolved here in the app language, never with stringResource
        val labels = listOf(rs(R.string.tab_practice), rs(R.string.tab_records), rs(R.string.tab_settings))
        MainWindow(
            pages =
                listOf(
                    MainWindowPage(Page.PLAY.id, labels[0], R.drawable.ic_car) { PlayTab(state, onEvent) },
                    MainWindowPage(Page.RECORDS.id, labels[1], R.drawable.ic_bar_chart) { RecordsPage(state) },
                    MainWindowPage(Page.SETTINGS.id, labels[2], R.drawable.ic_settings) { SettingsTab(state, onEvent) },
                ),
            current = state.page.id,
            onSelect = { id -> Page.values().firstOrNull { it.id == id }?.let { onEvent(Ev.OpenPage(it)) } },
        )
    }
}

@Composable
private fun PlayTab(state: UiState, onEvent: (Ev) -> Unit) {
    when (state.screen) {
        Screen.S20 -> state.summary?.let { GameOverPage(state, onEvent) } ?: PracticePage(state, onEvent)
        Screen.S50 -> PausePage(state, onEvent)
        Screen.S53 -> UnavailablePage(onEvent)
        else -> PracticePage(state, onEvent)
    }
}

/** Title + the always-visible mute button (D15). */
@Composable
private fun Header(title: String, state: UiState, onEvent: (Ev) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("W#header"), modifier = Modifier.weight(1f))
        WordButton("mute", rs(if (state.muted) R.string.unmute else R.string.mute), { onEvent(Ev.ToggleMute) }, iconRes = if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume, minWidth = 150.dp)
    }
}

@Composable
private fun Stars(n: Int, size: Int = 32) {
    Text("★".repeat(n.coerceIn(0, 3)) + "☆".repeat(3 - n.coerceIn(0, 3)), fontSize = size.sp, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("W#stars"))
}

private fun fmt(n: Int): String = String.format(Locale.US, "%,d", n)

@Composable
private fun levelName(n: Int): String =
    when (n) {
        1 -> rs(R.string.level_1)
        2 -> rs(R.string.level_2)
        3 -> rs(R.string.level_3)
        else -> rs(R.string.level_4)
    }

// ------------------------------------------------------------------------------------------- S30 practice

@Composable
private fun PracticePage(state: UiState, onEvent: (Ev) -> Unit) {
    val p = state.progress
    val lv = state.practiceLevel.coerceIn(1, Levels.COUNT)
    val unlocked = lv <= p.unlocked
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Header(rs(R.string.app_title), state, onEvent)
        Text(rs(R.string.tagline), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S30#tagline"))
        Dim(rs(R.string.next_step), maxLines = 2)
        Spacer(Modifier.height(20.dp))
        // level picker: ◀ [level] ▶ (same page option switch, not page tabs)
        Row(modifier = Modifier.fillMaxWidth().height(160.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WordButton("prevLevel", "◀", { onEvent(Ev.PrevLevel) }, minWidth = 96.dp, height = 96.dp)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { // gate:allow button-gap icon + level title, no buttons
                    Icon(painter = painterResource(R.drawable.ic_car), contentDescription = null, modifier = Modifier.size(32.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    Text(rs(R.string.level_label, lv) + " · " + levelName(lv), style = PicoTheme.typography.headlineSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S30#level"))
                }
                Spacer(Modifier.height(8.dp))
                val best = p.best(lv)
                if (!unlocked) Dim(rs(R.string.level_locked), maxLines = 1)
                else if (best.plays == 0) Dim(rs(R.string.best_none), maxLines = 1)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { // gate:allow button-gap stars + text, no buttons
                    Stars(best.stars, 26)
                    Text(rs(R.string.result_best, fmt(best.best)), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S30#best"))
                }
            }
            WordButton("nextLevel", "▶", { onEvent(Ev.NextLevel) }, minWidth = 96.dp, height = 96.dp)
        }
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WordButton("start", rs(R.string.start), { if (unlocked) onEvent(Ev.Start(lv)) }, modifier = Modifier.weight(1f), primary = unlocked, iconRes = R.drawable.ic_play, height = 88.dp)
            WordButton("daily", rs(R.string.daily), { onEvent(Ev.Daily) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_timer, height = 88.dp)
        }
        Spacer(Modifier.height(12.dp))
        val today = AppController.today()
        val db = p.dailyBestFor(today)
        Dim(if (db > 0) rs(R.string.daily_best, fmt(db)) else rs(R.string.daily_none), maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------- S20 game over

@Composable
private fun GameOverPage(state: UiState, onEvent: (Ev) -> Unit) {
    val s = state.summary ?: return
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Header(if (s.daily) rs(R.string.daily) else rs(R.string.level_label, s.level) + " · " + levelName(s.level), state, onEvent)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) { // gate:allow button-gap score + stars, no buttons
            Text(fmt(s.total), style = PicoTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 64.sp, fontFeatureSettings = "tnum, lnum"), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S20#total"))
            Stars(s.stars, 40)
            Text(if (s.newBest) rs(R.string.result_record) else rs(R.string.result_best, fmt(s.best)), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S20#best"))
        }
        Spacer(Modifier.height(12.dp))
        val side = when (s.missedMost) {
            Dir.LEFT -> rs(R.string.side_left_back)
            Dir.RIGHT -> rs(R.string.side_right_back)
            null -> rs(R.string.side_none)
        }
        Text(rs(R.string.result_stats_clear, s.clear), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S20#clear"))
        Text(rs(R.string.result_stats_check, s.avgShoulder?.let { String.format(Locale.US, "%.1f", it) } ?: "–"), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S20#check"))
        Text(rs(R.string.result_stats_missed, side), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S20#missed"))
        if (s.unlockedNext && s.level < Levels.COUNT && state.progress.unlocked > s.level) Dim(rs(R.string.result_unlocked), maxLines = 1)
        Spacer(Modifier.height(20.dp))
        WordButton("again", rs(R.string.result_again), { onEvent(Ev.PlayAgain) }, modifier = Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_refresh, height = 88.dp)
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.missedCount > 0) WordButton("watchMisses", rs(R.string.result_replay, state.missedCount), { onEvent(Ev.WatchMisses) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_replay, height = 80.dp)
            if (!s.daily && s.level < Levels.COUNT && state.progress.unlocked > s.level) WordButton("nextFromResult", rs(R.string.result_next), { onEvent(Ev.NextFromResult) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_skip_next, height = 80.dp)
            if (state.missedCount == 0 && !(s.level < Levels.COUNT && state.progress.unlocked > s.level && !s.daily)) WordButton("toPractice", rs(R.string.to_practice), { onEvent(Ev.ToPractice) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_home, height = 80.dp)
        }
    }
}

// ------------------------------------------------------------------------------------------- S50 pause

@Composable
private fun PausePage(state: UiState, onEvent: (Ev) -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Header(rs(R.string.pause_title), state, onEvent)
        if (state.lost) Text(rs(R.string.lost), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("S50#lost"))
        else if (state.pausedDaily) Dim(rs(R.string.daily_pause_note), maxLines = 1)
        else Dim(rs(R.string.level_label, state.level) + " · " + levelName(state.level), maxLines = 1)
        Spacer(Modifier.height(16.dp))
        when (state.confirm) {
            Confirm.RESTART, Confirm.LEAVE -> {
                // D44: entries that lose the game ask first, in place (same block height as the pause card: no blank)
                Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.CenterStart) {
                    Text(rs(if (state.confirm == Confirm.RESTART) R.string.restart_ask else R.string.leave_ask), style = PicoTheme.typography.headlineSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("S50#ask"))
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WordButton("confirmNo", rs(R.string.cancel), { onEvent(Ev.ConfirmNo) }, modifier = Modifier.weight(1f), height = 88.dp)
                    WordButton("confirmYes", rs(if (state.confirm == Confirm.RESTART) R.string.restart_game else R.string.back_to_practice), { onEvent(Ev.ConfirmYes) }, modifier = Modifier.weight(1f), danger = true, height = 88.dp)
                }
                Spacer(Modifier.height(16.dp))
                WordButton("resume", rs(R.string.resume), { onEvent(Ev.Resume) }, modifier = Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_play, height = 88.dp)
            }
            else -> {
                WordButton("resume", rs(R.string.resume), { onEvent(Ev.Resume) }, modifier = Modifier.fillMaxWidth(), primary = !state.lost, iconRes = R.drawable.ic_play, height = 104.dp)
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WordButton("restartGame", rs(R.string.restart_game), { onEvent(Ev.AskRestart) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_refresh, height = 88.dp)
                    WordButton("recentre", rs(R.string.recentre), { onEvent(Ev.Recentre) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_center, height = 88.dp)
                }
                Spacer(Modifier.height(16.dp))
                WordButton("backToPractice", rs(R.string.back_to_practice), { onEvent(Ev.AskLeave) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_home, height = 88.dp)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- S53

@Composable
private fun UnavailablePage(onEvent: (Ev) -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(160.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(24.dp))
        Text(rs(R.string.app_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S53#title"))
        Spacer(Modifier.height(16.dp))
        Text(rs(R.string.unavailable), style = PicoTheme.typography.headlineSmall, color = PicoTheme.colorScheme.labelPrimary, textAlign = TextAlign.Center, maxLines = 2, onTextLayout = fits("S53#text"))
        Spacer(Modifier.height(48.dp))
        WordButton("quitUnavailable", rs(R.string.quit_app), { onEvent(Ev.AskQuit) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_logout, height = 96.dp)
    }
}

// ------------------------------------------------------------------------------------------- S32 records

@Composable
private fun RecordsPage(state: UiState) {
    val p = state.progress
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(rs(R.string.records_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S32#title"))
        Spacer(Modifier.height(16.dp))
        for (n in 1..Levels.COUNT) {
            val b = p.best(n)
            Row(modifier = Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { // gate:allow button-gap read-only row, no buttons
                Text(rs(R.string.level_label, n) + " · " + levelName(n), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S32#name$n"), modifier = Modifier.weight(1f))
                if (b.plays == 0) Dim(if (n <= p.unlocked) rs(R.string.best_none) else rs(R.string.level_locked), maxLines = 1, modifier = Modifier.width(360.dp))
                else {
                    Stars(b.stars, 26)
                    Text(fmt(b.best), style = PicoTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum, lnum"), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S32#best$n"), modifier = Modifier.width(110.dp), textAlign = TextAlign.End)
                    Text(rs(R.string.records_plays, b.plays), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S32#plays$n"), modifier = Modifier.width(110.dp), textAlign = TextAlign.End)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val db = p.dailyBestFor(AppController.today())
        Row(modifier = Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { // gate:allow button-gap read-only row, no buttons
            Icon(painter = painterResource(R.drawable.ic_timer), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Text(rs(R.string.records_daily, if (db > 0) fmt(db) else "–"), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S32#daily"), modifier = Modifier.weight(1f))
            if (p.dailyStreak > 1 && p.dailyDate == AppController.today()) Dim(rs(R.string.records_streak, p.dailyStreak), maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------------------------------- S40 settings

@Composable
private fun SettingsTab(state: UiState, onEvent: (Ev) -> Unit) {
    if (state.screen == Screen.S41) HowToPage(onEvent) else SettingsPage(state, onEvent)
}

@Composable
private fun SettingsPage(state: UiState, onEvent: (Ev) -> Unit) {
    val s = state.progress.settings
    var about by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(rs(R.string.settings), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S40#title"))
        Spacer(Modifier.height(8.dp))
        if (about) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Text(rs(R.string.about_text), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 6, onTextLayout = fits("S40#about"))
            }
            WordButton("aboutBack", rs(R.string.back), { about = false }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_arrow_back)
            Spacer(Modifier.height(16.dp))
        } else {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                // left: practice
                Column(modifier = Modifier.weight(1f)) {
                    SettingRow(rs(R.string.comfort), Modifier.height(72.dp)) {
                        val latest by rememberUpdatedState(s.comfort)
                        Probe("comfort") { onEvent(Ev.SetComfort(!latest)) }
                        Box(Modifier.heightIn(min = 56.dp).width(96.dp), contentAlignment = Alignment.Center) {
                            Switch(checked = s.comfort, onCheckedChange = { onEvent(Ev.SetComfort(it)) })
                        }
                    }
                    SettingRow(rs(R.string.traffic), Modifier.height(72.dp)) {
                        Segments("traffic", listOf(rs(R.string.traffic_right), rs(R.string.traffic_left)), if (s.traffic == TrafficSide.RIGHT_HAND) 0 else 1, { i -> onEvent(Ev.SetTraffic(if (i == 0) TrafficSide.RIGHT_HAND else TrafficSide.LEFT_HAND)) }, 176.dp, 56.dp)
                    }
                    SettingRow(rs(R.string.eye), Modifier.height(72.dp)) {
                        if (state.eyeGranted) Text(rs(R.string.eye_state_on), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S40#eyeOn"))
                        else WordButton("eyeEnable", rs(R.string.eye_enable), { onEvent(Ev.EnableEyes) }, minWidth = 160.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    WordButton("howTo", rs(R.string.how_to) + " ›", { onEvent(Ev.HowTo) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_info, height = 72.dp)
                }
                // right: system
                Column(modifier = Modifier.weight(1f)) {
                    SettingRow(rs(R.string.volume), Modifier.height(64.dp)) {
                        val level by rememberUpdatedState(s.volume)
                        Probe("volLow") { onEvent(Ev.SetVolume(VolumeLevel.LOW)) }
                        Probe("volHigh") { onEvent(Ev.SetVolume(VolumeLevel.HIGH)) }
                        // B20: a local Float follows the pointer (taps and small drags are not swallowed); the detent
                        // (低 / 中 / 高) is taken on release; the callback reads the latest state (slider-stale)
                        var drag by remember(level) { mutableStateOf(level.ordinal.toFloat()) }
                        val send by rememberUpdatedState<(Float) -> Unit> { v -> onEvent(Ev.SetVolume(VolumeLevel.values()[v.roundToInt().coerceIn(0, 2)])) }
                        Slider(value = drag, onValueChange = { drag = it }, onValueChangeFinished = { send(drag); drag = drag.roundToInt().toFloat() }, valueRange = 0f..2f, modifier = Modifier.width(200.dp).height(56.dp))
                    }
                    SettingRow(rs(R.string.mute), Modifier.height(64.dp)) {
                        val latestMuted by rememberUpdatedState(state.muted)
                        Probe("muteSwitch") { onEvent(Ev.ToggleMute) }
                        Box(Modifier.heightIn(min = 56.dp).width(96.dp), contentAlignment = Alignment.Center) {
                            Switch(checked = latestMuted, onCheckedChange = { onEvent(Ev.ToggleMute) })
                        }
                    }
                    SettingRow(rs(R.string.language), Modifier.height(64.dp)) {
                        val lang = if (state.language == Language.SYSTEM) (if (AppController.isEnglish()) Language.EN else Language.ZH) else state.language
                        Segments("lang", listOf(rs(R.string.lang_zh), rs(R.string.lang_en)), if (lang == Language.EN) 1 else 0, { i -> onEvent(Ev.SetLanguage(if (i == 0) Language.ZH else Language.EN)) }, 200.dp, 56.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WordButton("recentreSettings", rs(R.string.recentre), { onEvent(Ev.Recentre) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_center)
                        WordButton("about", rs(R.string.about), { about = true }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_info)
                    }
                    Spacer(Modifier.height(16.dp))
                    WordButton("clearScores", rs(R.string.clear_scores), { onEvent(Ev.AskClear) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_close)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // bottom, every settings view: [回到面前] [退出应用] (D44 / D26), confirmations in place
        when (state.screen) {
            Screen.S42, Screen.S43 -> {
                val quit = state.screen == Screen.S42
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rs(if (quit) R.string.quit_ask else R.string.clear_ask), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("S40#ask"), modifier = Modifier.weight(1f))
                    WordButton("confirmNo", rs(R.string.cancel), { onEvent(Ev.ConfirmNo) }, minWidth = 140.dp, height = 72.dp)
                    WordButton("confirmYes", rs(if (quit) R.string.quit else R.string.clear), { onEvent(Ev.ConfirmYes) }, danger = true, minWidth = 140.dp, height = 72.dp)
                }
            }
            else ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WordButton("windowFront", rs(R.string.window_front), { onEvent(Ev.WindowFront) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_center, height = 72.dp)
                    WordButton("quitApp", rs(R.string.quit_app), { onEvent(Ev.AskQuit) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_logout, height = 72.dp)
                }
        }
    }
}

@Composable
private fun HowToPage(onEvent: (Ev) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(rs(R.string.how_to_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S41#title"))
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HowRow(R.drawable.ic_eye, rs(R.string.how_to_check_title), rs(R.string.how_to_check_hand), rs(R.string.how_to_check_ctrl))
            HowRow(R.drawable.ic_play, rs(R.string.how_to_go_title), rs(R.string.how_to_go_hand), rs(R.string.how_to_go_ctrl))
            HowRow(R.drawable.ic_door, rs(R.string.how_to_door_title), rs(R.string.how_to_door_hand), rs(R.string.how_to_door_ctrl))
            HowRow(R.drawable.ic_pause, rs(R.string.how_to_pause_title), rs(R.string.how_to_pause_hand), rs(R.string.how_to_pause_ctrl))
        }
        WordButton("howToBack", rs(R.string.back), { onEvent(Ev.HowToBack) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_arrow_back)
    }
}

@Composable
private fun ColumnScope.HowRow(icon: Int, title: String, hand: String, ctrl: String) {
    Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { // gate:allow button-gap read-only how-to row, no buttons
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(36.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("S41#t"))
            Text(hand, style = PicoTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("S41#h"))
            Text(ctrl, style = PicoTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("S41#c"))
        }
    }
}

@Suppress("unused")
private val unusedMinTouch = MinTouch
