---
name: new-screen
description: Add a new screen, route, page, or top-level feature surface to betterPitch. Use when asked to add a new practice mode, exercise, settings page, tab, or anything reachable as its own full-screen page, and when wiring new shared state into MainActivity. Covers the Route enum, MainActivity ownership, Theme.kt reuse, back handling, and the metronome pause contract.
---

# Adding a screen to betterPitch

The app has no navigation library and no ViewModels. `MainActivity` is the application layer;
screens are stateless-ish leaf composables taking parameters and returning callbacks. Follow the
existing shape — a screen that invents its own pattern will fight the rest of the app.

---

## 1. Route it

`ui/Theme.kt`:
```kotlin
internal enum class Route { Home, SightReading, RhythmGame }   // add yours
```

`MainActivity.onCreate`'s `when (route.value)` gets a branch. That `when` is the entire router.
Entry point from home is a card in `BetterPitchScreen` — copy `SightReadingCard` / `RhythmGameCard`
and pass `onOpen = { route.value = Route.Yours }` down as a parameter.

For a screen with internal pages (like the rhythm game's level-select → play), use a **private**
enum inside that file (`ScreenSection`) rather than adding more top-level routes.

---

## 2. Decide who owns the state

The rule, from how the app already works:

- **Shared across screens or outliving one → `MainActivity` owns it** as `mutableStateOf`, passed
  down as a value plus an `on...Change` lambda. Currently: `bpm`, `solfege`, `route`, mic results,
  rhythm progress.
- **Yours alone → `remember` inside the screen.** Currently: `sustain`, a round in progress, level
  selection, range and mode pickers.

Do **not** add a ViewModel, DI, or a repository. Their absence is deliberate — see the root
`CLAUDE.md`. If you genuinely need persistence, read `data/CLAUDE.md` first: `RhythmProgress` is a
deliberate single exception, and adding a second store is a design decision to raise, not assume.

Audio is never touched directly by a screen. `MainActivity` owns the engines and passes narrow
lambdas — `playNote: (Int) -> Unit`, `beatNow: () -> Double`, `onTapSound: () -> Unit`. Keep that
boundary.

---

## 3. Build it from Theme.kt

Reuse, don't reinvent: `Modifier.card()`, `ChipRow`/`Chips`, `PrimaryButton`, `dim()`,
`label()`/`pcLabel()`, `CardShape`/`ChipShape`, `keyPressSpring`, and the palette (`Ink`, `Surface`,
`Line`, `Honey`, `Mint`, `Coral`, `TextC`, `Muted`, `Well`, `OnHoney`, `OnCoral`).

**No new hex colours in a screen file.** If a genuinely new token is needed, add it to `Theme.kt`.

**Never `import androidx.compose.material3.*`** — `Surface` and `Line` shadow material3 names on
purpose. Import symbol by symbol.

Page shell, matching both game screens:
```kotlin
Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp, vertical = 18.dp)) {
    // header row: "‹ Back" chip, status text with Modifier.weight(1f), trailing toggle
}
```

For touch-down response (keys, pads) use `awaitEachGesture` + `awaitFirstDown(requireUnconsumed =
false)`, left unconsumed so a drag can still scroll and cancel. Not `clickable` — its tap/drag delay
is audible.

---

## 4. Back handling

Every non-home screen needs:
```kotlin
BackHandler { leave() }   // androidx.activity.compose.BackHandler
```
where `leave()` performs cleanup then calls `onBack()`. There is no back stack to unwind — `onBack`
just sets `route.value = Route.Home` (or the parent section).

---

## 5. If it uses the metronome

Three things, all mandatory:

1. **Add your route to `MainActivity.onStop()`'s pause list.**
   ```kotlin
   val pausesOnStop = route.value == Route.SightReading || route.value == Route.RhythmGame
   ```
   A frame loop freezes when the window goes away but the click would keep running, so returning
   would score everything that elapsed as missed.
2. **Adopt `wasClickingOnEntry` / `stopIfOurs`** so you never stop a click the user deliberately
   started on the home screen:
   ```kotlin
   val wasClickingOnEntry = remember { metronomeOn }
   val stopIfOurs by rememberUpdatedState(newValue = { if (!wasClickingOnEntry) onStopMetronome() })
   DisposableEffect(Unit) { onDispose { stopIfOurs() } }
   ```
3. **React to the external stop:**
   ```kotlin
   LaunchedEffect(metronomeOn) { if (!metronomeOn && phase == Running) phase = Paused }
   ```

If the screen scrolls items in time with the click, stop here and use **`/beat-clock-game`** instead
— that architecture has rules this skill does not repeat.

---

## 6. Strings and manifest

User-visible app name lives in `res/values/strings.xml`; screen copy is inline in the composables,
matching the existing style. A new permission needs both a `<uses-permission>` entry **and** a
runtime request — copy the `RECORD_AUDIO` flow in `MainActivity` (`registerForActivityResult` +
`ContextCompat.checkSelfPermission`).

---

## 7. Finish

Run **`/verify-build`**. Nothing here can be compiled or run locally, so report what you checked
statically and hand the build off.
