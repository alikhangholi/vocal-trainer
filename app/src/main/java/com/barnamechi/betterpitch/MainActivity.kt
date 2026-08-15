package com.barnamechi.betterpitch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.barnamechi.betterpitch.audio.Metronome
import com.barnamechi.betterpitch.audio.PitchEngine
import com.barnamechi.betterpitch.audio.ToneEngine
import com.barnamechi.betterpitch.billing.BillingManager
import com.barnamechi.betterpitch.music.Notes
import com.barnamechi.betterpitch.ui.BetterPitchScreen

class MainActivity : ComponentActivity() {

    private lateinit var tone: ToneEngine
    private lateinit var billing: BillingManager
    private val metronome = Metronome()
    private var pitch: PitchEngine? = null

    private val detectedMidi = mutableStateOf<Int?>(null)
    private val cents = mutableStateOf(0)
    private val listening = mutableStateOf(false)
    private val loMidi = mutableStateOf<Int?>(null)
    private val hiMidi = mutableStateOf<Int?>(null)
    private val metronomeOn = mutableStateOf(false)
    private val bpm = mutableStateOf(60)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tone = ToneEngine().also { it.start() }
        billing = BillingManager(this).also { it.connect() }

        setContent {
            val isPremium by billing.isPremium.collectAsState()
            val billingStatus by billing.status.collectAsState()

            BetterPitchScreen(
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
                onToggleMetronome = {
                    if (metronomeOn.value) { metronome.stop(); metronomeOn.value = false }
                    else { metronome.start(bpm.value); metronomeOn.value = true }
                },
                onBpmChange = { v ->
                    bpm.value = v
                    metronome.setBpm(v)
                },
                isPremium = isPremium,
                billingStatus = billingStatus,
                onUnlock = { billing.subscribe() },
            )
        }
    }

    private fun requestMic() {
        // Gate at the source as well as in the UI: a free user never reaches the permission
        // dialog, let alone PitchEngine.
        if (!billing.isPremium.value) return
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

    override fun onStart() {
        super.onStart()
        // Re-check on every foreground: a subscription bought or cancelled outside the app
        // (in Bazaar itself) shows up here without a restart.
        if (billing.isReady()) billing.queryPurchasedSubscriptions()
    }

    override fun onDestroy() {
        super.onDestroy()
        pitch?.stop()
        tone.stop()
        metronome.stop()
        billing.disconnect()
    }
}
