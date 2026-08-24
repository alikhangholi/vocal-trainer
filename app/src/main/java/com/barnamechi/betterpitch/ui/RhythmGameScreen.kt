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
import com.barnamechi.betterpitch.music.Rhythm
import com.barnamechi.betterpitch.music.RhythmEvent
import com.barnamechi.betterpitch.music.RhythmLevel
import kotlin.math.abs
import kotlin.math.ceil

private const val ROUND_BARS = 8

private enum class RhythmPhase { Idle, Running, Paused, Finished }
private enum class ScreenSection { LevelSelect, Playing }

/**
 * One round. Plain class rather than snapshot state, same rationale as sight-reading's `Game`: it
 * is read/written every frame and the canvas already invalidates draw every frame from `nowBeat`.
 *
 * Unlike sight-reading's uniform `beatOf(i) = i * beatsPerNote`, event durations here are not
 * uniform, so onsets are precomputed once into [onsetBeat] rather than derived arithmetically.
 */
private class RhythmRound(val events: List<RhythmEvent>, val level: RhythmLevel) {
    val onsetBeat: FloatArray
    val results = ByteArray(events.size)
    val judgedAt = FloatArray(events.size)
    val tapCount: Int

    var target = 0
    var origin = 0.0
    val leadBeats: Float = RHYTHM_LEAD_BEATS

    init {
        val onsets = FloatArray(events.size)
        var acc = 0f
        for (i in events.indices) {
            onsets[i] = acc
            acc += events[i].beats
        }
        onsetBeat = onsets
        tapCount = events.count { !it.isRest }
    }

    fun deadlineOf(i: Int): Float = onsetBeat[i] + level.goodWindowBeats

    /** Places event [fromIndex] a full lead-in away from the line, on a bar accent. */
    fun anchorAt(clockNow: Double, fromIndex: Int, beatsPerBar: Int) {
        val earliest = clockNow + leadBeats + 1.0 - onsetBeat[fromIndex]
        origin = ceil(earliest / beatsPerBar) * beatsPerBar
    }
}

