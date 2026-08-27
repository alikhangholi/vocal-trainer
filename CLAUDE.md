# betterPitch — Claude guide

Native Kotlin + Jetpack Compose Android app for ear and rhythm training. One activity, three
screens: a reference keyboard with live mic pitch detection, a scrolling sight-reading game, and a
levelled rhythm game. Every feature is free — there is no paywall, no billing SDK, no account, and
no network layer.

Repo is `vocal-trainer`; the app is `betterPitch` (`com.barnamechi.betterpitch`).

---

## Build reality — read this before promising anything

**There is no Android toolchain on this machine.** No `java`, no `gradle`, no Android SDK, no `adb`,
no `gh`. No Gradle wrapper is committed either. Claude **cannot compile, run, install, or test this
app locally.**

Consequences:

- **Never say a change "builds", "compiles", or "works".** Say what was changed and what was checked
  statically. Overstating this is the single most damaging thing to get wrong here.
- Do not run or suggest running `gradle`, `./gradlew`, or `adb` as something *you* will do.
- The real compile check is CI: `.github/workflows/build.yml` runs `assembleDebug` on every push and
  re-emits compiler errors as `::error::` workflow annotations, specifically so a failure is
  readable without downloading logs (which needs admin rights).

Use the **`/verify-build`** skill for the checks that *are* possible offline, and for how to hand the
build off.

The user builds on their own machine or via CI. Commands for them (they can run any of these
in-session by typing `!` followed by the command):

```bash
gradle wrapper --gradle-version 8.9   # once — the wrapper binary is not committed
./gradlew assembleDebug               # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                # build + install to a connected device
```

---

## House rules

- **Git is read-only for Claude.** Reading history, diffs and blame is fine and encouraged. Never
  commit, branch, stage, push, or merge — commits and branches belong to the user.
- **Never run deploy, publish, or release steps.**
- **Smallest change that works. Don't gold-plate.** The features here are implemented and
  intentional: verify them, don't redesign them.
- **`docs/` is gitignored** (`.gitignore` is literally `docs/*`). Nothing written there is part of
  the repo. Never put a deliverable in it.
- **Don't add infrastructure opportunistically.** See the architecture note below — the absences are
  deliberate.
- Comments in this codebase carry real load: many explain why an apparently-odd choice is correct.
  Match that density, and never delete an explanatory comment while editing the code near it.

---

## Architecture in one screen

`MainActivity` is the whole application layer. It:

- owns all three audio engines (`ToneEngine`, `PitchEngine`, `Metronome`) and their lifecycle,
- owns every piece of state that outlives or crosses a screen — `bpm`, `solfege`, `route`, mic
  results, rhythm progress — as plain `mutableStateOf`,
- passes state down as ordinary parameters and callbacks up as lambdas.

Screens own only what is theirs alone (`sustain`, a round in progress, a level selection) via
`remember`.

**Deliberately absent — do not introduce without asking:** dependency injection, ViewModels,
a navigation library (`Route` enum + `when` is the router), a repository layer, and a test suite.
`RhythmProgress` is the one storage exception, and it is documented as such.

---

## Project map

| Path | What it is |
|---|---|
| `MainActivity.kt` | Permission, engine ownership, all shared state, the `when` router |
| `music/` | Pure music domain — no Android imports. See `music/CLAUDE.md` |
| `music/Notes.kt` | MIDI ↔ frequency, note names, solfège, cents, C2–C6 range |
| `music/Staff.kt` | Treble-staff geometry in diatonic "steps"; the sight-reading note pool |
| `music/Rhythm.kt` | The 9-level rhythm curriculum, patterns, and hit windows |
| `audio/` | Three hand-rolled realtime engines. See `audio/CLAUDE.md` |
| `audio/ToneEngine.kt` | 4-voice additive synth (`strike`/`noteOn`/`damp`/`noteOff`/`clickHit`) |
| `audio/PitchEngine.kt` | Mic capture + autocorrelation pitch detection |
| `audio/Metronome.kt` | Click track **and** the beat clock both games are driven by |
| `data/RhythmProgress.kt` | The only persistence. See `data/CLAUDE.md` |
| `ui/` | All Compose. See `ui/CLAUDE.md` |
| `ui/Theme.kt` | The single source of colours, shapes, shared widgets, and `Route` |
| `ui/BetterPitchScreen.kt` | Home: keyboard, toggles, mic panel, metronome, game entry cards |
| `ui/SightReadingScreen.kt` + `ui/StaffCanvas.kt` | The note-reading game |
| `ui/RhythmGameScreen.kt` + `ui/RhythmTrackCanvas.kt` | The rhythm game |

Nested `CLAUDE.md` files load only when Claude touches that directory — put package-specific rules
there, not here.

---

## Skills

| Skill | Use when |
|---|---|
| `/realtime-audio` | Any change under `audio/`, or an audio symptom (stuck tone, detune, leak) |
| `/beat-clock-game` | Either game, either canvas, rhythm levels, hit windows, a new game |
| `/verify-build` | Before reporting any code change as done |
| `/new-screen` | Adding a route, page, or top-level feature surface |

---

## Versions, repositories, secrets

**Version set:** AGP 8.5.2 · Kotlin 2.0.20 · Compose BOM 2024.09.02 · compileSdk/targetSdk 34 ·
minSdk 24 · JVM target 17.

Kotlin 2.0 means the Compose compiler comes from the `org.jetbrains.kotlin.plugin.compose` Gradle
plugin, not a `composeOptions` version. `app/build.gradle.kts` still uses the pre-2.0 `kotlinOptions`
spelling rather than `compilerOptions`; it works, so leave it unless a build error demands the
change.

**Repositories:** `settings.gradle.kts` deliberately orders repositories twice. Under
`GITHUB_ACTIONS` the canonical `google()`/`mavenCentral()`/`gradlePluginPortal()` come first; locally
the Aliyun/Myket/Iranian mirrors come first, because the user builds from a network where the
canonical hosts are unreliable. **Do not "clean up" this ordering.** A new dependency must be
available from those mirrors, not only from Maven Central.

**Secrets:** release signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
through the `secret()` helper in `app/build.gradle.kts` — Gradle property, then environment
variable, then a default. Never hardcode a credential, never commit a keystore, never echo one into
a log. Without a keystore the release variant still builds, unsigned, by design.
