package com.barnamechi.vocaltrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Streams a continuous sine tone. noteOn(freq) holds the note indefinitely
 * (this is the "play continuously" behaviour); noteOff() fades it out.
 * A smoothed amplitude envelope avoids clicks on start/stop and note changes.
 */
class ToneEngine {
    private val sampleRate = 44100
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var targetFreq = 440.0
    @Volatile private var targetAmp = 0.0

    private var phase = 0.0
    private var amp = 0.0

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
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
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        running = true

        thread = Thread {
            val buf = ShortArray(512)
            while (running) {
                val inc = 2 * PI * targetFreq / sampleRate
                val ta = targetAmp
                for (i in buf.indices) {
                    amp += (ta - amp) * 0.0006 // ~40 ms smoothing
                    val s = sin(phase) * amp * 0.9
                    buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
                    phase += inc
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                t.write(buf, 0, buf.size)
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun noteOn(freq: Double) { targetFreq = freq; targetAmp = 0.25 }
    fun noteOff() { targetAmp = 0.0 }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        amp = 0.0
    }
}
