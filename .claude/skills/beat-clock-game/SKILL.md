---
name: beat-clock-game
description: Work on betterPitch's metronome-synced games. Use for any change to SightReadingScreen, RhythmGameScreen, StaffCanvas, RhythmTrackCanvas, music/Rhythm.kt or music/Staff.kt, and for requests like "add a rhythm level", "tune the hit windows", "change the note pool or range", "the notes drift from the click", "scoring feels wrong", "the game stutters", or "build another practice game".
---

# Beat-clock games in betterPitch

`SightReadingScreen` and `RhythmGameScreen` are two instances of one architecture: items scroll
right-to-left toward a fixed judgement line, driven by `Metronome.audibleBeat()`. Getting the rules
below wrong produces a game that *looks* fine and *scores* wrong — the worst kind of bug here.

`ui/CLAUDE.md` has the reference; `music/CLAUDE.md` covers the curriculum. This is the procedure.

---

## The five rules, in priority order

### 1. Everything is in beats. Never pixels.
Px-per-beat is recomputed each frame from canvas width and a fixed lead time. A BPM change therefore
just eats beats faster — tempo sync is free, and changing tempo mid-round **cannot** affect scoring.

Never cache a pixel position, never store a timestamp in ms, never convert a hit window to
milliseconds. If you find yourself writing `System.currentTimeMillis()` in game logic, stop.

### 2. `nowBeat: FloatState` is read ONLY inside the Canvas draw lambda.
A snapshot read there invalidates draw and nothing else, so the canvas repaints at 60 fps with zero
recompositions. A read in a composable body recomposes the entire page 60 times a second.

Writing `nowBeat.floatValue` from the frame loop is fine. Reading it inside `answer()`/`tap()` (plain
functions, not composable bodies) is fine.

```bash
# audit after editing:
grep -n "nowBeat" app/src/main/java/com/barnamechi/betterpitch/ui/*.kt
```
Every read should be inside a `Canvas { }` lambda or a non-composable event handler.

### 3. The round object is deliberately NOT snapshot state.
`Game` and `RhythmRound` are plain classes: the item array, `results: ByteArray`,
`judgedAt: FloatArray`, and a `target` cursor, all mutated in place. One allocation per round.
Making them observable costs a recomposition per note and buys nothing, because `nowBeat` already
invalidates draw every frame. Do not "modernise" them into `mutableStateListOf`.

Only genuinely observable things are Compose state: `phase`, the score counters, user settings.

### 4. The frame loop shape

```kotlin
LaunchedEffect(phase, round) {
    if (phase != Running) return@LaunchedEffect
    while (true) {
        withFrameNanos { }
        val b = (beatNow() - round.origin).toFloat()
        nowBeat.floatValue = b
        while (round.target < size && b > round.deadlineOf(round.target)) {
            // judge as missed (skip rests), advance
            round.target++
        }
        if (round.target >= size) { phase = Finished; stopIfOurs(); break }
    }
}
```

- Keyed on `(phase, round)` so it cancels itself on any phase change or new round.
- `withFrameNanos` stops resuming while the window is invisible.
- Deadline catch-up is a **`while`, not an `if`** — a dropped frame must not skip an item.

### 5. Anchoring and the metronome contract
- `anchorAt(clockNow, fromIndex, beatsPerBar)` sets
  `origin = ceil((clockNow + leadBeats + 1.0 - onsetOf(fromIndex)) / beatsPerBar) * beatsPerBar` —
  one full lead-in away, rounded up to a bar line so the round starts *with* the accented click.
  Resume re-anchors from the current `target`.
- `val wasClickingOnEntry = remember { metronomeOn }` +
  `stopIfOurs = { if (!wasClickingOnEntry) onStopMetronome() }` in `rememberUpdatedState`, called on
  finish, on leave, and from `onDispose`. **Never clobber a click the user started on the home
  screen.**
- `LaunchedEffect(metronomeOn) { if (!metronomeOn && phase == Running) phase = Paused }` — because
  `MainActivity.onStop()` stops the metronome for game routes, since the frame loop freezes in the
  background while audio would not.
- The judgement-line pulse (`1f - (now - floor(now))`) must blink **with** the click. It is a free
  sync self-check, not decoration.

---

## Recipe: add a rhythm level

1. **Patterns** in `music/Rhythm.kt`, from the helpers `q()` quarter, `h()` half, `e()` eighth,
   `s()` sixteenth, `dq()` dotted quarter, `t()` triplet eighth, `qr()`/`er()` rests, `tie(beats)`
   for a tied/long note. Each `pattern(...)` should total `beatsPerBar` (4) unless you mean
   otherwise. Draw whole patterns, not random durations — a round should read as a phrase.
2. **`RhythmLevel` entry**: `id` (contiguous from 1 — unlocking is sequential), `title`,
   `description`, `bpmRange`, patterns, `perfectWindowBeats`, `goodWindowBeats`, `passAccuracy`
   (0.85 everywhere so far), optional `passPerfectRatio` (levels 7–9 use 0.60–0.65).
3. **`bpmRange` must intersect `BPM_CHOICES`** (`40, 50, 60, 72, 84, 96, 120` in `ui/Theme.kt`).
   `RhythmPlayScreen` filters the tempo chips by it and falls back to `bpmRange.first` if the
   intersection is empty — which would offer a tempo that has no chip.
4. **Decide about level 9.** `LEVEL_9_PATTERNS` is the union of 5–8, so adding patterns to those
   levels automatically feeds it. A new level only reaches 9 if you add it to that union.
5. Windows tighten across the curriculum (0.12/0.25 → 0.05/0.12 beats). Place a new level's windows
   consistently with its neighbours.

## Recipe: tune hit windows
Windows are **beat fractions**, so they are already tempo-independent — that is the whole design.
Tightening `perfectWindowBeats` at 60 BPM tightens it in real time at 120 BPM too. State the effect
in ms at the level's own tempo when you report a change (`beats * 60000 / bpm`), because that is
what the user will feel.

## Recipe: change the sight-reading note pool
`Staff.naturalsInRange(low, high)` then `Staff.filterMode(pool, mode)`. **Preserve
`SightReadingScreen.currentPool()`'s empty-pool fallback** — a narrow range plus a LINES/SPACES
filter can select nothing (e.g. a one-note range of the wrong parity), and falling back to the
unfiltered range is what stops a crash. The game is naturals-only by design; adding accidentals is a
feature, not a tweak.

## Recipe: a new beat-clock game
Copy the skeleton, don't invent one: `RhythmRound` (non-uniform onsets, precomputed + binary search)
if durations vary; `Game` (uniform `beatOf(i) = i * beatsPerNote`, closed-form window) if they don't.
Then follow **`/new-screen`** for the routing and the `onStop()` pause list.

---

## Finish

Run **`/verify-build`**. There is no compiler or device here, so also tell the user what to watch:
start the click, enter the game, and confirm the judgement line blinks in time with the click and
that items cross the line exactly on the beat.
