# `ui/` — Compose conventions

All UI lives here. `Theme.kt` is shared infrastructure; the three screens are leaves; the two
canvases are the per-frame render surfaces for the games.

Use **`/beat-clock-game`** for game work and **`/new-screen`** for adding a route.

---

## Theme.kt is the only place the look is defined

Reuse these; do not introduce a new hex colour, shape, or one-off button in a screen file:

- **Colours:** `Ink` (page), `Surface` (card), `Line` (border/staff), `Honey` (primary/accent),
  `Mint` (correct/in-tune), `Coral` (wrong/danger), `TextC`, `Muted`, `Well` (inset), `OnHoney`,
  `OnCoral`.
- **Shapes:** `CardShape` (18.dp), `ChipShape` (10.dp).
- **Widgets:** `Modifier.card()`, `Chips` / `ChipRow`, `PrimaryButton`, `keyPressSpring`.
- **Helpers:** `dim()`, `label(m, solfege)`, `pcLabel(m, solfege)`, `BPM_CHOICES`, `Route`.

### Never `import androidx.compose.material3.*` in this package
`Theme.kt` defines `Surface` and `Line`, which **shadow material3 names on purpose**. A wildcard
material3 import creates an ambiguity that breaks the build. Import symbol by symbol — the screens
import exactly `androidx.compose.material3.Text`, `Switch`, `SwitchDefaults` and nothing more.

### `internal`, not `private`, for anything shared
Top-level `private` in Kotlin is *file*-scoped, so a `private val Honey` in `Theme.kt` would be
invisible to the screens. Everything shared in this package is `internal`. Keep helpers used by one
file `private`.

### `dim()` composites, it does not fade
`dim(c) = lerp(Ink, c, 0.35f)`. A disabled control keeps its exact place in the layout and simply
recedes; drawing with `alpha` instead would let a neighbouring key show through a dimmed one.

---

## Routing

`internal enum class Route { Home, SightReading, RhythmGame }` plus a `when` in `MainActivity` is the
entire router. There is no navigation library, no back stack, and no argument passing — screens take
plain parameters. Each game screen adds its own `BackHandler`.

---

## The beat-clock game contract

`SightReadingScreen` and `RhythmGameScreen` are two instances of one architecture. Getting any of
this wrong produces a game that looks fine and scores wrong.

**1. Everything is in beats. Never pixels.**
Px-per-beat (`ppb`) is computed fresh each frame from the canvas width and a fixed lead time, so a
BPM change simply eats beats faster — tempo sync is free, and changing BPM mid-round cannot affect
scoring. Do not cache pixel positions, and do not "optimise" by converting beats to px once.

**2. The round object is deliberately not snapshot state.**
`Game` (sight-reading) and `RhythmRound` (rhythm) are plain classes holding `IntArray`/`List`,
`results: ByteArray`, `judgedAt: FloatArray`, and a `target` cursor, mutated in place. Making them
observable would cost a recomposition per note and buy nothing, because the canvas already
invalidates draw every frame. One allocation per round is the point.

**3. `nowBeat: FloatState` is read ONLY inside the Canvas draw lambda.**
A snapshot read there invalidates draw and nothing else, so the canvas repaints at 60 fps without a
single recomposition. A read in a composable body recomposes the whole page 60 times a second. This
is the highest-value rule in the file.

Writes to `nowBeat.floatValue` happen in the frame loop; that is fine.

**4. The frame loop.**

```kotlin
LaunchedEffect(phase, round) {
    if (phase != Running) return@LaunchedEffect
    while (true) {
        withFrameNanos { }
        val b = (beatNow() - round.origin).toFloat()
        nowBeat.floatValue = b
        // auto-judge everything whose deadline has passed
        while (round.target < size && b > round.deadlineOf(round.target)) { ... ; round.target++ }
        if (round.target >= size) { phase = Finished; stopIfOurs(); break }
    }
}
```

It cancels itself when `phase` or the round changes, and `withFrameNanos` stops resuming while the
window is invisible. Judgement is a *catch-up* `while`, not an `if` — a dropped frame must not skip
a note.

**5. `anchorAt()` places the round on a bar accent.**
`origin = ceil((clockNow + leadBeats + 1.0 - beatOf(fromIndex)) / beatsPerBar) * beatsPerBar
+ countInBars * beatsPerBar` — one full lead-in away from the judgement line, rounded up to a bar
line so the round starts *with* the accented click, plus the rhythm game's count-in. The count-in is
a whole number of bars precisely so `origin` stays a multiple of `beatsPerBar` and that alignment
survives. Resuming from a pause re-anchors from the current target index and counts in zero bars.

