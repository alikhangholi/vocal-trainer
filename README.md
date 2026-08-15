# betterPitch — native Android

Native Kotlin + Jetpack Compose. Plays reference notes (with a **Sustain** mode that
holds a note continuously), and detects your sung pitch from the mic in real time —
the key you sing lights up green, with a live tuning meter and a session range finder.

## What's here
- `app/src/main/java/com/barnamechi/betterpitch/`
  - `music/Notes.kt` — MIDI↔frequency, note names, solfège, cents, free-tier note set
  - `audio/ToneEngine.kt` — continuous tone synthesis (AudioTrack); `noteOn`/`noteOff`
  - `audio/PitchEngine.kt` — mic capture + autocorrelation pitch detection (AudioRecord)
  - `billing/BillingManager.kt` — Cafe Bazaar subscription (Poolakey); `isPremium: StateFlow`
  - `MainActivity.kt` — RECORD_AUDIO permission, engine ownership, state
  - `ui/BetterPitchScreen.kt` — keyboard, sustain/solfège toggles, unlock + mic panels

## Free tier vs. subscription
Free covers the seven solfège notes of the C4 octave (`Notes.FREE_MIDI`). The full C2–C6
keyboard and live mic detection require the `betterpitch_premium` subscription, bought
through Cafe Bazaar. Verification is client-side only (no backend) — Poolakey checks
Bazaar's signature against `BuildConfig.BAZAAR_RSA_KEY`, injected at build time.

## Build (on your machine — SDK required)
This project has no Gradle wrapper binary committed. Generate it once, then build:

```bash
# from the project root
gradle wrapper --gradle-version 8.9      # or: open the folder in Android Studio (it does this for you)
./gradlew assembleDebug                   # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                    # build + install onto a connected phone / emulator
```

Enable USB debugging on the phone, or use `adb install app-debug.apk`.

For a signed release, supply the keystore and the Bazaar key as Gradle properties or
environment variables — never in the repo:

```bash
KEYSTORE_FILE=/abs/path/betterpitch-release.jks KEYSTORE_PASSWORD=... \
KEY_ALIAS=betterpitch KEY_PASSWORD=... BAZAAR_RSA_KEY=... \
  ./gradlew assembleRelease
```

Without `KEYSTORE_FILE` the release variant still builds, just unsigned.

## Notes / knobs
- Range is C2–C6 — change `LOW`/`HIGH` in `Notes.kt`.
- Tone is a pure sine; add harmonics in `ToneEngine` for a warmer timbre.
- `minSdk = 24`. Versions (AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM) are sane defaults —
  Android Studio or Claude Code will reconcile them to your installed SDK if needed.
