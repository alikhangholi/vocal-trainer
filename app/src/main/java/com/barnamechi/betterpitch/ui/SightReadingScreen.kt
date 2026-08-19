package com.barnamechi.betterpitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barnamechi.betterpitch.music.Staff
import kotlin.math.ceil

private const val ROUND_NOTES = 100
private val SPACING_CHOICES = listOf(1, 2, 4)

private enum class Phase { Idle, Running, Paused, Finished }

/**
 * One round. Deliberately **not** snapshot state: it is read and written every frame, and the
 * canvas already invalidates draw every frame from `nowBeat`, so making these observable would buy
 * nothing and cost a recomposition per note.
 *
 * Everything here is in beats, never pixels. That is why changing the BPM mid-round is a no-op for
 * scoring - only px/second changes. Don't "fix" it by caching positions.
 */
private class Game(val notes: IntArray, val beatsPerNote: Int) {
    val results = ByteArray(notes.size)
    val judgedAt = FloatArray(notes.size)

    /** Earliest un-judged note: the one you are answering. */
    var target = 0

    /** Absolute metronome beat at which note 0 reaches the judgement line. */
    var origin = 0.0

    /**
     * How long a note stays answerable after it passes the line. Half the gap is exactly where the
     * next note becomes the one nearest the line; the 1-beat cap keeps whole-note mode sane.
     */
    val graceBeats: Float = minOf(0.5f * beatsPerNote, 1f)

    val leadBeats: Float = NOTES_VISIBLE * beatsPerNote

    fun beatOf(i: Int): Float = i * beatsPerNote.toFloat()
    fun deadlineOf(i: Int): Float = beatOf(i) + graceBeats

    /** Places note [fromIndex] a full lead-in away from the line, on a bar accent. */
    fun anchorAt(clockNow: Double, fromIndex: Int, beatsPerBar: Int) {
        val earliest = clockNow + leadBeats + 1.0 - beatOf(fromIndex)
        origin = ceil(earliest / beatsPerBar) * beatsPerBar
    }
}

