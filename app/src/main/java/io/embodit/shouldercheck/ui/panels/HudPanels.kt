package io.embodit.shouldercheck.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.shouldercheck.R
import io.embodit.shouldercheck.app.AngState
import io.embodit.shouldercheck.app.Base
import io.embodit.shouldercheck.app.Ev
import io.embodit.shouldercheck.app.HudAction
import io.embodit.shouldercheck.app.HudMessage
import io.embodit.shouldercheck.app.StepChip
import io.embodit.shouldercheck.app.UiState
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Step
import io.embodit.shouldercheck.domain.StepState
import io.embodit.shouldercheck.ui.components.Glass
import io.embodit.shouldercheck.ui.components.ScType
import io.embodit.shouldercheck.ui.components.Segments
import io.embodit.shouldercheck.ui.components.StatusLabel
import io.embodit.shouldercheck.ui.components.WordButton
import io.embodit.shouldercheck.ui.components.Words
import io.embodit.shouldercheck.ui.components.fmt1
import io.embodit.shouldercheck.ui.components.rs
import io.embodit.shouldercheck.ui.theme.ScColors

/**
 * P2 HUD strip (design panels[P2]): 760 × 176 dp, two rows. Row 1: the task sentence (or a short message) + 「暂停」
 * 「静音」; random traffic puts "3/10 · 难度 2" first; head-mode questions carry 「按头部判断」. Row 2: the step blocks
 * (≥ 56 dp, grey → lit with a tick, back to grey when stale; the look-back block shows "64° / 60°" live) and the
 * action button (执行 in gesture mode / 关门 / 确认). With step hints off only the action button stays.
 */
@Composable
fun P2Panel(state: UiState, onEvent: (Ev) -> Unit) {
    val hud = state.hud
    Glass(760.dp, 176.dp, corner = 28.dp, padding = 14.dp, spacing = 10.dp) {
        Row(modifier = Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hud.setIndex != null && hud.setLevel != null) {
                Box(modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(rs(R.string.set_prefix, hud.setIndex, hud.setLevel), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                }
            }
            Box(modifier = Modifier.weight(1f)) { Row1(state) }
            if (hud.mode == JudgeMode.HEAD && state.base != Base.S04) StatusLabel(R.drawable.ic_eye_off, rs(R.string.by_head), ScColors.Stale, PicoTheme.typography.labelMedium, 16.dp)
            WordButton("pause", rs(R.string.pause), { onEvent(Ev.Pause) }, iconRes = R.drawable.ic_pause, minWidth = 84.dp)
            WordButton("mute", rs(if (state.muted) R.string.unmute else R.string.mute), { onEvent(Ev.ToggleMute) }, iconRes = if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume, minWidth = 84.dp)
        }
        Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.base == Base.S11) {
                Segments("speed", listOf("0.5×", "1×"), if (hud.replaySpeed < 0.75f) 0 else 1, { i -> onEvent(Ev.ReplaySpeed(if (i == 0) 0.5 else 1.0)) }, 200.dp)
                Text(rs(R.string.sec_fmt, fmt1(hud.replayT)), style = ScType.chip, color = PicoTheme.colorScheme.labelPrimary)
                Spacer(Modifier.weight(1f))
                WordButton("endReplay", rs(R.string.replay_end), { onEvent(Ev.EndReplay) }, primary = true, iconRes = R.drawable.ic_close)
            } else {
                if (hud.showSteps) {
                    for (chip in hud.steps) StepBlock(chip, state, Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                ActionButton(hud.action, onEvent)
            }
        }
    }
}

