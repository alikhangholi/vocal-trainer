package com.barnamechi.betterpitch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.barnamechi.betterpitch.audio.Metronome
import com.barnamechi.betterpitch.audio.PitchEngine
import com.barnamechi.betterpitch.audio.ToneEngine
import com.barnamechi.betterpitch.data.RhythmProgress
import com.barnamechi.betterpitch.music.Notes
import com.barnamechi.betterpitch.music.Rhythm
import com.barnamechi.betterpitch.ui.BetterPitchScreen
import com.barnamechi.betterpitch.ui.RhythmGameScreen
import com.barnamechi.betterpitch.ui.Route
import com.barnamechi.betterpitch.ui.SightReadingScreen

class MainActivity : ComponentActivity() {

    private lateinit var tone: ToneEngine
    private val metronome = Metronome()
    private var pitch: PitchEngine? = null
    private lateinit var rhythmProgress: RhythmProgress

    private val detectedMidi = mutableStateOf<Int?>(null)
    private val cents = mutableStateOf(0)
    private val listening = mutableStateOf(false)
    private val loMidi = mutableStateOf<Int?>(null)
    private val hiMidi = mutableStateOf<Int?>(null)
    private val metronomeOn = mutableStateOf(false)
    private val bpm = mutableStateOf(60)
    private val solfege = mutableStateOf(false)
    private val route = mutableStateOf(Route.Home)
    private val rhythmUnlockedThrough = mutableStateOf(1)
    private val rhythmBestScores = mutableStateOf<Map<Int, Int>>(emptyMap())

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tone = ToneEngine().also { it.start() }
        rhythmProgress = RhythmProgress(this)
        rhythmUnlockedThrough.value = rhythmProgress.unlockedThrough()
        rhythmBestScores.value = Rhythm.LEVELS.associate { it.id to rhythmProgress.bestAccuracy(it.id) }

        setContent {
            when (route.value) {
                Route.Home -> BetterPitchScreen(
                    detectedMidi = detectedMidi.value,
                    cents = cents.value,
                    listening = listening.value,
                    loMidi = loMidi.value,
                    hiMidi = hiMidi.value,
                    onKeyDown = { m, sustain ->
                        val f = Notes.midiToFreq(m)
                        if (sustain) tone.noteOn(f) else tone.strike(f)
                    },
                    onKeyUp = { sustain -> if (sustain) tone.noteOff() else tone.damp() },
                    onToggleListen = { if (listening.value) stopListening() else requestMic() },
                    metronomeOn = metronomeOn.value,
                    bpm = bpm.value,
                    onToggleMetronome = { toggleMetronome() },
                    onBpmChange = { v -> setBpm(v) },
                    solfege = solfege.value,
                    onSolfegeChange = { solfege.value = it },
                    onOpenSightReading = { route.value = Route.SightReading },
                    onOpenRhythmGame = { route.value = Route.RhythmGame },
                )

                Route.SightReading -> SightReadingScreen(
                    solfege = solfege.value,
                    onSolfegeChange = { solfege.value = it },
                    bpm = bpm.value,
                    onBpmChange = { v -> setBpm(v) },
                    metronomeOn = metronomeOn.value,
                    beatsPerBar = metronome.beatsPerBar,
                    onStartMetronome = { if (!metronomeOn.value) toggleMetronome() },
                    onStopMetronome = { if (metronomeOn.value) toggleMetronome() },
                    beatNow = { metronome.audibleBeat() },
                    playNote = { m -> tone.strike(Notes.midiToFreq(m)) },
                    onBack = { route.value = Route.Home },
                )

                Route.RhythmGame -> RhythmGameScreen(
                    bpm = bpm.value,
                    onBpmChange = { v -> setBpm(v) },
                    metronomeOn = metronomeOn.value,
                    beatsPerBar = metronome.beatsPerBar,
                    onStartMetronome = { if (!metronomeOn.value) toggleMetronome() },
                    onStopMetronome = { if (metronomeOn.value) toggleMetronome() },
                    beatNow = { metronome.audibleBeat() },
                    onTapSound = { tone.clickHit() },
                    unlockedThrough = rhythmUnlockedThrough.value,
                    bestScores = rhythmBestScores.value,
                    onLevelResult = { levelId, accuracyPercent, passed ->
                        rhythmProgress.recordResult(levelId, accuracyPercent, passed)
                        rhythmUnlockedThrough.value = rhythmProgress.unlockedThrough()
                        rhythmBestScores.value = rhythmBestScores.value +
                            (levelId to maxOf(rhythmBestScores.value[levelId] ?: 0, accuracyPercent))
                    },
                    onBack = { route.value = Route.Home },
                )
            }
        }
    }

    private fun toggleMetronome() {
        if (metronomeOn.value) { metronome.stop(); metronomeOn.value = false }
        else { metronome.start(bpm.value); metronomeOn.value = true }
    }

    private fun setBpm(v: Int) {
        bpm.value = v
        metronome.setBpm(v)
    }

    private fun requestMic() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        loMidi.value = null
        hiMidi.value = null
        pitch = PitchEngine { f ->
            // Never let the app's own piano count as "your voice". (The metronome click is too
            // short to pass the periodicity gate, so it needs no gating and you can sing along.)
            if (tone.isSounding()) return@PitchEngine
            runOnUiThread {
                if (f > 60f && f < 1200f) {
                    val m = Math.round(Notes.freqToMidiFloat(f.toDouble())).toInt()
                    detectedMidi.value = m
                    cents.value = Notes.centsOff(f.toDouble(), m)
                    if (loMidi.value == null || m < loMidi.value!!) loMidi.value = m
                    if (hiMidi.value == null || m > hiMidi.value!!) hiMidi.value = m
                } else {
                    detectedMidi.value = null
                }
            }
        }
        listening.value = pitch?.start() == true
        if (!listening.value) pitch = null
    }

    private fun stopListening() {
        pitch?.stop(); pitch = null
        listening.value = false
        detectedMidi.value = null
    }

    override fun onStop() {
        super.onStop()
        // Both games' frame loops freeze when the window goes away, but the click track wouldn't -
        // coming back would score everything that elapsed while backgrounded as missed. Stopping
        // the metronome pauses the round instead. Home keeps its click running, as it always has.
        val pausesOnStop = route.value == Route.SightReading || route.value == Route.RhythmGame
        if (pausesOnStop && metronomeOn.value) {
            metronome.stop()
            metronomeOn.value = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pitch?.stop()
        tone.stop()
        metronome.stop()
    }
}
