package com.barnamechi.vocaltrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Click track. One streaming AudioTrack, one thread: a short decaying click every beat,
 * accented on the first beat of each bar of 4. No assets.
 */
class Metronome {
    private companion object {
        const val REQUESTED_RATE = 44100
        const val CLICK_SEC = 0.012
        const val CLICK_HZ = 1000.0
        const val ACCENT_HZ = 1500.0
        const val AMP = 0.35
        const val BEATS_PER_BAR = 4
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var bpm = 60

    fun isRunning(): Boolean = running

    fun setBpm(value: Int) { bpm = value.coerceIn(20, 300) }

    fun start(bpmValue: Int) {
        setBpm(bpmValue)
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            REQUESTED_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(REQUESTED_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        running = true

        val sr = t.sampleRate.takeIf { it > 0 } ?: REQUESTED_RATE

        thread = Thread {
            val buf = ShortArray(256)
            val clickSamples = (CLICK_SEC * sr).toInt()
            var sampleInBeat = 0
            var beat = 0
            while (running) {
                // beat length is read every buffer, so a BPM change takes effect immediately
                val beatSamples = (sr * 60.0 / bpm).toInt().coerceAtLeast(clickSamples + 1)
                for (i in buf.indices) {
                    val s = if (sampleInBeat < clickSamples) {
                        val hz = if (beat == 0) ACCENT_HZ else CLICK_HZ
                        val env = exp(-6.908 * sampleInBeat / clickSamples)
                        sin(2 * PI * hz * sampleInBeat / sr) * env * AMP
                    } else 0.0
                    buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
                    sampleInBeat++
                    if (sampleInBeat >= beatSamples) {
                        sampleInBeat = 0
                        beat = (beat + 1) % BEATS_PER_BAR
                    }
                }
                t.write(buf, 0, buf.size)
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