@Composable
private fun Row1(state: UiState) {
    val hud = state.hud
    val msg = hud.message
    when {
        state.lost || msg == HudMessage.Lost -> StatusLabel(R.drawable.ic_error, rs(R.string.msg_lost), ScColors.Miss, ScType.task)
        msg is HudMessage.NearMiss -> StatusLabel(R.drawable.ic_error, rs(R.string.msg_near_miss, rs(Words.dir(msg.dir)), rs(Words.kind(msg.kind))), ScColors.Miss, ScType.task)
        msg == HudMessage.Stale -> StatusLabel(R.drawable.ic_timer, rs(R.string.stale), ScColors.Stale, ScType.task)
        msg is HudMessage.TurnMore -> StatusLabel(R.drawable.ic_info, rs(if (msg.dir == Dir.LEFT) R.string.msg_turn_more_left else R.string.msg_turn_more_right, msg.deg), ScColors.Stale, ScType.task)
        msg is HudMessage.Relaxed -> StatusLabel(R.drawable.ic_info, rs(R.string.msg_relaxed, msg.deg), ScColors.Done, ScType.task)
        msg is HudMessage.MirrorGone -> StatusLabel(R.drawable.ic_info, rs(R.string.msg_mirror_gone, msg.seconds), ScColors.Stale, ScType.task)
        state.base == Base.S11 -> Line(rs(R.string.replay_title))
        state.base == Base.S04 -> Line(hud.demoLine?.let { rs(Words.demo(it)) } ?: rs(R.string.demo_go))
        hud.rearNoteDeg != null -> Text(rs(R.string.rear_note, hud.rearNoteDeg), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        hud.situation == Situation.MIRRORS && hud.blindLeft != null -> Line(rs(R.string.blind, fmt1(hud.blindLeft)) + " · " + rs(R.string.blind_right, fmt1(hud.blindRight ?: 0.0)))
        hud.situation != null -> Line(rs(Words.task(hud.situation)))
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = ScType.task, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** One step block: icon (state colour) + name; the look-back block adds the live "64° / 60°". */
@Composable
private fun StepBlock(chip: StepChip, state: UiState, modifier: Modifier) {
    val (icon, color) =
        when (chip.state) {
            StepState.DONE -> R.drawable.ic_done to ScColors.Done
            StepState.STALE -> R.drawable.ic_timer to ScColors.Stale
            StepState.WRONG -> R.drawable.ic_wrong to ScColors.Miss
            StepState.PENDING -> R.drawable.ic_pending to ScColors.Missing
        }
    val bg = if (chip.highlight) PicoTheme.colorScheme.interaction.copy(alpha = 0.35f) else if (chip.state == StepState.DONE) ScColors.Done.copy(alpha = 0.22f) else PicoTheme.colorScheme.fillTertiary
    Row(
        modifier = modifier.heightIn(min = 56.dp).height(60.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(22.dp)) { drawCircle(ScColors.IconDisc) }
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
        val shoulder = chip.step == Step.SHOULDER_LEFT || chip.step == Step.SHOULDER_RIGHT
        val label = rs(Words.step(chip.step))
        // long English labels ("Right mirror") wrap onto two lines instead of being cut (S20 check)
        val twoLines = !shoulder && label.length > 9
        Column(verticalArrangement = Arrangement.Center) {
            Text(label, style = if (twoLines) ScType.chip.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)) else ScType.chip, color = PicoTheme.colorScheme.labelPrimary, maxLines = if (twoLines) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (shoulder) {
                val ang = if (chip.step == Step.SHOULDER_LEFT) state.angLeft else state.angRight
                Text("${ang.deg}° / ${ang.threshold ?: 0}°", style = PicoTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum, lnum"), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            } else if (chip.byHead && chip.state == StepState.DONE) {
                Text(rs(R.string.by_head), style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActionButton(action: HudAction, onEvent: (Ev) -> Unit) {
    when (action) {
        HudAction.EXECUTE -> WordButton("execute", rs(R.string.execute), { onEvent(Ev.Execute) }, primary = true, iconRes = R.drawable.ic_play, minWidth = 104.dp, height = 56.dp)
        HudAction.CLOSE_DOOR -> WordButton("closeDoor", rs(R.string.close_door), { onEvent(Ev.CloseDoor) }, iconRes = R.drawable.ic_door, minWidth = 104.dp, height = 56.dp)
        HudAction.CONFIRM_LEFT -> WordButton("confirm", rs(R.string.confirm_left), { onEvent(Ev.Confirm) }, primary = true, iconRes = R.drawable.ic_check, minWidth = 104.dp, height = 56.dp)
        HudAction.CONFIRM_RIGHT -> WordButton("confirm", rs(R.string.confirm_right), { onEvent(Ev.Confirm) }, primary = true, iconRes = R.drawable.ic_check, minWidth = 104.dp, height = 56.dp)
        HudAction.SKIP_DEMO -> WordButton("skipDemo", rs(R.string.skip_demo), { onEvent(Ev.SkipDemo) }, iconRes = R.drawable.ic_skip_next, minWidth = 104.dp, height = 56.dp)
        HudAction.NONE -> Unit
    }
}

/** B-pillar readout (design panels[ANG]): 200 × 80 dp, big figures; lit (tick) once the threshold is reached / recorded. */
@Composable
fun AngPanel(ang: AngState, dir: Dir) {
    Glass(200.dp, 80.dp, corner = 22.dp, padding = 8.dp, spacing = 0.dp) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (ang.lit) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.size(26.dp)) { drawCircle(ScColors.IconDisc) }
                    Icon(painter = painterResource(R.drawable.ic_done), contentDescription = null, modifier = Modifier.size(22.dp), tint = ScColors.Done)
                }
                Spacer(Modifier.width(6.dp))
            }
            val text =
                if (ang.range) rs(if (dir == Dir.LEFT) R.string.range_left_label else R.string.range_right_label, ang.deg)
                else "${ang.deg}° / ${ang.threshold ?: 0}°"
            Text(text, style = ScType.ang, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        }
    }
}
