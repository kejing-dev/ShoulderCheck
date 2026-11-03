package io.embodit.shouldercheck.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.shouldercheck.R
import io.embodit.shouldercheck.app.Ev
import io.embodit.shouldercheck.app.P1Page
import io.embodit.shouldercheck.app.ResultUi
import io.embodit.shouldercheck.app.UiState
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Lessons
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.PracticeStatus
import io.embodit.shouldercheck.domain.SawHazard
import io.embodit.shouldercheck.domain.Scorecard
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.SeenVia
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.VolumeLevel
import io.embodit.shouldercheck.ui.components.Dim
import io.embodit.shouldercheck.ui.components.Glass
import io.embodit.shouldercheck.ui.components.LinkButton
import io.embodit.shouldercheck.ui.components.MinTouch
import io.embodit.shouldercheck.ui.components.Probe
import io.embodit.shouldercheck.ui.components.ScType
import io.embodit.shouldercheck.ui.components.Segments
import io.embodit.shouldercheck.ui.components.SettingRow
import io.embodit.shouldercheck.ui.components.StatusLabel
import io.embodit.shouldercheck.ui.components.TitleRow
import io.embodit.shouldercheck.ui.components.WordButton
import io.embodit.shouldercheck.ui.components.Words
import io.embodit.shouldercheck.ui.components.fmt1
import io.embodit.shouldercheck.ui.components.lessonName
import io.embodit.shouldercheck.ui.components.rs
import io.embodit.shouldercheck.ui.components.situationName
import io.embodit.shouldercheck.ui.theme.ScColors
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * P1 centre screen (design panels[P1]): one page at a time. 800 × 520 dp (results / scorecard 800 × 600 on the tall
 * slot), scale 0.8. At most one primary button, four cards / list items and two links per page; controls ≥ 48 dp.
 */
@Composable
fun P1Panel(state: UiState, tall: Boolean, onEvent: (Ev) -> Unit) {
    Glass(800.dp, if (tall) 600.dp else 520.dp) {
        when (state.page) {
            P1Page.LOADING -> Loading()
            P1Page.SIT -> SitPage(state, onEvent)
            P1Page.RANGE -> RangePage(state, onEvent)
            P1Page.HOME -> HomePage(state, onEvent)
            P1Page.LESSONS -> LessonsPage(state, onEvent)
            P1Page.RESULT -> state.result?.let { ResultPage(state, it, onEvent) }
            P1Page.SCORECARD -> ScorePage(state, onEvent)
            P1Page.SET_RESULT -> SetPage(state, onEvent)
            P1Page.SETTINGS -> SettingsPage(state, onEvent)
            P1Page.PAUSED -> PausedPage(onEvent)
            P1Page.UNAVAILABLE -> UnavailablePage(onEvent)
        }
    }
}

@Composable
private fun ColumnScope.Loading() {
    Text(rs(R.string.app_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary)
    Text(rs(R.string.loading), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary)
}

@Composable
private fun EyeOffLine(state: UiState, onEvent: (Ev) -> Unit) {
    if (!state.eyeOff) return
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusLabel(R.drawable.ic_eye_off, rs(R.string.by_head), ScColors.Stale, PicoTheme.typography.labelLarge, 18.dp)
        Text(rs(R.string.eye_off), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        LinkButton("eyeEnable", rs(R.string.eye_enable), { onEvent(Ev.EnableEyes) })
    }
}

// ------------------------------------------------------------------------------------------- S01 / S01B

@Composable
private fun ColumnScope.SitPage(state: UiState, onEvent: (Ev) -> Unit) {
    Text(rs(R.string.app_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary)
    Spacer(Modifier.height(8.dp))
    Text(rs(R.string.sit_hint), style = PicoTheme.typography.headlineSmall, color = PicoTheme.colorScheme.labelPrimary)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painter = painterResource(R.drawable.ic_eye), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Dim(rs(R.string.eye_hint))
    }
    Spacer(Modifier.weight(1f))
    EyeOffLine(state, onEvent)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        WordButton("sit", rs(R.string.sit), { onEvent(Ev.Sit) }, primary = true, iconRes = R.drawable.ic_check, minWidth = 320.dp, height = 56.dp)
    }
}

