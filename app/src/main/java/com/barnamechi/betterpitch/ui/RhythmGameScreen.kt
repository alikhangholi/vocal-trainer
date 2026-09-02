package com.barnamechi.betterpitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barnamechi.betterpitch.music.Meter
import com.barnamechi.betterpitch.music.Rhythm
import com.barnamechi.betterpitch.music.RhythmEvent
import com.barnamechi.betterpitch.music.RhythmLayout
import com.barnamechi.betterpitch.music.RhythmLevel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val ROUND_BARS = 8

/** Bars of click before the first note. One full bar, so the round is counted in, not sprung on. */
private const val COUNT_IN_BARS = 1

private enum class RhythmPhase { Idle, Running, Paused, Finished }
private enum class ScreenSection { LevelSelect, Playing }

/**
 * One round. Plain class rather than snapshot state, same rationale as sight-reading's `Game`: it
 * is read/written every frame and the canvas already invalidates draw every frame from `nowBeat`.
 *
 * The engraving - onsets, beam groups, which events are actually tapped - lives in [layout] and is
 * computed once here; this class owns only what changes as the round is played.
 */
private class RhythmRound(events: List<RhythmEvent>, val level: RhythmLevel) {
    val layout = RhythmLayout(events, level.meter)
    val results = ByteArray(layout.size)
    val judgedAt = FloatArray(layout.size)

    /** Signed timing error per event, in beats; negative is early. Display only - it is recorded
     *  after the verdict and never changes one. */
    val deltaBeats = FloatArray(layout.size)

    var target = 0
    var origin = 0.0

    /** Beats of silent count-in before beat 0. Zero when resuming into the middle of a round. */
    var countInBeats = 0f
    val leadBeats: Float = RHYTHM_LEAD_BEATS

    // Running timing bias, as plain fields. Two more mutableIntStateOf counters would recompose
    // the page on every tap to deliver something that is only meaningful as an end-of-round mean.
    var deltaSum = 0f
    var deltaCount = 0

    val tapCount: Int get() = layout.tapCount

    fun deadlineOf(i: Int): Float = layout.deadlineOf(i, level.goodWindowBeats)

    /**
     * Places event [fromIndex] a full lead-in away from the line, on a bar accent, after
     * [countInBars] bars of count-in. The count-in is a whole number of bars, so `origin` stays a
     * multiple of `beatsPerBar` and the round still starts *with* the accented click.
     */
    fun anchorAt(clockNow: Double, fromIndex: Int, countInBars: Int) {
        val bpb = level.meter.beatsPerBar
        val earliest = clockNow + leadBeats + 1.0 - layout.onsetBeat[fromIndex]
        origin = ceil(earliest / bpb) * bpb + countInBars.toDouble() * bpb
        countInBeats = (countInBars * bpb).toFloat()
    }
}

@Composable
fun RhythmGameScreen(
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    metronomeOn: Boolean,
    onStartMetronome: () -> Unit,
    onStopMetronome: () -> Unit,
    onBeatsPerBarChange: (Int) -> Unit,
    beatNow: () -> Double,
    onTapSound: () -> Unit,
    bestScores: Map<Int, Int>,
    onLevelResult: (levelId: Int, accuracyPercent: Int) -> Unit,
    onBack: () -> Unit,
) {
    var section by remember { mutableStateOf(ScreenSection.LevelSelect) }
    var selectedLevel by remember { mutableStateOf<RhythmLevel?>(null) }

    when (section) {
        ScreenSection.LevelSelect -> RhythmLevelSelect(
            bestScores = bestScores,
            onSelect = { level -> selectedLevel = level; section = ScreenSection.Playing },
            onBack = onBack,
        )
        ScreenSection.Playing -> selectedLevel?.let { level ->
            RhythmPlayScreen(
                level = level,
                bpm = bpm,
                onBpmChange = onBpmChange,
                metronomeOn = metronomeOn,
                onStartMetronome = onStartMetronome,
                onStopMetronome = onStopMetronome,
                onBeatsPerBarChange = onBeatsPerBarChange,
                beatNow = beatNow,
                onTapSound = onTapSound,
                onLevelResult = onLevelResult,
                onBack = { section = ScreenSection.LevelSelect },
            )
        }
    }
}

