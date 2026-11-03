package io.embodit.shouldercheck.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.embodit.shouldercheck.R
import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.Lesson
import io.embodit.shouldercheck.domain.Lessons
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Step
import io.embodit.shouldercheck.ui.theme.ScColors

/** Copy lookups shared by the panels (all text from string resources; lesson names from the content table). */
internal object Words {
    fun step(s: Step): Int =
        when (s) {
            Step.INNER_MIRROR -> R.string.step_inner
            Step.LEFT_MIRROR -> R.string.step_left_mirror
            Step.RIGHT_MIRROR -> R.string.step_right_mirror
            Step.SHOULDER_LEFT, Step.SHOULDER_RIGHT -> R.string.step_shoulder
            Step.SIGNAL_LEFT -> R.string.step_signal_left
            Step.SIGNAL_RIGHT -> R.string.step_signal_right
            Step.FAR_HAND -> R.string.step_far_hand
            Step.CONFIRM_LEFT -> R.string.step_confirm_left
            Step.CONFIRM_RIGHT -> R.string.step_confirm_right
            Step.SEE_HAZARD -> R.string.step_see
        }

    fun demo(s: Step): Int =
        when (s) {
            Step.INNER_MIRROR -> R.string.demo_inner
            Step.LEFT_MIRROR -> R.string.demo_left_mirror
            Step.RIGHT_MIRROR -> R.string.demo_right_mirror
            Step.SHOULDER_LEFT -> R.string.demo_shoulder_left
            Step.SHOULDER_RIGHT -> R.string.demo_shoulder_right
            Step.SIGNAL_LEFT -> R.string.demo_signal_left
            Step.SIGNAL_RIGHT -> R.string.demo_signal_right
            Step.FAR_HAND -> R.string.demo_far_hand
            Step.CONFIRM_LEFT, Step.CONFIRM_RIGHT -> R.string.demo_confirm
            Step.SEE_HAZARD -> R.string.demo_see
        }

    fun task(s: Situation): Int =
        when (s) {
            Situation.LANE_LEFT -> R.string.task_lane_left
            Situation.LANE_RIGHT -> R.string.task_lane_right
            Situation.PULL_OUT -> R.string.task_pull_out
            Situation.RIGHT_TURN -> R.string.task_right_turn
            Situation.DOOR_DRIVER -> R.string.task_door_driver
            Situation.DOOR_REAR -> R.string.task_door_rear
            Situation.MIRRORS -> R.string.task_mirrors
            Situation.BLIND_SPOT -> R.string.task_blind_spot
        }

    fun outcome(o: Outcome): Int =
        when (o) {
            Outcome.CLEAR -> R.string.outcome_clear
            Outcome.WAITED -> R.string.outcome_waited
            Outcome.LUCKY -> R.string.outcome_lucky
            Outcome.NEAR_MISS -> R.string.outcome_miss
            Outcome.TIMEOUT -> R.string.outcome_timeout
            Outcome.SKIPPED -> R.string.outcome_skipped
        }

    fun outcomeIcon(o: Outcome): Int =
        when (o) {
            Outcome.CLEAR -> R.drawable.ic_done
            Outcome.WAITED -> R.drawable.ic_hourglass
            Outcome.LUCKY -> R.drawable.ic_warning
            Outcome.NEAR_MISS -> R.drawable.ic_error
            Outcome.TIMEOUT -> R.drawable.ic_timer
            Outcome.SKIPPED -> R.drawable.ic_skip_next
        }

    fun outcomeColor(o: Outcome): Color =
        when (o) {
            Outcome.CLEAR, Outcome.WAITED -> ScColors.Done
            Outcome.LUCKY -> ScColors.Lucky
            Outcome.NEAR_MISS -> ScColors.Miss
            Outcome.TIMEOUT -> ScColors.Stale
            Outcome.SKIPPED -> ScColors.Missing
        }

    fun kind(k: ActorKind?): Int =
        when (k) {
            ActorKind.SEDAN -> R.string.kind_sedan
            ActorKind.SUV -> R.string.kind_suv
            ActorKind.EBIKE -> R.string.kind_ebike
            ActorKind.BIKE -> R.string.kind_bike
            ActorKind.MOTO -> R.string.kind_moto
            ActorKind.BUS -> R.string.kind_bus
            null -> R.string.kind_any
        }

    fun dir(d: Dir): Int = if (d == Dir.LEFT) R.string.dir_left else R.string.dir_right

    fun mirror(id: MirrorId): Int =
        when (id) {
            MirrorId.INNER -> R.string.mirror_inner
            MirrorId.LEFT -> R.string.mirror_left
            MirrorId.RIGHT -> R.string.mirror_right
        }
}

/** Lesson name in the panel language. */
@Composable
internal fun lessonName(l: Lesson): String = if (LocalScResources.current.isZh()) l.zh else l.en

@Composable
internal fun lessonName(n: Int): String = lessonName(Lessons.byN(n))

@Composable
internal fun situationName(s: Situation): String = lessonName(s.lesson)

internal fun fmt1(d: Double): String = String.format(java.util.Locale.US, "%.1f", d)
