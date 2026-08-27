---
name: verify-build
description: Check a code change as far as it can be checked without a compiler, then hand the build off correctly. Use BEFORE reporting any Kotlin/Gradle/manifest change in this repo as done, and whenever asked to "build", "compile", "run the app", "check it works", or "make an APK". This machine has no Android toolchain, so this skill replaces the build loop rather than running it.
---

# Verify a change in betterPitch

## Read this first

**You cannot compile, run, install, or test this app.** This machine has no `java`, no `gradle`, no
Android SDK, no `adb`, and no `gh`. There is no Gradle wrapper committed either.

So:

- **Never state or imply that a change builds, compiles, runs, or is tested.** Report what you
  changed and what you checked statically, and name the gap explicitly.
- Do not attempt `gradle`, `./gradlew`, `adb`, or `kotlinc`. They will fail, and retrying wastes the
  user's turn.
- The only real compile check is CI. Step 3 covers reading it.

---

## Step 1 — Static pre-flight (do all of this)

These are genuine checks, not theatre. Every item below has broken this repo or was flagged as a
risk in its original build brief.

### 1a. The material3 shadowing trap
`ui/Theme.kt` defines `Surface` and `Line`, deliberately shadowing material3 names.

```bash
grep -rn --include="*.kt" "import androidx.compose.material3\.\*" app/src/main/java/
```

Any hit is a build break. Imports in `ui/` must be symbol-by-symbol.

### 1b. Every symbol used is imported
The repo has already shipped this bug once — commit `a8d36a8`, "restore the lerp import in
BetterPitchScreen". For each file you touched, list the symbols you introduced and confirm each is
imported or same-package.

Same-package (`ui/`) needs no import: everything `internal` in `Theme.kt` — `Ink`, `Surface`, `Line`,
`Honey`, `Mint`, `Coral`, `TextC`, `Muted`, `Well`, `OnHoney`, `OnCoral`, `CardShape`, `ChipShape`,
`card()`, `dim()`, `label()`, `pcLabel()`, `Chips`, `ChipRow`, `PrimaryButton`, `keyPressSpring`,
`BPM_CHOICES`, `Route` — plus the canvases and their `R_*` / `RR_*` verdict constants.

Commonly forgotten: `androidx.compose.ui.graphics.lerp` (Color) vs
`androidx.compose.ui.util.lerp` (Float) — these are different functions; pick by argument type.
Also `kotlin.math.*` helpers, `withFrameNanos`, `mutableIntStateOf`/`mutableFloatStateOf`,
`rememberUpdatedState`, `BackHandler`.

```bash
# quick sanity: does the file reference something it never imports and isn't in-package?
grep -c "^import" <file>
```

### 1c. New files declare the right package
Path must match `package com.barnamechi.betterpitch.<dir>`.

### 1d. Known compiler traps in this project
- **Compose `offset` with a negative `Dp`** — `Modifier.offset(x = ...)` where the value can go
  negative (the black-key layout does this). Use the `Dp` overload, not the lambda one, when the
  value is computed at composition time.
- **`BoxWithConstraints` scope** — `maxWidth`/`maxHeight` are only available inside its content
  lambda.
- **Kotlin 2.0: `kotlinOptions` vs `compilerOptions`** — `app/build.gradle.kts` still uses the
  pre-2.0 `kotlinOptions { jvmTarget = "17" }` spelling. It works. Only change it if a build error
  actually demands it, and say so.
- **Compose compiler is a Gradle plugin** under Kotlin 2.0
  (`org.jetbrains.kotlin.plugin.compose`), not a `composeOptions` version. Do not add
  `composeOptions`.
- **`@Composable` context** — `remember`, `LaunchedEffect`, `rememberTextMeasurer` etc. cannot be
  called from a `DrawScope` lambda or a plain function.

### 1e. New dependencies must resolve from the configured mirrors
`settings.gradle.kts` puts Aliyun/Myket/Iranian mirrors first for local builds. A dependency that
exists only on some other host will build in CI and fail on the user's machine. Prefer using what
is already on the classpath: Compose BOM 2024.09.02, `activity-compose`, `core-ktx`,
`kotlinx-coroutines-android`.

### 1f. Manifest and resources
New permission → `AndroidManifest.xml` **and** a runtime request (see how `RECORD_AUDIO` is handled
in `MainActivity`). New user-visible string → `res/values/strings.xml`.

### 1g. Diff hygiene
```bash
git status && git diff --stat
```
Confirm only the files you intended are touched. **Read-only git — never commit, branch, or push.**

---

## Step 2 — Hand the build off

Report honestly, then give the user the commands. They can run any of them in-session by typing `!`
followed by the command:

```bash
gradle wrapper --gradle-version 8.9   # once — the wrapper binary is not committed
./gradlew assembleDebug               # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                # build + install to a connected device
```

Android Studio generates the wrapper on first open, so "open the folder in Android Studio" is a
valid alternative to the first line.

---

## Step 3 — Read CI

`.github/workflows/build.yml` runs `assembleDebug` on **every push** and re-emits compiler errors as
`::error::` workflow annotations, precisely so a failure is readable without downloading logs.

Getting at them:

- `gh` is **not installed**. If the user wants this loop to close cleanly, `brew install gh` is the
  durable fix — mention it once, don't nag.
- The repo is `github.com/alikhangholi/vocal-trainer`. If it is public, `WebFetch` on
  `https://github.com/alikhangholi/vocal-trainer/actions` can reach the run list.
- Otherwise ask the user to open the Actions tab and paste the annotations. Annotations are readable
  by anyone who can see the repo; raw logs need admin rights.

When you get errors back, they are `e: file:///...Foo.kt:12:34: message` — map the path back into
`app/src/main/java/...` and fix from there.

---

## Reporting template

> Changed: `<files>`.
> Statically checked: no wildcard material3 import, all new symbols imported, package declarations
> match, no new dependencies.
> **Not compiled** — there is no Android toolchain on this machine. To confirm, run
> `./gradlew assembleDebug`, or push and read the Actions annotations.
