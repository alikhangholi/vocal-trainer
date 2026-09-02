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

## `Rhythm` + `Notation` — the curriculum and its engraving

The rhythm game **draws real notation**, so the model is symbolic. It used to be plain floats on the
grounds that a duration track needs nothing more; that is no longer true and the reasoning is
inverted — `1.5f` is a dotted quarter in 4/4 and unwritable in 6/8, so the *symbol* is the truth and
the duration is derived from it.

### Two number systems, on purpose

- **Ticks (`Int`, `TICKS_PER_WHOLE = 1920`) for structure.** Every notatable value divides 1920
  exactly, including triplets (160) and compound eighths (240). Every engraving question — "same
  beat?", "crosses the barline?" — is a boundary equality, and floats cannot answer it: three 6/8
  eighths summed as `1f/3f` make 0.99999997 and land in the wrong beat.
- **Beats (`Float`) for scrolling and scoring**, where a rounding error is invisible. `onsetBeat[i]`
  is one division of the tick onset, never an accumulation.

### The shape

`NoteValue` (+ `dots`, `tuplet`, `isRest`, `tiedToNext`) → `RhythmEvent` → `RhythmPattern(events,
meter)` → `RhythmLevel`. `RhythmPattern` **requires** its events to fill exactly one bar of its
meter, and throws at class-init if they don't — a pattern that doesn't add up would push every later
barline off the accent for the rest of the round. Rounds are built by drawing **whole patterns**, so
a round reads as a musical phrase rather than atomised random note-lengths.

`Meter(top, bottom)` derives everything else, so a meter cannot be written down inconsistently.
`beatTicks` does double duty: it is both the metronome click period and the cell beams group inside.
**That one fact is why compound time needs no special case anywhere else** — in 6/8 the beat is the
dotted quarter, a bar is *two* clicks and not six, an eighth is simply 1/3 of a beat, and the same
grouping rule that beams eighths in pairs in 4/4 beams them in threes here.

`RhythmLayout` (in `Notation.kt`) is the engraver: onsets, beam groups (`beamFirst`/`beamLast`),
tuplet groups, tie continuations, and `tapEligible`. It is built once per round and is immutable
after `init`; the canvas never recomputes any of it.

- **A tie is a real thing now** (`tiedToNext`), but only where no single symbol can express the
  length — 2.5 beats in 4/4, or a note spanning both dotted-quarter groups of a 6/8 bar. Anything a
  dot can write uses the dot. Exactly two patterns in the whole curriculum need one.
- **`tapEligible` is the single skip rule.** A rest and the far side of a tie are both drawn and
  never tapped, so the frame loop and `tap()` skip on one predicate instead of one check per
  exception. Never add a second.
- **Hit windows are in beats, not milliseconds**, so they scale with BPM automatically. Never
  convert them to ms in game logic. (The end-of-round timing bias is displayed in ms; that is a
  display conversion, and it is commented as such.)
- Level sequencing follows standard music-ed practice: quarters → rests → paired eighths → eighth
  rests → dotted → sixteenths → triplets → syncopation/ties → mixed, then 3/4 and compound 6/8.
  `LEVEL_9_PATTERNS` is the union of levels 5–8, so **adding a pattern to 5–8 automatically feeds
  level 9** — and for the same reason **a non-4/4 pattern must never go into 5–8**, or level 9 would
  mix meters and fail its own bar check.
- `passAccuracy` / `passPerfectRatio` gate nothing — every level is open. They are the verdict shown
  on the results line.

### Adding a level

1. Build patterns from `q() h() dh() e() s() dq() t() qr() er()` and `tied(...)`, using the
   `p44`/`p34`/`p68` helper for the meter. The bar check will tell you immediately if it is wrong.
2. Add a `RhythmLevel` with its `meter`, `perfectWindowBeats`, `goodWindowBeats`, `passAccuracy`.
3. **`goodWindowBeats` must stay under half the level's smallest note-to-note gap.** Judging walks a
   single `target` cursor, so a wider window lets a tap aimed at one note be credited to the one
   before it — a scoring bug that never looks like one. Sixteenths give a 0.25-beat gap and 6/8
   eighths give 1/3 of a beat, so both sit at 0.12.
4. **`bpmRange` must intersect `BPM_CHOICES`** (`40, 50, 60, 72, 84, 96, 120` in `ui/Theme.kt`) —
   `RhythmPlayScreen` filters the chip row by it and falls back to `bpmRange.first` if empty, which
   would offer a tempo with no chip. In compound meter those numbers are **dotted-quarter** tempos,
   so keep them low: 72 there is already 216 eighths a minute.
5. Decide whether it should feed `LEVEL_9_PATTERNS` (4/4 patterns only).
6. **Never renumber an existing level.** Best scores are stored under `level_{id}_best`, so a
   renumber silently moves every score onto the wrong level. Append.
