# `data/` — the one storage exception

`RhythmProgress` is the **only** persistence in the app. betterPitch is otherwise entirely
stateless: no database, no files, no network, no account, and nothing that survives a restart. This
single `SharedPreferences` store exists because a levelled game needs progress to survive a restart —
it is a deliberate, argued exception, not a precedent.

**If a new feature wants to persist something, raise it as a design decision. Do not assume it.**

## Contract

- `unlockedThrough()` — highest unlocked level id, default `1`.
- `bestAccuracy(levelId)` — best percentage seen, default `0`.
- `recordResult(levelId, accuracyPercent, passed)` — called once per finished round.

Two behaviours the UI depends on:

- **It never re-locks.** A level already unlocked stays unlocked, whatever a later round scores.
- **Best scores only ever increase.** `recordResult` writes only if the new value is higher.

`MainActivity` reads the store once in `onCreate`, mirrors it into `mutableStateOf`
(`rhythmUnlockedThrough`, `rhythmBestScores`), and updates both the store and the mirror in the
`onLevelResult` callback. The mirror is what Compose observes — writing to prefs alone will not
update the level-select screen.

Keys are `"unlocked_through"` and `"level_{id}_best"` under the prefs file `"rhythm_progress"`.
Renaming any of them silently resets every user's progress.
