# `music/` — pure domain

`Notes`, `Staff`, `Rhythm`. **Zero Android imports — keep it that way.** This is the only layer that
can be reasoned about, and one day tested, without an emulator. If something here starts needing a
`Context`, it belongs in `data/` or the screen instead.

All three are stateless `object`s of pure functions and `val` tables.

---

## `Notes` — pitch

**MIDI integers are the app's universal pitch currency.** Engines, keyboard, detector, staff and
games all pass `Int` MIDI numbers; Hz appears only at the audio boundary.

- `midiToFreq` / `freqToMidiFloat` — equal temperament, A4 = 440.
- `LOW = 36` (C2), `HIGH = 84` (C6), `MIDDLE_C = 60`. These two constants are the keyboard's range
  knobs; widening them widens the on-screen keyboard automatically.
- `solfege()` **falls back to the note name on accidentals** — the `SOLFEGE` map only covers the
  seven naturals. That is intended, not a gap: there is no Do♯/Ré♭ decision to make.
- `centsOff(f, m)` is the tuning-meter number; `isBlack(m)` drives keyboard layout.

## `Staff` — treble-staff geometry

Works in the **diatonic "step" domain**, not semitones: one step is one letter of the musical
alphabet, i.e. half a staff line-gap. Steps are absolute, so consecutive letters always differ by
exactly 1 whatever the octave.

- `DIATONIC` maps pitch class → diatonic index, using **`-1` to mark an accidental**. `isNatural()`
  is just `DIATONIC[pc] >= 0`.
- The game is **naturals-only by design**, so there is never a C♯-vs-D♭ spelling decision and never
  an accidental glyph to draw. Adding accidentals is a real feature, not a tweak.
- Landmark constants: `BOTTOM_LINE_STEP` 37 (E4), `G_LINE_STEP` 39, `MIDDLE_LINE_STEP` 41 (B4),
  `TOP_LINE_STEP` 45 (F5). Game range `GAME_LOW` 60 (C4) – `GAME_HIGH` 84 (C6).
- **`isLine()` is step parity** against `BOTTOM_LINE_STEP`, matching `StaffCanvas`'s
  `BOTTOM_LINE_STEP..TOP_LINE_STEP step 2` draw loop. The parity holds through ledger lines too,
  which is why lines/spaces filtering needs no special-casing outside the staff.
- `NATURALS`, `naturalsInRange()`, `filterMode()` build the game's pool;
  `stepDownNatural`/`stepUpNatural` are the range-stepper ladder.

Note the callers guard against an empty pool: a narrow range combined with a LINES/SPACES filter can
select nothing, and `SightReadingScreen.currentPool()` falls back to the unfiltered range rather
than crashing. Preserve that guard.

## `Rhythm` — the curriculum

- **Durations are plain floats** (quarter = `1f`, dotted quarter = `1.5f`, triplet eighth = `1f/3f`),
  not an enum of note values, because the game draws a scrolling duration track and never notation.
  **A tie is simply one event with a longer `beats`** — there is no tie concept to model.
- `RhythmEvent(beats, isRest)` → `RhythmPattern(events, beatsPerBar = 4)` → `RhythmLevel`. Rounds are
  built by drawing **whole patterns**, not individual durations, so a round reads as a musical
  phrase rather than atomised random note-lengths.
- **Hit windows are in beats, not milliseconds**, so they scale with BPM automatically. Never
  convert them to ms.
- Level sequencing follows standard music-ed practice: quarters → quarter rests → paired eighths →
  eighth rests → dotted → sixteenths → triplets → syncopation/ties → mixed. `LEVEL_9_PATTERNS` is
  the union of levels 5–8, so **adding a pattern to 5–8 automatically feeds level 9**.
- `passAccuracy` is the fraction of tap-eligible events that must be Perfect-or-Good (0.85
  everywhere). `passPerfectRatio` is the extra gate that only levels 7–9 carry.
- Windows tighten across the curriculum (0.12/0.25 beats down to 0.05/0.12), approaching but never
  reaching commercial rhythm-game tightness — this app teaches timing rather than testing trained
  reflexes.

### Adding a level
1. Build patterns from the `q() h() e() s() dq() t() qr() er() tie()` helpers.
2. Add a `RhythmLevel` with `perfectWindowBeats`, `goodWindowBeats`, `passAccuracy`.
3. **`bpmRange` must intersect `BPM_CHOICES`** (`40, 50, 60, 72, 84, 96, 120` in `ui/Theme.kt`) —
   `RhythmPlayScreen` filters the chip row by it and falls back to `bpmRange.first` if empty, which
   would offer a tempo with no chip.
4. Decide whether it should feed `LEVEL_9_PATTERNS`.
5. Levels unlock sequentially by `id`, so ids must stay contiguous from 1.
