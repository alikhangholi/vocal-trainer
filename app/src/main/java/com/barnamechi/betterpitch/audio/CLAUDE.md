# `audio/` — realtime engines

Three hand-rolled engines, each owning one raw `Thread` and one `AudioTrack`/`AudioRecord`. There is
no audio framework here and no coroutines on the audio path. `MainActivity` owns all three and is the
only place they are created and destroyed.

| Engine | Thread | Purpose |
|---|---|---|
| `ToneEngine` | render thread, `MAX_PRIORITY` | 4-voice additive synth: piano keys + rhythm tap click |
| `PitchEngine` | capture thread | mic → autocorrelation → Hz, via the `onPitch` callback |
| `Metronome` | render thread, `MAX_PRIORITY` | the click **and** `audibleBeat()`, the clock both games run on |

Use the **`/realtime-audio`** skill before changing anything here.

---

## The invariants

### 1. Never allocate in a render or capture loop
`ToneEngine` and `Metronome` each write from one `ShortArray(256)` allocated before the loop.
`PitchEngine` reuses `shorts` and `floats`. Anything allocating inside `while (running)` will
eventually produce an audible glitch when GC runs.

Buffer sizes are `AudioTrack.getMinBufferSize(...)` **on purpose** — the comment in `ToneEngine`
says "smallest safe buffer: keys must sound on touch-down". Enlarging it to stop an underrun trades
away the app's core feel.

### 2. Always use the track's *actual* sample rate
`REQUESTED_RATE` (44100) is a request. The device may open the track at something else.

- `ToneEngine.start()` reads `t.sampleRate.takeIf { it > 0 } ?: REQUESTED_RATE` and uses that `sr`
  for phase increments and `nyquist`.
- `ToneEngine.allocate()` re-reads it for `attackStep`, `decayCoef`, `releaseCoef`.
- `Metronome.start()` does the same and caches it into the `sampleRate` field for `audibleFrames()`.

Substituting the constant detunes every note and corrupts every envelope coefficient — and it will
sound *almost* right, so it will not be caught by ear on the first pass.

### 3. The threading contract
- Fields crossing threads are `@Volatile`: `running`, `Voice.active`/`held`/`releasing`,
  `lastSoundingMs`, `bpm`, `sampleRate`.
- Voice re-arm in `ToneEngine.allocate()` happens under `lock` **and sets `v.active = false` first**
  to park the render loop's use of that voice while its fields are rewritten. That leading write is
  not redundant — do not remove it.
- Voice stealing when all four are busy: `voices.minByOrNull { it.env }`, i.e. steal the quietest.
- `Metronome`'s `Anchor` is an immutable object published through an `AtomicReference`, so a reader
  can never mix `frame`, `beat` and `beatsPerFrame` from two different buffers. Keep it immutable.

### 4. The mic self-gate
`ToneEngine.isSounding()` — any voice active, plus a 200 ms `SELF_SOUND_TAIL_MS` tail after the
last one dies — is the contract that stops the app's own piano, coming back through the speaker,
from being detected as the user's voice. `MainActivity.startListening()` early-returns on it inside
the `PitchEngine` callback.

`PitchEngine` also asks for `VOICE_COMMUNICATION` first (the source the platform wires echo
cancellation to), falling back to `VOICE_RECOGNITION` then `MIC`, and enables `AcousticEchoCanceler`
and `NoiseSuppressor` where available. Both layers matter; neither alone is sufficient.

**`PitchEngine`'s `onPitch` callback fires on the capture thread.** Marshal to main
(`runOnUiThread`) before touching any UI state — `MainActivity` does.

### 5. `Metronome.audibleBeat()` — UI thread only, monotonic, common-mode
Fractional beats since `start()`, *at the instant currently reaching the speaker*. It extrapolates
**backwards** from the anchor, because the render thread runs ahead of the speaker by the queued
audio; that is what makes a note's arrival on screen line up with the sound rather than with the
render thread.

- Reader-side state (`lastBeat`, `ts`, `tsFrame`, `tsNanos`, `tsOk`) is UI-thread only and
  unsynchronised. Calling `audibleBeat()` from another thread is a data race.
- It never walks backwards: `if (b > lastBeat) lastBeat = b`.
- `getTimestamp()` refreshes only once per HAL block and is expensive, so it is polled every
  `TIMESTAMP_POLL_NS` (200 ms) and interpolated with the wall clock in between.
- **Audio and visuals both derive from the same `beatSamples`**, so rounding drift is common-mode
  and the relative error is exactly zero. Never recompute beat length independently anywhere else —
  that is precisely how the click and the notes come apart.
- `VISUAL_OFFSET_MS` is the single knob for devices lacking `getTimestamp()`, where the
  `playbackHeadPosition` fallback excludes output latency.

### 6. Every engine must be stoppable
`stop()` clears `running`, `join`s the thread (200 ms), then stops and releases the track/recorder.
`MainActivity.onDestroy()` is the single place all three are torn down; `PitchEngine` additionally
releases its `AcousticEchoCanceler`/`NoiseSuppressor`. A new thread or system resource added here
must be released in `stop()`, or the app leaks it and may leave a tone stuck on.

### 7. Detector constants are tuned for a *sung vowel*
`RMS_FLOOR = 0.012f`, `MIN_CONFIDENCE = 0.70f`, `STABLE_FRAMES = 2`, `HOLD_FRAMES = 4`. A real voice
— with vibrato, breath and room noise — sits around 0.7–0.85 confidence; tighter and only a synth
tone gets through. Changing any of these changes what counts as a voice, so state the perceptual
trade-off rather than retuning silently.

`autoCorrelate` is **O(n²)** over a trimmed 2048-sample frame. That cost is known and accepted for
C2–C6 accuracy. Do not grow the analysis window; if it needs to get cheaper, decimate or cap the
window rather than widening it.

---

## Looks like a bug, isn't

- **`Metronome.lastBeat` deliberately survives `stop()`** — so a late `audibleBeat()` call from a
  screen that is tearing down returns a sane value instead of 0.
- **The metronome click is intentionally *not* self-gated.** Only `ToneEngine` feeds
  `isSounding()`. The click is too short to pass `PitchEngine`'s periodicity gate, so you can sing
  along with the click while listening.
- **`beat` and `beatIndex` are two separate counters** in `Metronome`'s loop: `beat` is position
  within the bar (drives the accent), `beatIndex` is the absolute count (drives the clock).
- **`ToneEngine.clickHit()` reuses the piano voices**, just with a fixed 0.05 s decay instead of the
  pitch-dependent one. It is not a separate sound path.
- **Higher notes decay faster** — `2.5 * (440/freq)^0.35` clamped to 0.4–4.0 s — because that is
  what a piano does.