`beatsPerBar` is the *level's*, not a screen parameter: the rhythm game reads
`level.meter.beatsPerBar` and pushes the same number straight into `Metronome.setBeatsPerBar`.
Routing it through a `mutableStateOf` would let a round be anchored on last composition's bar
length while the click already used the new one.

**6. Never clobber a click the user started themselves.**
`val wasClickingOnEntry = remember { metronomeOn }`, then
`stopIfOurs = { if (!wasClickingOnEntry) onStopMetronome() }`, wrapped in `rememberUpdatedState` and
called from `onDispose`, on finish, and on leave.

**7. `MainActivity.onStop()` pauses the metronome for game routes only.**
The frame loop freezes when the window goes away but audio would not, so returning would score
everything that elapsed as missed. Home keeps its click running. A `LaunchedEffect(metronomeOn)` in
each game turns that stop into `Phase.Paused`. **A new metronome-driven screen must be added to that
list in `MainActivity.onStop()`.**

**8. The judgement-line pulse is a free sync self-check.**
`1f - (now - floor(now))` blinks once per beat. If it does not blink *with* the click, the clock is
wrong — that is a diagnostic, not decoration.

---

## Canvas conventions

Both canvases take arrays mutated by the game plus `nowBeat`, and pick the visible window
themselves — they never iterate the whole round.

- **`StaffCanvas`** — beats are uniform (`beat(i) = i * beatsPerNote`), so the window is closed-form
  arithmetic: no search, no allocation. `gap` (one staff line-gap) is the unit for *every* dimension,
  capped by `MAX_STAFF_GAP` so a tall card doesn't blow the staff up. Verdict bytes: `R_PENDING`,
  `R_CORRECT`, `R_WRONG`, `R_MISSED`.
- **`RhythmTrackCanvas`** — real notation on a five-line staff: neutral clef, time signature,
  barlines, noteheads, stems, beams, flags, dots, ties, rest glyphs and triplet brackets, all from
  `DrawScope` primitives and `Path`. Durations are non-uniform, so the window comes from
  `lowerBound()` over the precomputed `onsetBeat` array, then widens to whole beam groups via
  `beamFirst`/`beamLast` — a group straddling the window edge must still draw as one beam. Verdict
  bytes: `RR_PENDING`, `RR_PERFECT`, `RR_GOOD`, `RR_MISSED`.
  - **No music font, ever.** The 𝄞 reasoning in `StaffCanvas.drawClef` covers the whole SMuFL set:
    Android devices don't ship one, and a missing glyph is a tofu box you cannot detect at runtime.
  - All heads sit on the middle line, so stems are uniform and **beams are horizontal** — the slant
    and stem-length optimisation that dominates a real beaming engine is simply not needed here.
  - The header (clef + time signature) is painted **last**, over an opaque `Surface` rect with the
    staff lines restored across it. Notes scroll to `x = 0`, and one sliding through the clef reads
    as a bug.
  - Everything the engraver needs is precomputed in `music/RhythmLayout`; the canvas never walks the
    whole round and never allocates.

Shared visual grammar: pending items `lerp(Muted, TextC, ...)` brighten as they approach so the eye
is drawn to what's next; a judged item flashes for 0.4 beats then rides off screen as history.

---

## Touch

```kotlin
awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)   // fires on touch-down, no tap/drag delay
    onPress(m)
    waitForUpOrCancellation()
    onRelease()
}
```

Used by the piano keys, the answer pad and the tap pad. It is left **unconsumed** deliberately, so a
drag still scrolls the keyboard — which cancels the gesture and releases the note. Do not switch
these to `clickable`; the tap/drag disambiguation delay is audible.

---

## Known duplication to keep in sync

`MAX_STAFF_GAP` lives in `Theme.kt` and is shared by both canvases — it used to be `private` in
`StaffCanvas`. Don't copy it back.

The white-key width `44.dp` appears twice: inside `Keyboard` and at the `KeyboardOverview` call site
in `BetterPitchScreen` (`contentWidth = 44.dp * whiteKeyCount`). Change one, change the other, or the
overview's viewport indicator drifts from the keyboard.
