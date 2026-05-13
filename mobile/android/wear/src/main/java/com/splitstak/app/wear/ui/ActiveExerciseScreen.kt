package com.splitstak.app.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.splitstak.app.wear.data.ActionSender
import com.splitstak.app.wear.data.Exercise
import com.splitstak.app.wear.data.SetEntry
import com.splitstak.app.wear.data.Snapshot
import com.splitstak.app.wear.data.WatchState
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The main interaction surface — one exercise, one set displayed at a time.
 *
 * Layout (compact for the Pixel Watch's ~390px circular face):
 *   - Exercise name + target (top)
 *   - "‹ SET 2/3 ›" row (taps cycle to prev/next exercise)
 *   - Side-by-side value boxes (WT / RPS, or mode-appropriate)
 *       · Tap a box to focus it (orange pulsing border).
 *       · Rotating the crown adjusts the focused value.
 *       · Tap the focused box again to release focus.
 *   - Done circle (tap to mark the set complete)
 *   - Progress dots
 *
 * On hitting a PR, a full-face black overlay flashes "PR" for ~3s before
 * fading. Every interaction calls [ActionSender] which:
 *   1. Mutates WatchState locally for instant UI feedback
 *   2. Sends the action to the phone over MessageClient
 *   3. The phone applies the same mutation against the source of truth
 *      and re-publishes the snapshot, reconciling any drift
 */
@Composable
fun ActiveExerciseScreen(snapshot: Snapshot) {
    val context = LocalContext.current
    val selectedId by WatchState.widgetSelectedFlow.collectAsState()
    val exercise = snapshot.exercises.firstOrNull { it.id == selectedId }
        ?: snapshot.currentExercise()
        ?: return

    val setIdx = remember(exercise.id, exercise.sets) {
        // Default to the first incomplete set; falls back to last set.
        val idx = exercise.sets.indexOfFirst { !it.d }
        if (idx >= 0) idx else (exercise.sets.size - 1).coerceAtLeast(0)
    }

    // Which box is focused for crown input. null = none (crown is idle).
    var focused by remember(exercise.id, setIdx) { mutableStateOf<String?>(null) }

    // Compose focus + rotary handling. The outer Box always holds focus so
    // crown events route to our handler regardless of which UI box is
    // visually focused-for-editing.
    val focusRequester = remember { FocusRequester() }
    var rotaryAccum by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        // requestFocus() can throw if the modifier isn't attached yet;
        // a tiny delay lets the focusable node settle.
        try {
            delay(50)
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    // PR overlay — rising edge of exercise.isPr while id stays constant.
    var showPrOverlay by remember { mutableStateOf(false) }
    val prevPr = remember(exercise.id) { mutableStateOf(exercise.isPr) }
    LaunchedEffect(exercise.id, exercise.isPr) {
        if (exercise.isPr && !prevPr.value) {
            showPrOverlay = true
            delay(3000)
            showPrOverlay = false
        }
        prevPr.value = exercise.isPr
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitstakColors.Bg)
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                val f = focused ?: return@onRotaryScrollEvent false
                rotaryAccum += event.verticalScrollPixels
                // Roughly one detent on the Pixel Watch crown. Lower
                // value = faster ramp; higher = more controlled.
                val threshold = 40f
                while (abs(rotaryAccum) >= threshold) {
                    val sign = if (rotaryAccum > 0) 1 else -1
                    rotaryAccum -= sign * threshold
                    when (f) {
                        "weight" -> ActionSender.incWeight(context, exercise.id, setIdx, sign)
                        "reps"   -> ActionSender.incReps(context, exercise.id, setIdx, sign)
                        "hold"   -> ActionSender.incHold(context, exercise.id, setIdx, sign)
                        "ctime"  -> ActionSender.incTime(context, exercise.id, sign.toDouble())
                        "cdist"  -> ActionSender.incDistance(context, exercise.id, sign.toDouble())
                    }
                }
                true
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = exercise.name.uppercase(),
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SplitstakColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Target (or PR tag inline when relevant)
            val targetLine = buildString {
                if (exercise.target.isNotEmpty()) append(exercise.target)
                if (exercise.isPr) {
                    if (isNotEmpty()) append(" · ")
                    append("PR")
                }
            }
            if (targetLine.isNotEmpty()) {
                Text(
                    text = targetLine,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = SplitstakColors.Accent,
                    maxLines = 1
                )
            }

            // SET row with ‹ ›
            ExerciseNavRow(
                label = when (exercise.mode) {
                    "cardio" -> "CARDIO"
                    else -> "SET ${setIdx + 1}/${exercise.sets.size}"
                },
                onPrev = { ActionSender.nav(context, -1) },
                onNext = { ActionSender.nav(context, 1) }
            )

            // Value boxes
            BodyBoxes(
                exercise = exercise,
                setIdx = setIdx,
                focused = focused,
                onToggleFocus = { tag ->
                    focused = if (focused == tag) null else tag
                }
            )

            // Done circle
            val isDone: Boolean = when (exercise.mode) {
                "cardio" -> exercise.cardio?.done == true
                else -> exercise.sets.getOrNull(setIdx)?.d == true
            }
            DoneCircle(
                done = isDone,
                onClick = {
                    if (exercise.mode == "cardio") {
                        ActionSender.toggleDone(context, exercise.id, -1)
                    } else {
                        ActionSender.toggleDone(context, exercise.id, setIdx)
                    }
                }
            )

            // Progress dots
            ProgressDots(exercises = snapshot.exercises)
        }

        // Crown indicator — pulsing arrow on the right edge (Pixel Watch
        // crown is right-mounted) when a box is focused.
        if (focused != null) {
            val pulse = rememberInfiniteTransition(label = "crown-pulse")
            val pulseAlpha by pulse.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "crown-pulse-alpha"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 2.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "›",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SplitstakColors.Accent,
                    modifier = Modifier.alpha(pulseAlpha)
                )
            }
        }

        // PR overlay — full face black + giant "PR"
        if (showPrOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SplitstakColors.Bg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PR",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Black,
                    color = SplitstakColors.Accent
                )
            }
        }
    }
}

