package com.splitstak.app.wear.ui

import android.content.Context
import android.view.Surface
import android.view.WindowManager
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
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Three focusable rectangles, all driven by the same pattern:
 *   1. Tap the rectangle → orange pulsing border (focused).
 *   2. Spin the crown → adjusts the focused field. The crown indicator
 *      arrow pulses on the side the crown is physically on.
 *   3. Tap again → release focus, crown becomes idle.
 *
 * The three rectangles:
 *   - Exercise box (top): name + target + "SET 2/3". Crown cycles exercises.
 *   - WT / RPS boxes (or mode-equivalent): crown adjusts the value.
 *
 * The done circle below is a plain tap toggle; no focus needed because
 * there's nothing to "adjust" on it.
 *
 * On hitting a PR, a full-face black overlay flashes "PR" for ~3s. Every
 * interaction calls [ActionSender], which optimistically mutates the
 * watch's local snapshot for instant feedback and sends the same action
 * to the phone over MessageClient for the source-of-truth update.
 *
 * Crown side: Wear OS rotates the display 180° when the user picks
 * "Worn on right wrist" so the physical crown stays on the dominant-hand
 * side. We detect that rotation and flip the arrow indicator accordingly.
 */
@Composable
fun ActiveExerciseScreen(snapshot: Snapshot) {
    val context = LocalContext.current
    val selectedId by WatchState.widgetSelectedFlow.collectAsState()
    val exercise = snapshot.exercises.firstOrNull { it.id == selectedId }
        ?: snapshot.currentExercise()
        ?: return

    val isCardio = exercise.kind == "cardio"

    val setIdx = remember(exercise.id, exercise.sets) {
        // Default to the first incomplete set; falls back to last set.
        val idx = exercise.sets.indexOfFirst { !it.d }
        if (idx >= 0) idx else (exercise.sets.size - 1).coerceAtLeast(0)
    }

    // Which rectangle is focused for crown input. null = none (crown idle).
    // Intentionally NOT keyed on exercise.id — when the user holds the
    // exercise box focused and spins the crown to nav, we want focus to
    // persist across the resulting exercise change so they can keep going.
    var focused by remember { mutableStateOf<String?>(null) }

    // Compose focus + rotary handling. The outer Box always holds focus so
    // crown events route to our handler regardless of which UI box is
    // visually focused-for-editing.
    val focusRequester = remember { FocusRequester() }
    var rotaryAccum by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        try {
            delay(50)
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    val isLefty = remember { detectLefty(context) }

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
                // One crown detent ≈ 40px of scroll. Exercise nav uses a
                // larger threshold so the user doesn't blow past multiple
                // exercises with a single roll.
                val threshold = if (f == "exercise") 70f else 40f
                while (abs(rotaryAccum) >= threshold) {
                    val sign = if (rotaryAccum > 0) 1 else -1
                    rotaryAccum -= sign * threshold
                    when (f) {
                        "exercise" -> ActionSender.nav(context, sign)
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
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ExerciseBox(
                exercise = exercise,
                setIdx = setIdx,
                isCardio = isCardio,
                focused = focused == "exercise",
                onClick = {
                    focused = if (focused == "exercise") null else "exercise"
                }
            )

            BodyBoxes(
                exercise = exercise,
                setIdx = setIdx,
                isCardio = isCardio,
                focused = focused,
                onToggleFocus = { tag -> focused = if (focused == tag) null else tag }
            )

            val isDone: Boolean = if (isCardio) {
                exercise.cardio?.done == true
            } else {
                exercise.sets.getOrNull(setIdx)?.d == true
            }
            DoneCircle(
                done = isDone,
                onClick = {
                    if (isCardio) {
                        ActionSender.toggleDone(context, exercise.id, -1)
                    } else {
                        ActionSender.toggleDone(context, exercise.id, setIdx)
                    }
                }
            )

            ProgressDots(exercises = snapshot.exercises)
        }

        // Crown indicator arrow — pulses on whichever side the crown is
        // physically on (right for default, left for lefty/right-wrist).
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
                    .padding(horizontal = 2.dp),
                contentAlignment = if (isLefty) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Text(
                    text = if (isLefty) "‹" else "›",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SplitstakColors.Accent,
                    modifier = Modifier.alpha(pulseAlpha)
                )
            }
        }

        // PR overlay
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

/** Worn-on-right-wrist mode rotates the display 180°. */
private fun detectLefty(context: Context): Boolean = try {
    @Suppress("DEPRECATION")
    val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        .defaultDisplay.rotation
    rotation == Surface.ROTATION_180
} catch (_: Exception) {
    false
}

@Composable
private fun ExerciseBox(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: Boolean,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "ex-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = if (focused) 0.4f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ex-pulse-alpha"
    )
    val borderColor: Color = if (focused) {
        SplitstakColors.Accent.copy(alpha = pulseAlpha)
    } else {
        SplitstakColors.Border
    }

    val targetLine = buildString {
        if (exercise.target.isNotEmpty()) append(exercise.target)
        if (exercise.isPr) {
            if (isNotEmpty()) append(" · ")
            append("PR")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .background(SplitstakColors.Surface)
            .border(1.5.dp, borderColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = exercise.name.uppercase(),
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SplitstakColors.Text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (targetLine.isNotEmpty()) {
            Text(
                text = targetLine,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = SplitstakColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (isCardio) "CARDIO" else "SET ${setIdx + 1}/${exercise.sets.size}",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = SplitstakColors.TextDim,
            maxLines = 1
        )
    }
}

@Composable
private fun BodyBoxes(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: String?,
    onToggleFocus: (String) -> Unit
) {
    val set = exercise.sets.getOrNull(setIdx) ?: SetEntry("", "", "", false)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            isCardio -> {
                val c = exercise.cardio
                ValueBox("MIN", c?.time ?: "", focused == "ctime") { onToggleFocus("ctime") }
                ValueBox("MI", c?.distance ?: "", focused == "cdist") { onToggleFocus("cdist") }
            }
            exercise.mode == "bodyweight" -> {
                ValueBox("RPS", set.r, focused == "reps") { onToggleFocus("reps") }
            }
            exercise.mode == "time" -> {
                ValueBox("SEC", set.t, focused == "hold") { onToggleFocus("hold") }
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
                .height(32.dp)
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
        modifier = Modifier.size(32.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (done) SplitstakColors.Accent else SplitstakColors.Surface,
            contentColor = if (done) SplitstakColors.Bg else SplitstakColors.TextDim
        )
    ) {
        Text(
            text = if (done) "✓" else "○",
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
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
                    .background(
                        if (ex.allComplete) SplitstakColors.Accent
                        else SplitstakColors.Border,
                        shape = CircleShape
                    )
            )
        }
    }
}
