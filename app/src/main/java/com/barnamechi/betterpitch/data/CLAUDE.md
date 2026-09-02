# `data/` — the one storage exception

`RhythmProgress` is the **only** persistence in the app. betterPitch is otherwise entirely
stateless: no database, no files, no network, no account, and nothing that survives a restart. This
single `SharedPreferences` store exists because a levelled game needs its scores to survive a
restart — it is a deliberate, argued exception, not a precedent.

**It records; it does not gate.** Every rhythm level is playable from a fresh install. There used to
be an `unlockedThrough` counter that made a level non-clickable until you passed the one before it;
it is gone, along with its `unlocked_through` key. Nothing here can make a level unreachable.

**If a new feature wants to persist something, raise it as a design decision. Do not assume it.**

## Contract

- `bestAccuracy(levelId)` — best percentage seen, default `0`.
- `recordResult(levelId, accuracyPercent)` — called once per finished round.

One behaviour the UI depends on: **best scores only ever increase.** `recordResult` writes only if
the new value is higher.

`MainActivity` reads the store once in `onCreate`, mirrors it into `mutableStateOf`
(`rhythmBestScores`), and updates both the store and the mirror in the `onLevelResult` callback. The
mirror is what Compose observes — writing to prefs alone will not update the level-select screen.

Keys are `"level_{id}_best"` under the prefs file `"rhythm_progress"`. Renaming the file or the key
silently resets every score — and so does **renumbering a level**, which is why levels are only ever
appended.