@Composable
fun SightReadingScreen(
    solfege: Boolean,
    onSolfegeChange: (Boolean) -> Unit,
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    metronomeOn: Boolean,
    beatsPerBar: Int,
    onStartMetronome: () -> Unit,
    onStopMetronome: () -> Unit,
    beatNow: () -> Double,
    onBack: () -> Unit,
) {
    var spacing by remember { mutableIntStateOf(1) }
    var game by remember { mutableStateOf(Game(Staff.randomRound(ROUND_NOTES), 1)) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var correct by remember { mutableIntStateOf(0) }
    var errors by remember { mutableIntStateOf(0) }

    // Read ONLY inside StaffCanvas's draw lambda. A read in a composable body here would
    // recompose this whole page 60 times a second. Starts off-staff, so Idle shows empty lines.
    val nowBeat = remember { mutableFloatStateOf(-(NOTES_VISIBLE + 1f)) }

    // Don't clobber a click the user deliberately started on the home screen.
    val wasClickingOnEntry = remember { metronomeOn }
    val stopIfOurs by rememberUpdatedState(
        newValue = { if (!wasClickingOnEntry) onStopMetronome() }
    )

    fun newRound(beatsPerNote: Int) {
        game = Game(Staff.randomRound(ROUND_NOTES), beatsPerNote)
        correct = 0
        errors = 0
        phase = Phase.Idle
        nowBeat.floatValue = -(game.leadBeats + 1f) // empty staff until the round starts
    }

    fun startFrom(index: Int) {
        onStartMetronome()
        game.anchorAt(beatNow(), index, beatsPerBar)
        nowBeat.floatValue = (beatNow() - game.origin).toFloat()
        phase = Phase.Running
    }

    fun leave() {
        stopIfOurs()
        onBack()
    }

    // The metronome is stopped from MainActivity.onStop() while a round runs, because the frame
    // loop freezes in the background and the audio would otherwise run on without us.
    LaunchedEffect(metronomeOn) {
        if (!metronomeOn && phase == Phase.Running) phase = Phase.Paused
    }

    // The frame loop. Cancels itself when the phase changes or the screen leaves; withFrameNanos
    // also stops resuming while the window is invisible.
    LaunchedEffect(phase, game) {
        if (phase != Phase.Running) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val b = (beatNow() - game.origin).toFloat()
            nowBeat.floatValue = b
            var missed = 0
            while (game.target < game.notes.size && b > game.deadlineOf(game.target)) {
                game.results[game.target] = R_MISSED
                game.judgedAt[game.target] = b
                game.target++
                missed++
            }
            if (missed > 0) errors += missed
            if (game.target >= game.notes.size) {
                phase = Phase.Finished
                stopIfOurs()
                break
            }
        }
    }

    DisposableEffect(Unit) { onDispose { stopIfOurs() } }
    BackHandler { leave() }

    fun answer(pitchClass: Int) {
        if (phase != Phase.Running || game.target >= game.notes.size) return
        val b = nowBeat.floatValue
        // You can only answer what you can read: after a run of wrong presses the target may not
        // have entered the staff yet.
        if (game.beatOf(game.target) - b > game.leadBeats) return
        val ok = Staff.pitchClass(game.notes[game.target]) == pitchClass
        game.results[game.target] = if (ok) R_CORRECT else R_WRONG
        game.judgedAt[game.target] = b
        game.target++
        if (ok) correct++ else errors++
        if (game.target >= game.notes.size) {
            phase = Phase.Finished
            stopIfOurs()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(ChipShape)
                    .background(Well)
                    .border(1.dp, Line, ChipShape)
                    .clickable { leave() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("‹ Back", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            ToggleChipCompact("Solfège", solfege, onSolfegeChange)
        }

        Spacer(Modifier.height(14.dp))
        ChipRow(BPM_CHOICES, bpm, label = { "$it" }, onSelect = onBpmChange)
        Spacer(Modifier.height(8.dp))
        ChipRow(
            SPACING_CHOICES, spacing,
            enabled = phase != Phase.Running && phase != Phase.Paused,
            label = { if (it == 1) "1 beat" else "$it beats" },
            onSelect = { if (it != spacing) { spacing = it; newRound(it) } }
        )

        Spacer(Modifier.height(14.dp))
        // The staff absorbs the leftover height, so short screens shrink the staff rather than
        // pushing the answer pad off the bottom - there is no scroll container to rescue us.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 140.dp)
                .clip(CardShape)
                .background(Surface)
                .border(1.dp, Line, CardShape)
        ) {
            StaffCanvas(
                notes = game.notes,
                results = game.results,
                judgedAt = game.judgedAt,
                beatsPerNote = game.beatsPerNote,
                graceBeats = game.graceBeats,
                nowBeat = nowBeat,
                running = phase == Phase.Running,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Correct", "$correct", Mint, Modifier.weight(1f))
            Stat("Errors", "$errors", Coral, Modifier.weight(1f))
            if (phase == Phase.Finished) {
                Stat("Accuracy", "${correct * 100 / ROUND_NOTES}%", TextC, Modifier.weight(1f))
            } else {
                Stat("Left", "${ROUND_NOTES - correct - errors}", TextC, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            when (phase) {
                Phase.Idle -> "$ROUND_NOTES notes. Name each one before it passes the amber line."
                Phase.Running -> "Answer the note nearest the line."
                Phase.Paused -> "Paused. Resume picks up where you left off."
                Phase.Finished -> "Round complete."
            },
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        AnswerPad(solfege, phase == Phase.Running) { pc -> answer(pc) }

        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = when (phase) {
                Phase.Idle -> "Start round"
                Phase.Running -> "Stop"
                Phase.Paused -> "Resume"
                Phase.Finished -> "Play again"
            },
            danger = phase == Phase.Running
        ) {
            when (phase) {
                Phase.Idle -> startFrom(0)
                Phase.Running -> { phase = Phase.Idle; stopIfOurs(); newRound(spacing) }
                Phase.Paused -> startFrom(game.target)
                Phase.Finished -> { newRound(spacing); startFrom(0) }
            }
        }
    }
}

/** Seven pitch classes, matched octave-agnostically: C4 and C5 are both answered with "C". */
@Composable
private fun AnswerPad(solfege: Boolean, enabled: Boolean, onAnswer: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Staff.ANSWER_CLASSES.forEach { pc ->
            AnswerKey(pcLabel(60 + pc, solfege), enabled, Modifier.weight(1f)) { onAnswer(pc) }
        }
    }
}

@Composable
private fun AnswerKey(text: String, enabled: Boolean, modifier: Modifier, onPress: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val bg = if (pressed) Honey else Well
    Box(
        modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else dim(bg))
            .border(1.dp, if (pressed) Honey else Line, RoundedCornerShape(12.dp))
            // Touch-down, like the piano keys: waiting for touch-up would feel laggy in a
            // timed game.
            .pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (enabled) {
                        pressed = true
                        onPress()
                    }
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (pressed) OnHoney else if (enabled) TextC else dim(TextC),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1
        )
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(ChipShape)
            .background(Well)
            .border(1.dp, Line, ChipShape)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label.uppercase(), color = Muted, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ToggleChipCompact(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .clip(ChipShape)
            .background(if (checked) Honey else Well)
            .border(1.dp, if (checked) Honey else Line, ChipShape)
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (checked) OnHoney else Muted, fontSize = 13.sp,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PrimaryButton(text: String, danger: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) Coral else Honey)
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (danger) OnCoral else OnHoney, fontSize = 15.sp,
            fontWeight = FontWeight.Bold)
    }
}