@Composable
private fun ColumnScope.RangePage(state: UiState, onEvent: (Ev) -> Unit) {
    val r = state.range
    TitleRow(rs(R.string.range_title))
    val right = r?.side == Dir.RIGHT
    Text(rs(if (right) R.string.range_right else R.string.range), style = PicoTheme.typography.headlineSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(rs(if (right) R.string.range_right_label else R.string.range_left_label, r?.liveDeg ?: 0), style = ScType.metric, color = PicoTheme.colorScheme.labelPrimary)
        Dim(rs(R.string.range_hold), maxLines = 1)
    }
    Text(rs(R.string.range_recorded, r?.left?.let { "$it°" } ?: rs(R.string.none), r?.right?.let { "$it°" } ?: rs(R.string.none)), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary)
    if (r?.tooSmall == true) StatusLabel(R.drawable.ic_warning, rs(R.string.range_small), ScColors.Stale, PicoTheme.typography.bodyLarge)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        WordButton("rangeSkip", rs(R.string.range_skip), { onEvent(Ev.SkipRange) }, iconRes = R.drawable.ic_skip_next)
    }
}

// ------------------------------------------------------------------------------------------- S02

@Composable
private fun ColumnScope.HomePage(state: UiState, onEvent: (Ev) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LinkButton("knowBlind", rs(R.string.know_blind), { onEvent(Ev.OpenLessons(1)) })
        Spacer(Modifier.weight(1f))
        WordButton("mute", rs(if (state.muted) R.string.unmute else R.string.mute), { onEvent(Ev.ToggleMute) }, iconRes = if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        WordButton("settings", rs(R.string.settings), { onEvent(Ev.OpenSettings) }, iconRes = R.drawable.ic_settings)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(rs(R.string.app_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary)
        Dim(rs(R.string.home_line), maxLines = 1)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("cardDoor", R.drawable.ic_door, rs(R.string.card_door), rs(R.string.card_door_sub), Modifier.weight(1f)) { onEvent(Ev.OpenLessons(3)) }
        Card("cardLane", R.drawable.ic_car, rs(R.string.card_lane), rs(R.string.card_lane_sub), Modifier.weight(1f)) { onEvent(Ev.OpenLessons(5)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("cardRandom", R.drawable.ic_shuffle, rs(R.string.card_random), rs(R.string.card_random_sub), Modifier.weight(1f)) { onEvent(Ev.OpenRandom) }
        Card("cardScores", R.drawable.ic_bar_chart, rs(R.string.card_scores), rs(R.string.card_scores_sub), Modifier.weight(1f)) { onEvent(Ev.OpenScores) }
    }
    EyeOffLine(state, onEvent)
    Spacer(Modifier.weight(1f))
    val rec = Scorecard.recommendedLesson(state.progress)
    WordButton("recommend", rs(R.string.recommend, rs(R.string.lesson_no, rec, lessonName(rec))), { onEvent(Ev.StartRecommended) }, modifier = Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_play)
}

@Composable
private fun Card(name: String, icon: Int, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Probe(name, onClick)
    Button(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        size = ButtonDefaults.Regular.copy(height = 96.dp, minWidth = 120.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = PicoTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- S03

@Composable
private fun ColumnScope.LessonsPage(state: UiState, onEvent: (Ev) -> Unit) {
    TitleRow(
        rs(R.string.lessons_title),
        leading = { WordButton("back", rs(R.string.back), { onEvent(Ev.Back) }, iconRes = R.drawable.ic_arrow_back) },
        trailing = { WordButton("settings", rs(R.string.settings), { onEvent(Ev.OpenSettings) }, iconRes = R.drawable.ic_settings) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Group(rs(R.string.group_know))
            for (n in Lessons.GROUPS[0]) LessonRow(n, state, onEvent)
            Group(rs(R.string.group_door))
            for (n in Lessons.GROUPS[1]) LessonRow(n, state, onEvent)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Group(rs(R.string.group_lane))
            for (n in Lessons.GROUPS[2]) LessonRow(n, state, onEvent)
        }
    }
    Spacer(Modifier.weight(1f))
    LinkButton("replayDemo", rs(if (state.replayDemo) R.string.replay_demo_on else R.string.replay_demo_off), { onEvent(Ev.ToggleReplayDemo) })
}

@Composable
private fun Group(text: String) {
    Text(text, style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun LessonRow(n: Int, state: UiState, onEvent: (Ev) -> Unit) {
    val passed = n in state.progress.passed
    val click = { onEvent(Ev.ChooseLesson(n)) }
    Probe("lesson$n", click)
    Button(
        onClick = click,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        size = ButtonDefaults.Regular.copy(height = 56.dp, minWidth = 120.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (state.lessonFocus != 0 && lessonGroup(n) == lessonGroup(state.lessonFocus)) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(rs(R.string.lesson_no, n, lessonName(n)), style = PicoTheme.typography.titleSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (passed) StatusLabel(R.drawable.ic_done, rs(R.string.lesson_passed), ScColors.Done, PicoTheme.typography.labelMedium, 16.dp)
            else StatusLabel(R.drawable.ic_pending, rs(R.string.lesson_open), ScColors.Missing, PicoTheme.typography.labelMedium, 16.dp)
        }
    }
}

private fun lessonGroup(n: Int): Int = Lessons.GROUPS.indexOfFirst { n in it }

// ------------------------------------------------------------------------------------------- S10

@Composable
private fun ColumnScope.ResultPage(state: UiState, ui: ResultUi, onEvent: (Ev) -> Unit) {
    val r = ui.result
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusLabel(Words.outcomeIcon(r.outcome), rs(Words.outcome(r.outcome)), Words.outcomeColor(r.outcome), PicoTheme.typography.headlineMedium, 30.dp)
        StatusLabel(if (r.mode == JudgeMode.EYE) R.drawable.ic_eye else R.drawable.ic_eye_off, rs(if (r.mode == JudgeMode.EYE) R.string.mode_eye else R.string.mode_head), if (r.mode == JudgeMode.EYE) ScColors.Done else ScColors.Stale, PicoTheme.typography.labelLarge, 18.dp)
        Spacer(Modifier.weight(1f))
        Dim(ui.lesson?.let { rs(R.string.lesson_no, it, lessonName(it)) + " · " } .orEmpty() + rs(R.string.trial, ui.trialNo, ui.trialCount), maxLines = 1)
    }
    val explain =
        when {
            r.situation == Situation.MIRRORS -> rs(R.string.explain_lesson1)
            r.situation == Situation.BLIND_SPOT -> rs(R.string.explain_lesson2)
            r.outcome == Outcome.CLEAR -> rs(R.string.explain_clear)
            r.outcome == Outcome.WAITED -> rs(R.string.explain_waited)
            r.outcome == Outcome.LUCKY -> rs(R.string.lucky)
            r.outcome == Outcome.NEAR_MISS -> rs(R.string.explain_miss)
            r.outcome == Outcome.TIMEOUT -> rs(R.string.explain_timeout)
            else -> rs(R.string.explain_skipped)
        }
    Text(explain, style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
    if (r.signalTip) StatusLabel(R.drawable.ic_info, rs(R.string.law), ScColors.Stale, PicoTheme.typography.bodyMedium, 18.dp)
    ui.rearNoteDeg?.let { Dim(rs(R.string.rear_note, it), maxLines = 2) }
    if (r.situation == Situation.MIRRORS) {
        Text(rs(R.string.blind, fmt1(ui.blindLeft ?: 0.0)) + " · " + rs(R.string.blind_right, fmt1(ui.blindRight ?: 0.0)), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary)
    }
    // step timeline
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (st in r.steps) {
            Column(modifier = Modifier.weight(1f).heightIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val icon = if (st.done) R.drawable.ic_done else if (st.wrong) R.drawable.ic_wrong else if (st.stale) R.drawable.ic_timer else R.drawable.ic_pending
                StatusLabel(icon, rs(Words.step(st.step)), if (st.done) ScColors.Done else if (st.wrong) ScColors.Miss else ScColors.Missing, PicoTheme.typography.labelLarge, 18.dp)
                Dim(st.doneAt?.let { rs(R.string.sec_fmt, fmt1(it)) } ?: rs(R.string.none), maxLines = 1)
                if (st.judgedByHead && r.mode == JudgeMode.EYE) Dim(rs(R.string.by_head), maxLines = 1)
                else if (st.lowConfidence) Dim(rs(R.string.low_conf), maxLines = 1)
            }
        }
    }
    // metrics
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Metric(rs(R.string.metric_turn), "${r.shoulderPeak.roundToInt()}°", rs(R.string.metric_needed, r.threshold.roundToInt()), Modifier.weight(1f))
        Metric(rs(R.string.metric_check), r.checkTime?.let { rs(R.string.sec_fmt, fmt1(it)) } ?: rs(R.string.none), "", Modifier.weight(1f))
        val saw =
            when (r.saw) {
                SawHazard.NO_HAZARD -> rs(R.string.saw_none)
                SawHazard.NOT_SEEN -> rs(R.string.saw_no)
                SawHazard.SAW ->
                    when (r.seenVia) {
                        SeenVia.MIRROR_INNER -> rs(R.string.saw_in, rs(Words.mirror(MirrorId.INNER)))
                        SeenVia.MIRROR_LEFT -> rs(R.string.saw_in, rs(Words.mirror(MirrorId.LEFT)))
                        SeenVia.MIRROR_RIGHT -> rs(R.string.saw_in, rs(Words.mirror(MirrorId.RIGHT)))
                        else -> rs(R.string.saw_yes)
                    }
            }
        Metric(rs(R.string.metric_saw), saw, "", Modifier.weight(1.4f), small = true)
    }
    // gaze heat strip (360° × 80°)
    Dim(rs(if (r.mode == JudgeMode.EYE) R.string.heat else R.string.heat_head), maxLines = 1)
    HeatStrip(r.trace.map { it.yaw }, r.side, Modifier.fillMaxWidth().height(44.dp))
    if (r.actualOrder.isNotEmpty()) Dim(rs(R.string.order, r.actualOrder.map { rs(Words.step(it)) }.joinToString(" → ")), maxLines = 1)
    if (ui.lessonEnd) {
        val right = ui.lessonCorrect
        StatusLabel(if (ui.lessonPassed) R.drawable.ic_done else R.drawable.ic_warning, rs(if (ui.lessonPassed) R.string.lesson_passed_line else R.string.lesson_failed_line, ui.lesson ?: 0, right, ui.trialCount), if (ui.lessonPassed) ScColors.Done else ScColors.Stale, PicoTheme.typography.titleMedium)
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            ui.setEnd -> WordButton("setResult", rs(R.string.set_result), { onEvent(Ev.SetResult) }, primary = true, iconRes = R.drawable.ic_bar_chart)
            ui.lessonEnd && ui.lessonPassed -> WordButton("lessonList", rs(R.string.lesson_list), { onEvent(Ev.LessonList) }, primary = true, iconRes = R.drawable.ic_school)
            ui.lessonEnd -> {
                WordButton("retryLesson", rs(R.string.retry_lesson), { onEvent(Ev.RetryLesson) }, primary = true, iconRes = R.drawable.ic_replay)
                WordButton("lessonList", rs(R.string.lesson_list), { onEvent(Ev.LessonList) }, iconRes = R.drawable.ic_school)
            }
            else -> {
                WordButton("next", rs(R.string.next), { onEvent(Ev.Next) }, primary = true, iconRes = R.drawable.ic_skip_next)
                WordButton("retry", rs(R.string.retry), { onEvent(Ev.Retry) }, iconRes = R.drawable.ic_replay)
            }
        }
        WordButton("replay", rs(R.string.replay), { onEvent(Ev.Replay) }, iconRes = R.drawable.ic_play)
        Spacer(Modifier.weight(1f))
        LinkButton("home", rs(R.string.home), { onEvent(Ev.Home) })
    }
}

@Composable
private fun Metric(label: String, value: String, sub: String, modifier: Modifier, small: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Dim(label, maxLines = 1)
        Text(value, style = if (small) PicoTheme.typography.titleLarge else ScType.metric, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Dim(sub, maxLines = 1)
    }
}

/**
 * Gaze heat strip: the cabin all round (yaw −180 … 180), the three mirror directions and the two blind-spot sectors
 * (100°–150° each side) marked, the trace's yaw density as the bars.
 */
@Composable
private fun HeatStrip(yaws: List<Double>, side: TrafficSide, modifier: Modifier) {
    val cabin = androidx.compose.runtime.remember(side) { Cabin(Seat.DRIVER, side) }
    val line = PicoTheme.colorScheme.labelPrimary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun x(yaw: Double) = ((yaw + 180.0) / 360.0 * w).toFloat()
        drawRoundRect(Color.Black.copy(alpha = 0.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f))
        for (s in listOf(-1, 1)) drawRect(ScColors.Miss.copy(alpha = 0.22f), Offset(x(s * if (s < 0) 150.0 else 100.0), 0f), Size(x(150.0) - x(100.0), h))
        val bins = DoubleArray(72)
        for (y in yaws) {
            for (i in bins.indices) {
                val c = -177.5 + i * 5.0
                val d = Angles.wrap(y - c)
                bins[i] += exp(-(d * d) / (2 * 6.0 * 6.0))
            }
        }
        val max = bins.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val bw = w / bins.size
        for (i in bins.indices) {
            val k = (bins[i] / max).toFloat()
            if (k < 0.02f) continue
            drawRect(ScColors.Gaze.copy(alpha = 0.35f + 0.65f * k), Offset(i * bw, h * (1f - k)), Size(bw * 0.9f, h * k))
        }
        for (m in cabin.mirrors) drawLine(Color.White, Offset(x(m.yawDeg), 0f), Offset(x(m.yawDeg), h), strokeWidth = 3f)
        drawLine(line, Offset(x(0.0), h * 0.7f), Offset(x(0.0), h), strokeWidth = 2f)
    }
}

// ------------------------------------------------------------------------------------------- S12 / S14

@Composable
private fun ColumnScope.ScorePage(state: UiState, onEvent: (Ev) -> Unit) {
    TitleRow(rs(R.string.scores_title), leading = { WordButton("back", rs(R.string.back), { onEvent(Ev.Back) }, iconRes = R.drawable.ic_arrow_back) }, trailing = { WordButton("settings", rs(R.string.settings), { onEvent(Ev.OpenSettings) }, iconRes = R.drawable.ic_settings) })
    val p = state.progress
    val h = Scorecard.header(p)
    Text(
        rs(R.string.scores_header, h.blindSpotLeft?.let { fmt1(it) } ?: "—", h.blindSpotRight?.let { fmt1(it) } ?: "—", h.rangeLeft?.roundToInt()?.toString() ?: "—", h.rangeRight?.roundToInt()?.toString() ?: "—"),
        style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    val rows = Scorecard.rows(p.records)
    ScoreRowView(rs(R.string.col_situation), rs(R.string.col_rate), rs(R.string.col_check), rs(R.string.col_turn), rs(R.string.col_miss), null, header = true)
    for (r in rows) {
        ScoreRowView(situationName(r.situation), if (r.attempts == 0) "—" else "${(r.correctRate * 100).roundToInt()}% (${r.correct}/${r.attempts})", r.medianCheckTime?.let { rs(R.string.sec_fmt, fmt1(it)) } ?: "—", r.medianShoulder?.let { "${it.roundToInt()}°" } ?: "—", if (r.attempts == 0) "—" else r.nearMisses.toString(), r.status)
    }
    val o = Scorecard.overall(p.records)
    ScoreRowView(rs(R.string.overall), if (o.attempts == 0) "—" else "${(o.correctRate * 100).roundToInt()}%", o.medianCheckTime?.let { rs(R.string.sec_fmt, fmt1(it)) } ?: "—", o.medianShoulder?.let { "${it.roundToInt()}°" } ?: "—", if (o.attempts == 0) "—" else o.nearMisses.toString(), o.status, bold = true)
    Spacer(Modifier.weight(1f))
    ClearRow(state, onEvent)
}

@Composable
private fun ClearRow(state: UiState, onEvent: (Ev) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.confirmClear) {
            Text(rs(R.string.clear_confirm), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
            WordButton("cancel", rs(R.string.cancel), { onEvent(Ev.CancelConfirm) })
            WordButton("confirmClear", rs(R.string.confirm_clear), { onEvent(Ev.ConfirmClear) }, danger = true)
        } else {
            LinkButton("clearScores", rs(R.string.clear_scores), { onEvent(Ev.ClearScores) })
        }
    }
}

@Composable
private fun ScoreRowView(name: String, rate: String, check: String, turn: String, misses: String, status: PracticeStatus?, header: Boolean = false, bold: Boolean = false) {
    val style = if (header) PicoTheme.typography.labelLarge else if (bold) PicoTheme.typography.titleMedium else PicoTheme.typography.bodyLarge
    val color = if (header) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelPrimary
    Row(modifier = Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = if (header || bold) style else PicoTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2.2f))
        Text(rate, style = style, color = color, maxLines = 1, modifier = Modifier.weight(1.5f))
        Text(check, style = style, color = color, maxLines = 1, modifier = Modifier.weight(1f))
        Text(turn, style = style, color = color, maxLines = 1, modifier = Modifier.weight(0.9f))
        Text(misses, style = style, color = color, maxLines = 1, modifier = Modifier.weight(0.8f))
        Box(modifier = Modifier.weight(1.3f)) {
            when (status) {
                PracticeStatus.STEADY -> StatusLabel(R.drawable.ic_done, rs(R.string.steady), ScColors.Done, PicoTheme.typography.labelLarge, 16.dp)
                PracticeStatus.PRACTICE -> StatusLabel(R.drawable.ic_refresh, rs(R.string.practice), ScColors.Stale, PicoTheme.typography.labelLarge, 16.dp)
                PracticeStatus.NOT_YET -> StatusLabel(R.drawable.ic_pending, rs(R.string.not_yet), ScColors.Missing, PicoTheme.typography.labelLarge, 16.dp)
                null -> if (header) Text(rs(R.string.col_status), style = style, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColumnScope.SetPage(state: UiState, onEvent: (Ev) -> Unit) {
    val s = state.setSummary
    TitleRow(rs(R.string.set_title), trailing = { Dim(s?.let { rs(R.string.set_seed, it.seed.toInt()) } ?: "", maxLines = 1) })
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Metric(rs(R.string.set_rate), s?.let { "${(it.correctRate * 100).roundToInt()}%" } ?: "—", "", Modifier.weight(1f))
        Metric(rs(R.string.set_check), s?.meanCheckTime?.let { rs(R.string.sec_fmt, fmt1(it)) } ?: "—", "", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Metric(rs(R.string.set_turn), s?.medianShoulder?.let { "${it.roundToInt()}°" } ?: "—", "", Modifier.weight(1f))
        Metric(rs(R.string.set_misses), s?.nearMisses?.toString() ?: "—", "", Modifier.weight(1f))
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WordButton("setAgain", rs(R.string.again), { onEvent(Ev.SetAgain) }, primary = true, iconRes = R.drawable.ic_shuffle)
        WordButton("seeScores", rs(R.string.see_scores), { onEvent(Ev.OpenScores) }, iconRes = R.drawable.ic_bar_chart)
        WordButton("home", rs(R.string.home), { onEvent(Ev.Home) }, iconRes = R.drawable.ic_home)
    }
}

// ------------------------------------------------------------------------------------------- S15

@Composable
private fun ColumnScope.SettingsPage(state: UiState, onEvent: (Ev) -> Unit) {
    val st = state.progress.settings
    TitleRow(rs(R.string.settings_title), trailing = { WordButton("back", rs(R.string.back), { onEvent(Ev.Back) }, iconRes = R.drawable.ic_arrow_back) })
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(rs(R.string.col_practice), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
            SettingRow(rs(R.string.show_gaze)) { SwitchProbe("showGaze", st.showGaze) { onEvent(Ev.SetShowGaze(it)) } }
            SettingRow(rs(R.string.hints_lessons)) { SwitchProbe("hintsLessons", st.hintsLessons) { onEvent(Ev.SetHintsLessons(it)) } }
            SettingRow(rs(R.string.hints_free)) { SwitchProbe("hintsFree", st.hintsFree) { onEvent(Ev.SetHintsFree(it)) } }
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                WordButton("remeasure", rs(R.string.remeasure), { onEvent(Ev.Remeasure) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_refresh)
            }
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                WordButton("readjust", rs(R.string.readjust), { onEvent(Ev.ReadjustMirrors) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_center)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(rs(R.string.col_system), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
            val zh = state.language == Language.ZH || (state.language == Language.SYSTEM && rs(R.string.lang_code) == "zh")
            SettingRow(rs(R.string.language)) { Segments("lang", listOf(rs(R.string.lang_zh), rs(R.string.lang_en)), if (zh) 0 else 1, { i -> onEvent(Ev.SetLanguage(if (i == 0) Language.ZH else Language.EN)) }, 196.dp) }
            SettingRow(rs(R.string.traffic)) { Segments("traffic", listOf(rs(R.string.traffic_right), rs(R.string.traffic_left)), if (st.traffic == TrafficSide.RIGHT_HAND) 0 else 1, { i -> onEvent(Ev.SetTraffic(if (i == 0) TrafficSide.RIGHT_HAND else TrafficSide.LEFT_HAND)) }, 196.dp) }
            SettingRow(rs(R.string.volume)) {
                VolumeControl(st.volume, st.muted, onEvent)
            }
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordButton("recentre", rs(R.string.recentre), { onEvent(Ev.Recentre) }, iconRes = R.drawable.ic_center)
                Spacer(Modifier.weight(1f))
                LinkButton("about", rs(R.string.about), { onEvent(Ev.ToggleAbout) })
            }
            ClearRow(state, onEvent)
        }
    }
    if (state.showAbout) Dim(rs(R.string.about_text), maxLines = 2)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.confirmExit) {
            Text(rs(R.string.exit_confirm), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
            WordButton("cancelExit", rs(R.string.cancel), { onEvent(Ev.CancelConfirm) })
            WordButton("confirmExit", rs(R.string.exit_do), { onEvent(Ev.ConfirmExit) }, danger = true, iconRes = R.drawable.ic_logout)
        } else {
            WordButton("exit", rs(R.string.exit), { onEvent(Ev.Exit) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_logout)
        }
    }
}

@Composable
private fun SwitchProbe(name: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Probe(name) { onChange(!checked) }
    Box(modifier = Modifier.heightIn(min = MinTouch), contentAlignment = Alignment.Center) { Switch(checked = checked, onCheckedChange = onChange) }
}

/** Master volume: a slider with three detents (低 / 中 / 高, snaps) + percent, and the worded mute button. */
@Composable
private fun VolumeControl(level: VolumeLevel, muted: Boolean, onEvent: (Ev) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val v = level.ordinal / 2f
        Box(modifier = Modifier.width(96.dp).heightIn(min = MinTouch), contentAlignment = Alignment.Center) {
            Slider(value = v, onValueChange = { x -> val l = VolumeLevel.values()[(x * 2).roundToInt().coerceIn(0, 2)]; if (l != level) onEvent(Ev.SetVolume(l)) }, valueRange = 0f..1f)
        }
        Probe("volLow") { onEvent(Ev.SetVolume(VolumeLevel.LOW)) }
        Probe("volMid") { onEvent(Ev.SetVolume(VolumeLevel.MID)) }
        Probe("volHigh") { onEvent(Ev.SetVolume(VolumeLevel.HIGH)) }
        val word = rs(when (level) { VolumeLevel.LOW -> R.string.vol_low; VolumeLevel.MID -> R.string.vol_mid; VolumeLevel.HIGH -> R.string.vol_high })
        Text(if (muted) "0%" else "$word ${(level.gain * 100).roundToInt()}%", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        WordButton("mute", rs(if (muted) R.string.unmute else R.string.mute), { onEvent(Ev.ToggleMute) }, iconRes = if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume, minWidth = 48.dp)
    }
}

// ------------------------------------------------------------------------------------------- S16 / S19

@Composable
private fun ColumnScope.PausedPage(onEvent: (Ev) -> Unit) {
    TitleRow(rs(R.string.paused_title))
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WordButton("resume", rs(R.string.resume), { onEvent(Ev.Resume) }, modifier = Modifier.width(420.dp), primary = true, iconRes = R.drawable.ic_play, height = 56.dp)
        WordButton("skipQ", rs(R.string.skip_q), { onEvent(Ev.SkipQuestion) }, modifier = Modifier.width(420.dp), iconRes = R.drawable.ic_skip_next, height = 56.dp)
        WordButton("restartQ", rs(R.string.restart_q), { onEvent(Ev.RestartQuestion) }, modifier = Modifier.width(420.dp), iconRes = R.drawable.ic_replay, height = 56.dp)
        WordButton("home", rs(R.string.home), { onEvent(Ev.Home) }, modifier = Modifier.width(420.dp), iconRes = R.drawable.ic_home, height = 56.dp)
    }
}

@Composable
private fun ColumnScope.UnavailablePage(onEvent: (Ev) -> Unit) {
    TitleRow(rs(R.string.app_title))
    StatusLabel(R.drawable.ic_warning, rs(R.string.unavailable), ScColors.Stale, PicoTheme.typography.headlineSmall)
    Spacer(Modifier.weight(1f))
    WordButton("quit", rs(R.string.quit), { onEvent(Ev.ConfirmExit) }, primary = true, iconRes = R.drawable.ic_logout)
}
