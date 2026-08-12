package com.barnamechi.vocaltrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Small polyphonic synth with a piano-like timbre (additive: fundamental + 6 harmonics at exact
 * integer multiples, so tuning stays perfect equal temperament).
 *
 * strike(freq) = struck note: short attack, then natural exponential decay (sustain OFF).
 * damp()       = gentle damper release on struck notes (finger lifted).
 * noteOn(freq) = held, non-decaying note (sustain ON latch); noteOff() releases it.
 */
class ToneEngine {
    private companion object {
        const val REQUESTED_RATE = 44100
        const val VOICES = 4
        const val PARTIALS = 7
        val HARMONIC_AMP = doubleArrayOf(1.0, 0.5, 0.33, 0.22, 0.15, 0.10, 0.07)
        val HARMONIC_SUM = HARMONIC_AMP.sum()
        const val PEAK = 0.25       // per-voice peak, same loudness as the old sine
        const val ATTACK_SEC = 0.005
        const val RELEASE_SEC = 0.12
        const val MINUS_60_DB = 6.908 // ln(1000)
        const val DEAD = 1e-4
        const val SELF_SOUND_TAIL_MS = 200L // mic stays gated this long after the last voice dies
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** One struck/held note. Mutated on the audio thread; started/released from the UI thread. */
    private class Voice {
        @Volatile var active = false
        @Volatile var held = false          // true = sustain latch, no decay
        @Volatile var releasing = false
        var freq = 440.0
        val phase = DoubleArray(PARTIALS)
        var env = 0.0
        var attackStep = 0.0                // >0 while attacking
        var decayCoef = 1.0
        var releaseCoef = 1.0
    }

    private val voices = Array(VOICES) { Voice() }
    private val lock = Any()

    /** Wall clock of the last buffer that contained sound; used to gate the mic. */
    @Volatile private var lastSoundingMs = 0L

    /**
     * True while the app itself is making sound (plus a short tail). The pitch detector uses this
     * to ignore its own piano coming back through the speaker.
     */
    fun isSounding(): Boolean =
        voices.any { it.active } ||
            System.currentTimeMillis() - lastSoundingMs < SELF_SOUND_TAIL_MS

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            REQUESTED_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(REQUESTED_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf) // smallest safe buffer: keys must sound on touch-down
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
        track = t
        t.play()
        running = true

        // The track may not have opened at the requested rate; pitch must follow the ACTUAL rate.
        val sr = t.sampleRate.takeIf { it > 0 } ?: REQUESTED_RATE

        thread = Thread {
            val buf = ShortArray(256)
            val nyquist = sr / 2.0
            while (running) {
                if (voices.any { it.active }) lastSoundingMs = System.currentTimeMillis()
                for (i in buf.indices) {
                    var mix = 0.0
                    for (v in voices) {
                        if (!v.active) continue
                        // envelope
                        if (v.attackStep > 0.0) {
                            v.env += v.attackStep
                            if (v.env >= 1.0) { v.env = 1.0; v.attackStep = 0.0 }
                        } else if (v.releasing) {
                            v.env *= v.releaseCoef
                        } else if (!v.held) {
                            v.env *= v.decayCoef
                        }
                        if (v.env < DEAD && v.attackStep == 0.0) { v.active = false; v.env = 0.0; continue }
                        // additive timbre: exact integer harmonics
                        var s = 0.0
                        for (h in 0 until PARTIALS) {
                            val f = v.freq * (h + 1)
                            if (f >= nyquist) break
                            s += sin(v.phase[h]) * HARMONIC_AMP[h]
                            v.phase[h] += 2 * PI * f / sr
                            if (v.phase[h] > 2 * PI) v.phase[h] -= 2 * PI
                        }
                        mix += s / HARMONIC_SUM * v.env * PEAK
                    }
                    if (mix > 1.0) mix = 1.0 else if (mix < -1.0) mix = -1.0
                    buf[i] = (mix * Short.MAX_VALUE).toInt().toShort()
                }
                t.write(buf, 0, buf.size)
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Struck note: attack then natural decay. Higher notes decay a little faster. */
    fun strike(freq: Double) = allocate(freq, held = false)

    /** Held note that does not decay until [noteOff]. */
    fun noteOn(freq: Double) = allocate(freq, held = true)

    /** Damper on struck notes. */
    fun damp() = release(heldVoices = false)

    /** Release held (sustain) notes. */
    fun noteOff() = release(heldVoices = true)

    private fun allocate(freq: Double, held: Boolean) {
        val sr = (track?.sampleRate?.takeIf { it > 0 } ?: REQUESTED_RATE).toDouble()
        // -60 dB in decaySec; a high note rings shorter than a low one, like a piano.
        val decaySec = (2.5 * (440.0 / freq).pow(0.35)).coerceIn(0.4, 4.0)
        synchronized(lock) {
            val v = voices.firstOrNull { !it.active } ?: voices.minByOrNull { it.env } ?: voices[0]
            v.active = false            // pause the render loop's use of it while we re-arm
            v.freq = freq
            for (h in v.phase.indices) v.phase[h] = 0.0
            v.env = 0.0
            v.attackStep = 1.0 / (ATTACK_SEC * sr)
            v.decayCoef = exp(-MINUS_60_DB / (decaySec * sr))
            v.releaseCoef = exp(-MINUS_60_DB / (RELEASE_SEC * sr))
            v.held = held
            v.releasing = false
            v.active = true
        }
        lastSoundingMs = System.currentTimeMillis()
    }

    private fun release(heldVoices: Boolean) {
        synchronized(lock) {
            for (v in voices) {
                if (v.active && v.held == heldVoices) {
                    v.attackStep = 0.0
                    v.releasing = true
                }
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        synchronized(lock) { for (v in voices) { v.active = false; v.env = 0.0 } }
    }
}
