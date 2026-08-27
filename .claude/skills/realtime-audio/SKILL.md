---
name: realtime-audio
description: Safely change betterPitch's realtime audio engines. Use for ANY edit under app/src/main/java/com/barnamechi/betterpitch/audio/ (ToneEngine, PitchEngine, Metronome), and for audio symptoms — stuck or hanging tone, notes sounding out of tune or flat, thread or AudioTrack leak, the mic detecting the app's own piano, laggy or jittery pitch detection, click and visuals drifting apart, crackle or glitching.
---

# Changing realtime audio in betterPitch

These three engines are hand-rolled: raw `Thread`s, `AudioTrack`/`AudioRecord`, no framework, no
coroutines on the audio path. Bugs here are silent and hard to hear — a detuned note still sounds
like a note. Work the checklist.

`audio/CLAUDE.md` has the full reference. This is the procedure.

---

## 1. Locate yourself: which engine, which thread?

| Engine | Threads | Owns |
|---|---|---|
| `ToneEngine` | render thread (`MAX_PRIORITY`) + UI thread callers | 4 `Voice`s, one `AudioTrack` |
| `PitchEngine` | capture thread; `onPitch` **fires there** | `AudioRecord`, AEC, NoiseSuppressor |
| `Metronome` | render thread + **UI-thread-only reader** `audibleBeat()` | `AudioTrack`, `Anchor` |

Write down which thread your new code runs on **before** editing. Most bugs here are a field read
from the wrong one.

`MainActivity` creates and destroys all three. It is the only place that should.

---

## 2. Check against the invariants

Walk these in order. Each has a concrete failure mode.

**No allocation in the loop.** Both render loops write from one pre-allocated `ShortArray(256)`;
`PitchEngine` reuses `shorts`/`floats`. Allocating inside `while (running)` → audible glitch on GC.
Buffers are `getMinBufferSize` deliberately, so keys sound on touch-down — enlarging trades away the
app's feel.

**Actual sample rate, never `REQUESTED_RATE`.** `t.sampleRate.takeIf { it > 0 } ?: REQUESTED_RATE`.
Used for phase increments, `nyquist`, `attackStep`, `decayCoef`, `releaseCoef`, `beatSamples`.
Failure mode: *every note slightly out of tune and every envelope wrong, on exactly the devices that
don't honour 44100* — it will sound almost right.

**Cross-thread fields are `@Volatile`.** `running`, `Voice.active`/`held`/`releasing`,
`lastSoundingMs`, `bpm`, `sampleRate`. `Metronome.Anchor` is immutable and published via
`AtomicReference` so a reader can't mix fields from two buffers — keep it that way.

**Voice re-arm sets `v.active = false` first, under `lock`.** That leading write parks the render
loop while the voice's fields are rewritten. It is not redundant. Stealing takes the quietest voice
(`minByOrNull { it.env }`).

**The mic self-gate.** `ToneEngine.isSounding()` — voices active plus a 200 ms tail — is what stops
the app's piano from being heard as the user's voice; `MainActivity`'s `PitchEngine` callback
early-returns on it. The second layer is `PitchEngine` preferring `VOICE_COMMUNICATION` (the source
the platform wires echo cancellation to) with AEC + NoiseSuppressor. Both matter. If you add a new
sound source that should not be detected, it must feed `isSounding()`.

**`onPitch` fires on the capture thread.** Marshal to main before touching UI state.

**`audibleBeat()` is UI-thread only and monotonic.** It extrapolates *backwards* from the anchor
because the render thread leads the speaker by the queued audio — that backwards delta is what makes
visuals land with the sound. It never walks backwards (`if (b > lastBeat)`). `getTimestamp()` is
polled every 200 ms and interpolated. **Audio and visuals share `beatSamples`, so drift is
common-mode and relative error is zero — never recompute beat length independently.** Calling
`audibleBeat()` off the UI thread is a data race.

**Everything must be stoppable.** `stop()` clears `running`, `join`s (200 ms), stops and releases
the track/recorder; `PitchEngine` also releases AEC/NS. `MainActivity.onDestroy` tears down all
three. Any new thread or system resource must be released there, or you get a leak and possibly a
stuck tone.

---

## 3. If you are touching detector constants

`RMS_FLOOR` `0.012f` · `MIN_CONFIDENCE` `0.70f` · `STABLE_FRAMES` `2` · `HOLD_FRAMES` `4`.

They are tuned for a **sung vowel** — a real voice with vibrato, breath and room noise sits around
0.7–0.85 confidence. Tighter and only a synth tone gets through; looser and room noise starts
reading as pitch.

**State the perceptual trade-off out loud instead of retuning silently.** For example: "raising
`MIN_CONFIDENCE` to 0.8 will reject breathy or vibrato-heavy singing"; "lowering `STABLE_FRAMES` to
1 makes detection snappier but the displayed note will jitter between semitones".

`autoCorrelate` is **O(n²)** over a trimmed 2048-sample frame. That cost is known and accepted for
C2–C6 accuracy. **Do not grow the analysis window.** If it must get cheaper, decimate or cap the
window — the original build brief called out ~1024 samples or decimation as the acceptable moves.

---

## 4. Do not "fix" these — they are correct

- `Metronome.lastBeat` surviving `stop()` (a late reader gets a sane value).
- The metronome click **not** being self-gated — it is too short to pass the periodicity gate, so
  you can sing along with it.
- `beat` (position in bar, drives the accent) and `beatIndex` (absolute, drives the clock) being
  two counters.
- `clickHit()` reusing the piano voices with a fixed short decay.
- High notes decaying faster than low ones — `2.5 * (440/freq)^0.35`, clamped 0.4–4.0 s. That's a
  piano.

---

## 5. Finish

Audio changes cannot be heard from here — there is no device, emulator, or compiler on this machine.

- Run **`/verify-build`** for the static pre-flight.
- Say plainly what you could not verify, and name the specific thing the user should listen for:
  e.g. "play a low C2 and a high C6 and check both are in tune", "toggle Sustain on and off and
  confirm no note hangs", "start the metronome, open the rhythm game, and confirm the judgement line
  blinks *with* the click, not before it".

The judgement-line pulse in both games is a free sync self-check — worth naming whenever you touch
`Metronome`.
