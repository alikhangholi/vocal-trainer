package com.barnamechi.betterpitch.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.barnamechi.betterpitch.music.Notes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads the mic and estimates fundamental frequency via normalized autocorrelation
 * (good for a sustained sung vowel). Emits Hz through [onPitch]; -1f means "no clear pitch".
 * Callback fires on the audio thread — marshal to main before touching UI state.
 *
 * Readings are gated on autocorrelation confidence and smoothed over a few frames so ambient
 * noise can't make the displayed note jump around.
 */
class PitchEngine(private val onPitch: (Float) -> Unit) {
    private companion object {
        // Tuned for a *sung* vowel: vibrato, breath and room noise put a real voice around
        // 0.7-0.85 confidence. Tighter than this and only a synth tone gets through.
        const val RMS_FLOOR = 0.012f         // ignore quiet frames
        const val MIN_CONFIDENCE = 0.70f     // normalized peak c[maxpos]/c[0]
        const val STABLE_FRAMES = 2          // same semitone this many frames before we report it
        const val HOLD_FRAMES = 4            // keep last stable note over ~0.19 s of dropout
    }

    private val sampleRate = 44100
    private var thread: Thread? = null
    @Volatile private var running = false
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    // stabilizer state (audio thread only)
    private var candidateMidi = Int.MIN_VALUE
    private var candidateCount = 0
    private var stableHz = -1f
    private var missCount = 0

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 4096)
        // VOICE_COMMUNICATION first: it is the source the platform wires echo cancellation to, so
        // the app's own piano coming out of the speaker gets cancelled instead of detected.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        var rec: AudioRecord? = null
        for (src in sources) {
            val r = runCatching {
                AudioRecord(
                    src, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
            }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r.release()
        }
        val recorder = rec ?: return false
        // Echo cancellation / noise suppression on this capture session, where the device offers it.
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }
        }
        recorder.startRecording()
        running = true
        candidateMidi = Int.MIN_VALUE
        candidateCount = 0
        stableHz = -1f
        missCount = 0

        thread = Thread {
            val shorts = ShortArray(2048)
            val floats = FloatArray(2048)
            while (running) {
                val n = recorder.read(shorts, 0, shorts.size)
                if (n > 0) {
                    for (i in 0 until n) floats[i] = shorts[i] / 32768f
                    onPitch(stabilize(autoCorrelate(floats, n)))
                }
            }
            runCatching { recorder.stop() }
            recorder.release()
        }.apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
    }

    /**
     * Requires the same semitone on [STABLE_FRAMES] consecutive frames before reporting it, and
     * holds the last stable reading for [HOLD_FRAMES] frames instead of flickering to "no pitch".
     */
    private fun stabilize(hz: Float): Float {
        if (hz <= 0f) {
            candidateMidi = Int.MIN_VALUE
            candidateCount = 0
            if (stableHz > 0f && missCount < HOLD_FRAMES) { missCount++; return stableHz }
            stableHz = -1f
            return -1f
        }
        missCount = 0
        val midi = Notes.freqToMidiFloat(hz.toDouble()).roundToInt()
        if (midi == candidateMidi) candidateCount++ else { candidateMidi = midi; candidateCount = 1 }
        if (candidateCount >= STABLE_FRAMES) stableHz = hz
        // while a new note is still settling, keep showing the previous stable one
        return stableHz
    }

    private fun autoCorrelate(b: FloatArray, size0: Int): Float {
        var size = size0
        var rms = 0.0
        for (i in 0 until size) rms += (b[i] * b[i]).toDouble()
        rms = sqrt(rms / size)
        if (rms < RMS_FLOOR) return -1f // too quiet

        var r1 = 0
        var r2 = size - 1
        val thres = 0.2f
        var i = 0
        while (i < size / 2) { if (abs(b[i]) < thres) { r1 = i; break }; i++ }
        i = 1
        while (i < size / 2) { if (abs(b[size - i]) < thres) { r2 = size - i; break }; i++ }

        val trimmed = b.copyOfRange(r1, r2)
        size = trimmed.size
        if (size < 2) return -1f

        val c = FloatArray(size)
        for (a in 0 until size) {
            var sum = 0f
            for (j in 0 until size - a) sum += trimmed[j] * trimmed[j + a]
            c[a] = sum
        }

        var d = 0
        while (d < size - 1 && c[d] > c[d + 1]) d++
        var maxval = -1f
        var maxpos = -1
        for (k in d until size) if (c[k] > maxval) { maxval = c[k]; maxpos = k }
        if (maxpos <= 0) return -1f
        // confidence: how periodic the frame really is (1.0 = perfectly periodic)
        if (c[0] <= 0f || maxval / c[0] < MIN_CONFIDENCE) return -1f

        var t0 = maxpos.toFloat()
        val x1 = c[maxpos - 1]
        val x2 = c[maxpos]
        val x3 = if (maxpos + 1 < size) c[maxpos + 1] else 0f
        val a2 = (x1 + x3 - 2 * x2) / 2
        val bb = (x3 - x1) / 2
        if (a2 != 0f) t0 -= bb / (2 * a2) // parabolic interpolation

        return sampleRate / t0
    }
}