@Composable
private fun ExerciseNavRow(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NavArrow(label = "‹", onClick = onPrev)
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = SplitstakColors.TextDim,
            modifier = Modifier.width(58.dp),
            textAlign = TextAlign.Center
        )
        NavArrow(label = "›", onClick = onNext)
    }
}

@Composable
private fun NavArrow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(SplitstakColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = SplitstakColors.Text
        )
    }
}

@Composable
private fun BodyBoxes(
    exercise: Exercise,
    setIdx: Int,
    focused: String?,
    onToggleFocus: (String) -> Unit
) {
    val set = exercise.sets.getOrNull(setIdx) ?: SetEntry("", "", "", false)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (exercise.mode) {
            "bodyweight" -> {
                ValueBox("RPS", set.r, focused == "reps") { onToggleFocus("reps") }
            }
            "time" -> {
                ValueBox("SEC", set.t, focused == "hold") { onToggleFocus("hold") }
            }
            "cardio" -> {
                val c = exercise.cardio
                ValueBox("MIN", c?.time ?: "", focused == "ctime") { onToggleFocus("ctime") }
                ValueBox("MI", c?.distance ?: "", focused == "cdist") { onToggleFocus("cdist") }
            }
            else -> {
                ValueBox("WT", set.w, focused == "weight") { onToggleFocus("weight") }
                ValueBox("RPS", set.r, focused == "reps") { onToggleFocus("reps") }
            }
        }
    }
}

@Composable
private fun ValueBox(
    label: String,
    value: String,
    focused: Boolean,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "box-pulse-$label")
    val pulseAlpha by pulse.animateFloat(
        initialValue = if (focused) 0.4f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "box-pulse-$label-alpha"
    )
    val borderColor: Color = if (focused) {
        SplitstakColors.Accent.copy(alpha = pulseAlpha)
    } else {
        SplitstakColors.Border
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = SplitstakColors.TextFaint
        )
        Spacer(Modifier.height(1.dp))
        Box(
            modifier = Modifier
                .width(54.dp)
                .height(34.dp)
                .background(SplitstakColors.Surface)
                .border(1.5.dp, borderColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value.ifEmpty { "—" },
                fontFamily = FontFamily.Monospace,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = SplitstakColors.Text
            )
        }
    }
}

@Composable
private fun DoneCircle(done: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (done) SplitstakColors.Accent else SplitstakColors.Surface,
            contentColor = if (done) SplitstakColors.Bg else SplitstakColors.TextDim
        )
    ) {
        Text(
            text = if (done) "✓" else "○",
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (done) SplitstakColors.Bg else SplitstakColors.TextDim
        )
    }
}

@Composable
private fun ProgressDots(exercises: List<Exercise>) {
    if (exercises.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (ex in exercises) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (ex.allComplete) SplitstakColors.Accent
                        else SplitstakColors.Border
                    )
            )
        }
    }
}
