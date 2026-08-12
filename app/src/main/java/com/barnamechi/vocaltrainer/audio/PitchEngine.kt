package com.barnamechi.vocaltrainer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reads the mic and estimates fundamental frequency via normalized autocorrelation
 * (good for a sustained sung vowel). Emits Hz through [onPitch]; -1f means "no clear pitch".
 * Callback fires on the audio thread — marshal to main before touching UI state.
 */
class PitchEngine(private val onPitch: (Float) -> Unit) {
    private val sampleRate = 44100
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 4096)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        rec.startRecording()
        running = true

        thread = Thread {
            val shorts = ShortArray(2048)
            val floats = FloatArray(2048)
            while (running) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n > 0) {
                    for (i in 0 until n) floats[i] = shorts[i] / 32768f
                    onPitch(autoCorrelate(floats, n))
                }
            }
            runCatching { rec.stop() }
            rec.release()
        }.apply { start() }
        return true
    }

    fun stop() { running = false; thread?.join(200); thread = null }

    private fun autoCorrelate(b: FloatArray, size0: Int): Float {
        var size = size0
        var rms = 0.0
        for (i in 0 until size) rms += (b[i] * b[i]).toDouble()
        rms = sqrt(rms / size)
        if (rms < 0.01) return -1f // too quiet

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