@Composable
private fun RhythmLevelSelect(
    bestScores: Map<Int, Int>,
    onSelect: (RhythmLevel) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Box(
            Modifier
                .clip(ChipShape)
                .background(Well)
                .border(1.dp, Line, ChipShape)
                .clickable { onBack() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("‹ Back", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(14.dp))
        Text("Rhythm practice", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Every level is open. Work down the list, or jump to whatever you want to drill.",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))

        Rhythm.LEVELS.forEach { level ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(CardShape)
                    .background(Surface)
                    .border(1.dp, Line, CardShape)
                    .clickable { onSelect(level) }
                    .padding(16.dp)
            ) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${level.id}. ${level.title}",
                            color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            level.meter.label,
                            color = Honey, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                        val best = bestScores[level.id] ?: 0
                        if (best > 0) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Best $best%", color = Mint, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(level.description, color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RhythmPlayScreen(
    level: RhythmLevel,
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    metronomeOn: Boolean,
    onStartMetronome: () -> Unit,
    onStopMetronome: () -> Unit,
    onBeatsPerBarChange: (Int) -> Unit,
    beatNow: () -> Double,
    onTapSound: () -> Unit,
    onLevelResult: (levelId: Int, accuracyPercent: Int) -> Unit,
    onBack: () -> Unit,
) {
    // Only the tempos this level's curriculum calls for are offered, so the round is always played
    // at a speed the windows above were tuned for.
    val allowedBpm = remember(level.id) {
        BPM_CHOICES.filter { it in level.bpmRange }.ifEmpty { listOf(level.bpmRange.first) }
    }
    LaunchedEffect(level.id) {
        if (bpm !in allowedBpm) onBpmChange(allowedBpm.first())
    }

    // The accent has to match this level's bar before any round is anchored on it, and it must go
    // back to 4/4 on every exit path - so it hangs off the same lifecycle as the round itself.
    val setBeatsPerBar by rememberUpdatedState(onBeatsPerBarChange)
    DisposableEffect(level.id) {
        setBeatsPerBar(level.meter.beatsPerBar)
        onDispose { setBeatsPerBar(Meter.FOUR_FOUR.beatsPerBar) }
    }

    var round by remember(level.id) {
        mutableStateOf(RhythmRound(Rhythm.randomRound(level, ROUND_BARS), level))
    }
    var phase by remember(level.id) { mutableStateOf(RhythmPhase.Idle) }
    var perfectCount by remember(level.id) { mutableIntStateOf(0) }
    var goodCount by remember(level.id) { mutableIntStateOf(0) }
    var missCount by remember(level.id) { mutableIntStateOf(0) }
    var resultReported by remember(level.id) { mutableStateOf(false) }

    val idlePreviewBeat = -(RHYTHM_LEAD_BEATS + 1f + COUNT_IN_BARS * level.meter.beatsPerBar)
    val nowBeat = remember(level.id) { mutableFloatStateOf(idlePreviewBeat) }

    // Don't clobber a click the user deliberately started on the home screen.
    val wasClickingOnEntry = remember { metronomeOn }
    val stopIfOurs by rememberUpdatedState(newValue = { if (!wasClickingOnEntry) onStopMetronome() })

    fun newRound() {
        round = RhythmRound(Rhythm.randomRound(level, ROUND_BARS), level)
        perfectCount = 0
        goodCount = 0
        missCount = 0
        resultReported = false
        phase = RhythmPhase.Idle
        nowBeat.floatValue = idlePreviewBeat
    }

    fun startFrom(index: Int, countInBars: Int) {
        onStartMetronome()
        round.anchorAt(beatNow(), index, countInBars)
        nowBeat.floatValue = (beatNow() - round.origin).toFloat()
        phase = RhythmPhase.Running
    }

    fun leave() {
        stopIfOurs()
        onBack()
    }

    fun reportIfDone() {
        if (resultReported) return
        val judged = perfectCount + goodCount + missCount
        if (judged < round.tapCount) return
        resultReported = true
        val accuracy =
            if (round.tapCount == 0) 100 else (perfectCount + goodCount) * 100 / round.tapCount
        onLevelResult(level.id, accuracy)
    }

    // The metronome is stopped from MainActivity.onStop() while a round runs, because the frame
    // loop freezes in the background and the audio would otherwise run on without us.
    LaunchedEffect(metronomeOn) {
        if (!metronomeOn && phase == RhythmPhase.Running) phase = RhythmPhase.Paused
    }

    // The frame loop: advances the beat clock, auto-marks a MISS once a tap-eligible event's
    // window has fully elapsed. Rests and the far side of a tie are skipped without judging -
    // there is nothing to tap on either.
    LaunchedEffect(phase, round) {
        if (phase != RhythmPhase.Running) return@LaunchedEffect
        val layout = round.layout
        while (true) {
            withFrameNanos { }
            val b = (beatNow() - round.origin).toFloat()
            nowBeat.floatValue = b
            while (round.target < layout.size && b > round.deadlineOf(round.target)) {
                if (layout.tapEligible[round.target]) {
                    round.results[round.target] = RR_MISSED
                    round.judgedAt[round.target] = b
                    missCount++
                }
                round.target++
            }
            if (round.target >= layout.size) {
                phase = RhythmPhase.Finished
                stopIfOurs()
                reportIfDone()
                break
            }
        }
    }

    DisposableEffect(Unit) { onDispose { stopIfOurs() } }
    BackHandler { leave() }

    fun tap() {
        if (phase != RhythmPhase.Running) return
        val b = nowBeat.floatValue
        // Nothing to hit during the count-in; taps there are counting, not playing.
        if (b < 0f) return

        val layout = round.layout
        // The frame loop advances past rests and tied continuations on its own, but a fast tap
        // right as one starts can still find it as the current target.
        while (round.target < layout.size && !layout.tapEligible[round.target]) round.target++
        if (round.target >= layout.size) return

        val idx = round.target
        val onset = layout.onsetBeat[idx]
        if (onset - b > round.leadBeats) return // too early, not yet visible
        val signed = b - onset
        val delta = abs(signed)
        val verdict = when {
            delta <= level.perfectWindowBeats -> RR_PERFECT
            delta <= level.goodWindowBeats -> RR_GOOD
            else -> return // stray tap outside any window - ignore, the frame loop will judge a miss
        }
        round.results[idx] = verdict
        round.judgedAt[idx] = b
        round.deltaBeats[idx] = signed
        round.deltaSum += signed
        round.deltaCount++
        round.target++
        if (verdict == RR_PERFECT) perfectCount++ else goodCount++
        onTapSound()
        if (round.target >= layout.size) {
            phase = RhythmPhase.Finished
            stopIfOurs()
        }
    }

    LaunchedEffect(phase, round) {
        if (phase == RhythmPhase.Finished) reportIfDone()
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
            Spacer(Modifier.width(10.dp))
            Text(
                when (phase) {
                    RhythmPhase.Idle -> "${round.tapCount} taps"
                    RhythmPhase.Running, RhythmPhase.Paused ->
                        "$perfectCount perfect · $goodCount good · $missCount miss"
                    // deltaSum/deltaCount are plain fields, but the round is over by the time this
                    // composes and the phase change is what recomposed it, so they are final.
                    RhythmPhase.Finished -> {
                        val acc = if (round.tapCount == 0) 100
                        else (perfectCount + goodCount) * 100 / round.tapCount
                        val hits = perfectCount + goodCount
                        val perfectRatio =
                            if (hits == 0) 0f else perfectCount.toFloat() / hits
                        // The pass thresholds no longer gate anything - with every level open they
                        // are just the verdict on the round you played.
                        val passed = acc >= level.passAccuracy * 100 &&
                            perfectRatio >= level.passPerfectRatio
                        (if (passed) "Passed — $acc%" else "Complete — $acc%") +
                            biasLabel(round.deltaSum, round.deltaCount, bpm)
                    }
                },
                color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${level.id}. ${level.title}", color = TextC, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(
                // In compound time the click is the dotted quarter, so the tempo numbers below
                // mean something different - say so rather than leave it to be discovered.
                if (level.meter.isCompound) "${level.meter.label} · click = dotted quarter"
                else level.meter.label,
                color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(14.dp))
        ChipRow(allowedBpm, bpm, label = { "$it" }, onSelect = onBpmChange)

        Spacer(Modifier.height(14.dp))
        // The staff absorbs the leftover height, same as the sight-reading one - no scroll
        // container to rescue a short screen.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 120.dp)
                .clip(CardShape)
                .background(Surface)
                .border(1.dp, Line, CardShape)
        ) {
            RhythmTrackCanvas(
                layout = round.layout,
                results = round.results,
                judgedAt = round.judgedAt,
                deltaBeats = round.deltaBeats,
                goodWindowBeats = level.goodWindowBeats,
                nowBeat = nowBeat,
                running = phase == RhythmPhase.Running,
                countInBeats = round.countInBeats,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        TapPad(phase == RhythmPhase.Running) { tap() }

        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = when (phase) {
                RhythmPhase.Idle -> "Start round"
                RhythmPhase.Running -> "Stop"
                RhythmPhase.Paused -> "Resume"
                RhythmPhase.Finished -> "Play again"
            },
            danger = phase == RhythmPhase.Running
        ) {
            when (phase) {
                RhythmPhase.Idle -> startFrom(0, COUNT_IN_BARS)
                RhythmPhase.Running -> { phase = RhythmPhase.Idle; stopIfOurs(); newRound() }
                // Resuming picks up mid-round, so there is nothing to count in. The coerce covers
                // the narrow case where the frame loop reached the end just as the metronome
                // stopped, leaving target one past the last event.
                RhythmPhase.Paused ->
                    startFrom(round.target.coerceIn(0, round.layout.size - 1), 0)
                RhythmPhase.Finished -> { newRound(); startFrom(0, COUNT_IN_BARS) }
            }
        }
    }
}

/**
 * The round's mean timing error, in milliseconds at the tempo it was played.
 *
 * Milliseconds are banned from game logic for good reason - windows are beat-fractions so they
 * scale with tempo by themselves. This is the one exception, and it is display only: "0.04 beats
 * early" means nothing to a player, "31 ms early" is the thing they can actually feel.
 */
private fun biasLabel(deltaSum: Float, deltaCount: Int, bpm: Int): String {
    if (deltaCount == 0) return ""
    val ms = ((deltaSum / deltaCount) * 60_000f / bpm).roundToInt()
    if (ms == 0) return " · dead on"
    return if (ms < 0) " · avg ${-ms} ms early" else " · avg $ms ms late"
}

/** Sole input: rhythm has no pitch to choose, only "now". */
@Composable
private fun TapPad(enabled: Boolean, onTap: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) Honey else if (enabled) Well else dim(Well))
            .border(1.dp, if (pressed) Honey else Line, RoundedCornerShape(16.dp))
            // Touch-down, like the sight-reading answer keys: waiting for touch-up would feel
            // laggy in a timed game.
            .pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (enabled) {
                        pressed = true
                        onTap()
                    }
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "TAP",
            color = if (pressed) OnHoney else if (enabled) TextC else dim(TextC),
            fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp
        )
    }
}
