# Claude Code — build brief

Be token efficient.

This folder is a near-complete native Android app (Kotlin + Jetpack Compose). Your job is
to get it building and running as a debug APK on my device, without changing the design or
feature set. Do **not** run git — commits and branches are mine. Do **not** run deploy/publish
steps. You may run Gradle **only** for building/installing the debug APK, since that's the
deliverable; if you'd rather I run those commands, list them and I'll run them.

## App spec (already implemented — verify, don't redesign)
- Reference keyboard C2–C6, tap to play. **Sustain** toggle = a tapped note holds
  continuously until tapped again; off = press-and-hold. Solfège toggle swaps note names for Do/Re/Mi.
- Mic pitch detection (autocorrelation): shows note name, cents, in-tune (green), a tuning
  meter, and a lowest/highest session range. The sung note lights up on the keyboard.
- RECORD_AUDIO requested at runtime.

## Tasks
1. Generate the Gradle wrapper and reconcile AGP / Kotlin / Compose-compiler / Compose-BOM
   versions to whatever SDK + JDK I have installed. Fix any compile errors (watch: Compose
   `offset` with a negative Dp, `BoxWithConstraints` scope, `kotlinOptions` vs
   `compilerOptions` under Kotlin 2.0).
2. Build `assembleDebug`; report the APK path.
3. Sanity items to check while you're in there:
   - audio threads are stopped in `onDestroy` (no leak / no stuck tone),
   - detection stays responsive (if the O(n²) autocorrelation is heavy on-device, cap the
     analysis window ~1024 samples or decimate — keep accuracy for C2–C6),
   - keyboard black-key offsets line up across the whole range.
4. Close with a short report in `/docs` (what changed, final versions, APK path, anything I should know).

Don't gold-plate. Smallest set of changes that yields a working debug APK.
