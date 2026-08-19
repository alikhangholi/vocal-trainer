package com.barnamechi.betterpitch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Click track. One streaming AudioTrack, one thread: a short decaying click every beat,
 * accented on the first beat of each bar of 4. No assets.
 *
 * It also publishes a beat clock ([audibleBeat]) so a visual can be locked to the click. The clock
 * is derived from the very same `beatSamples` the click is synthesised from, so any rounding drift
 * is common-mode: audio and visuals drift together and the relative error is exactly zero.
 */
class Metronome {
    private companion object {
        const val REQUESTED_RATE = 44100
        const val CLICK_SEC = 0.012
        const val CLICK_HZ = 1000.0
        const val ACCENT_HZ = 1500.0
        const val AMP = 0.35
        const val BEATS_PER_BAR = 4
        /**
         * Single knob for devices where getTimestamp() is unavailable: the fallback
         * (getPlaybackHeadPosition) excludes output latency, so visuals lead the click a little.
         * Positive values push the visuals later.
         */
        const val VISUAL_OFFSET_MS = 0.0
        const val TIMESTAMP_POLL_NS = 200_000_000L
    }

    /**
     * Where the generator stood at the first frame of a buffer. Immutable and published atomically
     * so a reader can never mix fields belonging to two different buffers.
     */
    private class Anchor(val frame: Long, val beat: Double, val beatsPerFrame: Double)

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var bpm = 60
    @Volatile private var sampleRate = REQUESTED_RATE

    private val anchor = AtomicReference<Anchor?>(null)

    // Reader-side state. UI thread only, so no synchronisation.
    private var lastBeat = 0.0
    private val ts = AudioTimestamp()
    private var tsFrame = 0L
    private var tsNanos = 0L
    private var tsPolledNs = 0L
    private var tsOk = false

    val beatsPerBar: Int get() = BEATS_PER_BAR

    fun isRunning(): Boolean = running

    fun setBpm(value: Int) { bpm = value.coerceIn(20, 300) }

    /**
     * Fractional beats since [start], at the instant currently reaching the speaker. Monotonic, and
     * it holds its last value while stopped. **Call from the UI thread only.**
     */
    fun audibleBeat(): Double {
        val a = anchor.get() ?: return lastBeat
        val t = track ?: return lastBeat
        // The delta is negative: the render thread is ahead of the speaker by the queued audio, so
        // this extrapolates backwards from the anchor. That is what makes a note's arrival line up
        // with the sound rather than with the render thread.
        val b = a.beat + (audibleFrames(t) - a.frame) * a.beatsPerFrame
        if (b > lastBeat) lastBeat = b // a fresh timestamp must never walk the clock backwards
        return lastBeat
    }

    private fun audibleFrames(t: AudioTrack): Double {
        val now = System.nanoTime()
        // getTimestamp() only refreshes once per HAL block and is comparatively expensive, so poll
        // it slowly and interpolate with the wall clock in between.
        if (now - tsPolledNs > TIMESTAMP_POLL_NS) {
            tsPolledNs = now
            tsOk = runCatching { t.getTimestamp(ts) }.getOrDefault(false)
            if (tsOk) { tsFrame = ts.framePosition; tsNanos = ts.nanoTime }
        }
        val sr = sampleRate
        val offset = VISUAL_OFFSET_MS * sr / 1000.0
        return if (tsOk) tsFrame + (now - tsNanos) * sr / 1e9 - offset
        else (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL).toDouble() - offset
    }

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
        sampleRate = sr

        // Fresh clock for this run; the reader-side timestamp cache belongs to the old track.
        anchor.set(null)
        lastBeat = 0.0
        tsOk = false
        tsPolledNs = 0L

        thread = Thread {
            val buf = ShortArray(256)
            val clickSamples = (CLICK_SEC * sr).toInt()
            var sampleInBeat = 0
            var beat = 0
            var beatIndex = 0L      // total beats since play(), for the clock
            var framesWritten = 0L  // absolute frame index of buf[0]
            while (running) {
                // beat length is read every buffer, so a BPM change takes effect immediately
                val beatSamples = (sr * 60.0 / bpm).toInt().coerceAtLeast(clickSamples + 1)
                anchor.set(
                    Anchor(
                        frame = framesWritten,
                        beat = beatIndex + sampleInBeat.toDouble() / beatSamples,
                        beatsPerFrame = 1.0 / beatSamples
                    )
                )
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
                        beatIndex++
                    }
                }
                t.write(buf, 0, buf.size)
                framesWritten += buf.size
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
        // lastBeat is deliberately kept, so a late audibleBeat() call returns a sane value.
        anchor.set(null)
    }
}
