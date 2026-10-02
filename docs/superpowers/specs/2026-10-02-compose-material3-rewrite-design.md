# Ghost Rain — Compose Material3 Rewrite (Approach A) — Design

Date: 2026-10-02. Status: approved in chat (§1–§5), pending file review.

## 1. Intent

Reimplement the Settings UI with a modern look via `android.useAndroidX=true`
+ Compose Material3, plus migrate the build to Kotlin DSL (`*.gradle.kts`)
with updated dependencies. The app is already Kotlin; only the build scripts
are Groovy today.

Success: same wallpaper behavior and same `matrix` prefs, Settings looks
Material You modern, `./gradlew assembleDebug` / `assembleRelease` pass on
Termux (JDK 17).

## 2. Constraints (carry over)

- Package `org.ghostrain`, minSdk 26, target/compileSdk 35 (bumped from 34).
- `SharedPreferences` file `"matrix"`, keys byte-identical (no migration).
- Rain keys global; HUD keys per-screen with `Lock` suffix; `order` global;
  `layouts` JSON snapshot; `ui_open_*`, `lastAppliedVersion`,
  `updatePromptDismissed` behavior unchanged.
- Termux `aapt2FromMavenOverride` absolute path kept.
- Release signing fallback (real key → generated `keystore/dev-release.jks`)
  translated to Kotlin DSL with identical behavior.
- `MatrixWallpaperService` + `RainRenderer` Canvas output pixel-identical for
  identical prefs. VT323 stays for rain/HUD.
- Intentionally lifts the old no-AndroidX / no-dependency rule. `AGENTS.md`
  and `README.md` must be updated to reflect AndroidX + Compose/Material3.

## 3. Approach (locked: A)

A — Compose Settings + `AndroidView` bridge (chosen). Keep wallpaper engine
untouched; rewrite Settings in Compose; embed existing `PreviewView`
(`SettingsActivity.kt:921-1059`) and `HuePicker` (`:1065-1161`) via
`AndroidView` for guaranteed preview parity.

Rejected: B — full Compose Canvas preview + custom hue drag. Purer but
re-implements scaling/dt/box math with no tests to catch drift; ~1.5x work.

YAGNI cuts: no navigation-compose, no ViewModel/Hilt/DataStore, no XML
layouts, no Expressive components for v1.

## 4. Build foundation (§1 approved)

- Delete Groovy scripts; add `settings.gradle.kts`, `build.gradle.kts`,
  `app/build.gradle.kts`, `gradle/libs.versions.toml`.
- `gradle.properties`: `android.useAndroidX=true`; keep `aapt2` override,
  `nonTransitiveRClass=true`, JDK 17 flags. No Jetifier.
- Updates resolved at implementation by building (floors, not blind pins):
  AGP 8.5.2 → 9.x stable (JDK 17 compatible), Gradle 8.7 → matching 8.10+,
  Kotlin 1.9.24 → 2.2.x with JetBrains Compose compiler plugin,
  compileSdk/targetSdk 34 → 35, minSdk stays 26.
- Deps via Compose BOM `2026.08.00` (Compose 1.12) + `material3`,
  `activity-compose`, `ui-tooling-preview`. Wallpaper engine gets no new deps.

## 5. Compose Settings structure (§2 approved)

- `SettingsActivity` → `ComponentActivity` + `setContent`: `Scaffold` +
  `TopAppBar`, HOME/LOCK `TabRow`.
- Single screen `LazyColumn`: Setup status card → Screen (preview + layouts)
  → HUD → Rain. Same control order as today.
- Mapping: Button→FilledTonalButton, CheckBox→Switch, SeekBar→Slider,
  EditText→OutlinedTextField, dialogs→AlertDialog, reorder rows→ListItem +
  IconButton. No navigation-compose, no XML.
- State: `matrix` prefs via `remember` + `mutableState`, rewritten on tab
  switch (replaces `refreshers`/`rebuildElements`).

## 6. Prefs + preview bridge (§3 approved)

- No key changes. `RainSettings.fromPrefs` stays single source for rain.
- Engine untouched, no Compose deps.
- Compose writes prefs on each change, calls `updatePreview()` into the
  embedded `PreviewView`. `setAnimating()` tied to lifecycle + SCREEN open.

## 7. Theme (§4 approved — Full Material You)

- Settings: `dynamicDarkColorScheme` / `dynamicLightColorScheme` on Android
  12+, Material3 baseline fallback on API 26–30. No green legacy in chrome.
- Wallpaper unaffected: rain hue picker remains source of truth; HUD/panel
  colors in `MatrixWallpaperService.kt:98-105`, rain colors in
  `RainRenderer.kt:99-119` unchanged. Settings typography Material3 default.

## 8. Verification (§5 approved)

- `./gradlew assembleDebug` + `assembleRelease` on Termux; signed output.
- Manual: set-wallpaper Both flow, HOME/LOCK switching, sliders/switches
  persist, layouts save/load/delete, redact-IP dialog, update prompt,
  preview animates only when SCREEN open.
- No new tests/lint/CI (repo has none; workflow is manual-only).