@Composable
fun RhythmGameScreen(
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    metronomeOn: Boolean,
    beatsPerBar: Int,
    onStartMetronome: () -> Unit,
    onStopMetronome: () -> Unit,
    beatNow: () -> Double,
    onTapSound: () -> Unit,
    unlockedThrough: Int,
    bestScores: Map<Int, Int>,
    onLevelResult: (levelId: Int, accuracyPercent: Int, passed: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var section by remember { mutableStateOf(ScreenSection.LevelSelect) }
    var selectedLevel by remember { mutableStateOf<RhythmLevel?>(null) }

    when (section) {
        ScreenSection.LevelSelect -> RhythmLevelSelect(
            unlockedThrough = unlockedThrough,
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
                beatsPerBar = beatsPerBar,
                onStartMetronome = onStartMetronome,
                onStopMetronome = onStopMetronome,
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
    unlockedThrough: Int,
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
        Text("Pick a level. Pass one to unlock the next.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        Rhythm.LEVELS.forEach { level ->
            val unlocked = level.id <= unlockedThrough
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(CardShape)
                    .background(Surface)
                    .border(1.dp, Line, CardShape)
                    .clickable(enabled = unlocked) { onSelect(level) }
                    .padding(16.dp)
            ) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${level.id}. ${level.title}",
                            color = if (unlocked) TextC else dim(TextC),
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        val best = bestScores[level.id] ?: 0
                        if (best > 0) {
                            Text("Best $best%", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        } else if (!unlocked) {
                            Text("Locked", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(level.description, color = if (unlocked) Muted else dim(Muted), fontSize = 12.sp)
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
    beatsPerBar: Int,
    onStartMetronome: () -> Unit,
    onStopMetronome: () -> Unit,
    beatNow: () -> Double,
    onTapSound: () -> Unit,
    onLevelResult: (levelId: Int, accuracyPercent: Int, passed: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // Only the tempos this level's curriculum calls for are offered, so the round is always played
    // at a speed the windows above were tuned for.
    val allowedBpm = remember(level.id) { BPM_CHOICES.filter { it in level.bpmRange }.ifEmpty { listOf(level.bpmRange.first) } }
    LaunchedEffect(level.id) {
        if (bpm !in allowedBpm) onBpmChange(allowedBpm.first())
    }

    var round by remember(level.id) { mutableStateOf(RhythmRound(Rhythm.randomRound(level, ROUND_BARS), level)) }
    var phase by remember(level.id) { mutableStateOf(RhythmPhase.Idle) }
    var perfectCount by remember(level.id) { mutableIntStateOf(0) }
    var goodCount by remember(level.id) { mutableIntStateOf(0) }
    var missCount by remember(level.id) { mutableIntStateOf(0) }
    var resultReported by remember(level.id) { mutableStateOf(false) }

    val nowBeat = remember(level.id) { mutableFloatStateOf(-(RHYTHM_LEAD_BEATS + 1f)) }

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
        nowBeat.floatValue = -(round.leadBeats + 1f)
    }

    fun startFrom(index: Int) {
        onStartMetronome()
        round.anchorAt(beatNow(), index, beatsPerBar)
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
        val accuracy = if (round.tapCount == 0) 100 else (perfectCount + goodCount) * 100 / round.tapCount
        val hits = perfectCount + goodCount
        val perfectRatio = if (hits == 0) 0f else perfectCount.toFloat() / hits
        val passed = accuracy >= level.passAccuracy * 100 && perfectRatio >= level.passPerfectRatio
        onLevelResult(level.id, accuracy, passed)
    }

    // The metronome is stopped from MainActivity.onStop() while a round runs, because the frame
    // loop freezes in the background and the audio would otherwise run on without us.
    LaunchedEffect(metronomeOn) {
        if (!metronomeOn && phase == RhythmPhase.Running) phase = RhythmPhase.Paused
    }

    // The frame loop: advances the beat clock, auto-marks a MISS once a tap-eligible event's
    // window has fully elapsed. Rests are skipped without judging - there is nothing to tap.
    LaunchedEffect(phase, round) {
        if (phase != RhythmPhase.Running) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val b = (beatNow() - round.origin).toFloat()
            nowBeat.floatValue = b
            while (round.target < round.events.size && b > round.deadlineOf(round.target)) {
                val ev = round.events[round.target]
                if (!ev.isRest) {
                    round.results[round.target] = RR_MISSED
                    round.judgedAt[round.target] = b
                    missCount++
                }
                round.target++
            }
            if (round.target >= round.events.size) {
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
        // Rests are never tapped - the frame loop advances past them on its own, but a fast tap
        // right as one starts can still find it as the current target.
        while (round.target < round.events.size && round.events[round.target].isRest) round.target++
        if (round.target >= round.events.size) return

        val idx = round.target
        val b = nowBeat.floatValue
        val onset = round.onsetBeat[idx]
        if (onset - b > round.leadBeats) return // too early, not yet visible
        val delta = abs(b - onset)
        val verdict = when {
            delta <= level.perfectWindowBeats -> RR_PERFECT
            delta <= level.goodWindowBeats -> RR_GOOD
            else -> return // stray tap outside any window - ignore, the frame loop will judge a miss
        }
        round.results[idx] = verdict
        round.judgedAt[idx] = b
        round.target++
        if (verdict == RR_PERFECT) perfectCount++ else goodCount++
        onTapSound()
        if (round.target >= round.events.size) {
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
                    RhythmPhase.Finished -> {
                        val acc = if (round.tapCount == 0) 100 else (perfectCount + goodCount) * 100 / round.tapCount
                        "Complete — $acc%"
                    }
                },
                color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "${level.id}. ${level.title}", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(14.dp))
        ChipRow(allowedBpm, bpm, label = { "$it" }, onSelect = onBpmChange)

        Spacer(Modifier.height(14.dp))
        // The track absorbs the leftover height, same as the sight-reading staff - no scroll
        // container to rescue a short screen.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 100.dp)
                .clip(CardShape)
                .background(Surface)
                .border(1.dp, Line, CardShape)
        ) {
            RhythmTrackCanvas(
                events = round.events,
                onsetBeat = round.onsetBeat,
                results = round.results,
                judgedAt = round.judgedAt,
                nowBeat = nowBeat,
                running = phase == RhythmPhase.Running,
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
                RhythmPhase.Idle -> startFrom(0)
                RhythmPhase.Running -> { phase = RhythmPhase.Idle; stopIfOurs(); newRound() }
                RhythmPhase.Paused -> startFrom(round.target)
                RhythmPhase.Finished -> { newRound(); startFrom(0) }
            }
        }
    }
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
