# Compose Material3 Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild Settings in Compose Material3 on Kotlin DSL with updated dependencies, keeping wallpaper output identical.

**Architecture:** Phase 1 converts the build to `*.gradle.kts` + version catalog and rewrites `SettingsActivity` as Compose with an `AndroidView` bridge for the proven preview; Phases 2–3 follow only after Phase 1 ships.

**Tech Stack:** Kotlin 2.2.x, AGP 9.x (JDK 17), Compose BOM 2026.08.00 (Compose 1.12) + material3 + activity-compose, SharedPreferences `matrix` (Phase 3 migrates to DataStore).

**Spec:** `docs/superpowers/specs/2026-10-02-compose-material3-rewrite-design.md`

## Global Constraints

- Package `org.ghostrain`, minSdk 26, compileSdk/targetSdk 35, JDK 17.
- `matrix` prefs keys byte-identical in Phase 1–2; no migration until Phase 3.
- Termux `android.aapt2FromMavenOverride` absolute path kept.
- Release signing: real key from `local.properties`/env, else generate once `keystore/dev-release.jks`.
- Wallpaper Canvas output pixel-identical for identical prefs (Phases 1–2); Phase 3 dynamic HUD defaults OFF.
- Exact version pins resolved at Task 1 by building; floors: AGP 9.x stable, Gradle 8.10+, Kotlin 2.2.x, Compose BOM 2026.08.00.

## Review Focus

- Corrupted `layouts` JSON string must load as empty list, never crash — pinned in Task 2.
- `order` string with unknowns/duplicates/blanks must merge with defaults deterministically — pinned in Task 2.
- `rainMinLen > rainMaxLen` must clamp to min/max pair, never crash `respawn` — pinned in Task 2.
- Empty title string must hide the title line, not draw blank space — pinned in Task 5.
- Lock-screen redact toggle OFF must still show `[locked]` when locked and redaction is ON — pinned in Task 5.

---

### Task 1: Kotlin DSL build foundation

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Modify: `gradle.properties` (set `android.useAndroidX=true`)
- Delete: `settings.gradle`, `build.gradle`, `app/build.gradle`
- Test: build output only (`./gradlew assembleDebug`)

**Interfaces:**
- Consumes: nothing (first task)
- Produces: version catalog aliases `libs.plugins.*`, `libs.androidx.*`, `libs.compose.*` used by Tasks 2–7; `assembleDebug` green

- [ ] **Step 1: Write `gradle/libs.versions.toml` with floors (agp 9.x, kotlin 2.2.x, compose-bom 2026.08.00, material3, activity-compose, junit 4.13.2)**
- [ ] **Step 2: Write `settings.gradle.kts` (pluginManagement + dependencyResolutionManagement google()/mavenCentral(), `rootProject.name = "Ghost Rain"`, `include(":app")`)**
- [ ] **Step 3: Write root `build.gradle.kts` (alias plugins, no `dependencies {}` block)**
- [ ] **Step 4: Translate `app/build.gradle` signing block to Kotlin DSL verbatim behavior (`Properties`, env fallback, `exec("keytool", ...)`, `signingConfigs.release`)**
- [ ] **Step 5: Run and verify it passes**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` exists

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts gradle/libs.versions.toml gradle.properties settings.gradle build.gradle app/build.gradle
git commit -S -m "build: migrate to Kotlin DSL with version catalog and AndroidX"
```

### Task 2: Prefs single-source + JVM unit tests

**Files:**
- Create: `app/src/main/java/org/ghostrain/HudPrefs.kt`, `app/src/main/java/org/ghostrain/LayoutsRepo.kt`, `app/src/test/java/org/ghostrain/PrefsLogicTest.kt`
- Modify: `app/build.gradle.kts` (add `testImplementation("junit:junit:4.13.2")`), `MatrixWallpaperService.kt` + Settings screen to consume `HudPrefs` (wiring only, no behavior change)
- Test: `app/src/test/java/org/ghostrain/PrefsLogicTest.kt`

**Interfaces:**
- Consumes: catalog aliases from Task 1
- Produces: `HudPrefs.keyOf(base: String, editingLock: Boolean): String`, `HudPrefs.orderKeys(orderRaw: String?): List<String>`, `HudLines.titleOrNull(raw: String?): String?` (null when blank), `LayoutsRepo.load(raw: String?): List<LayoutSnapshot>`, `LayoutsRepo.serialize(list: List<LayoutSnapshot>): String`, `data class LayoutSnapshot(val name: String, val bools: Map<String, Boolean>, val ints: Map<String, Int>, val title: String)`, `RainSettings.fromMap(m: Map<String, Int>): RainSettings` (same defaults/clamping as `fromPrefs`) used by Tasks 4–5; `RainSettings.fromPrefs` unchanged

- [ ] **Step 1: Write the failing tests (pure JVM, no Android)**

```kotlin
@Test fun corruptedLayoutsLoadAsEmpty() {
    assertEquals(0, LayoutsRepo.load("not-json").size)
}
@Test fun orderMergesUnknownsAndDedupes() {
    assertEquals(listOf("net", "title", "ram", "disk", "bat", "cpu", "up"),
        HudPrefs.orderKeys("net,,net,bogus"))
}
@Test fun rainLenClampSwapsMinMax() {
    val s = RainSettings.fromMap(mapOf("rainMinLen" to 40, "rainMaxLen" to 6))
    assertTrue(s.minLen <= s.maxLen)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL (classes under test do not exist yet)

- [ ] **Step 3: Implement `HudPrefs` + `HudLines` + `LayoutsRepo` + `RainSettings.fromMap` shim (delegates to the same defaults as `fromPrefs`) in the exact files above**
- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/ghostrain/HudPrefs.kt app/src/main/java/org/ghostrain/LayoutsRepo.kt app/src/test/java/org/ghostrain/PrefsLogicTest.kt app/build.gradle.kts
git commit -S -m "feat: centralize HUD prefs with unit tests"
```

### Task 3: Compose host + Theme + navigation skeleton

**Files:**
- Create: `app/src/main/java/org/ghostrain/Theme.kt`, `app/src/main/java/org/ghostrain/SettingsScreen.kt`
- Modify: `app/src/main/java/org/ghostrain/SettingsActivity.kt` (become `ComponentActivity` with `setContent`, keep legacy `PreviewView`/`HuePicker` inner classes for the bridge)
- Test: manual launch

**Interfaces:**
- Consumes: `HudPrefs`/`LayoutsRepo` from Task 2
- Produces: `GhostRainTheme(content: @Composable () -> Unit)`, `SettingsScreen()` skeleton with `Scaffold` + `TopAppBar` + HOME/LOCK `TabRow` + empty `LazyColumn` sections, consumed by Tasks 4–6

- [ ] **Step 1: Implement `GhostRainTheme` (`dynamicDarkColorScheme`/`dynamicLightColorScheme` on API 31+, baseline fallback below)**
- [ ] **Step 2: Implement `SettingsScreen` skeleton with tab state (`editingLock: Boolean`) preserved across rotation via `rememberSaveable`**
- [ ] **Step 3: Run and verify it passes**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; app launches, tabs switch, no crashes

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/ghostrain/Theme.kt app/src/main/java/org/ghostrain/SettingsScreen.kt app/src/main/java/org/ghostrain/SettingsActivity.kt
git commit -S -m "feat: compose settings host with Material You theme"
```

### Task 4: Setup + Screen sections with preview bridge

**Files:**
- Modify: `app/src/main/java/org/ghostrain/SettingsScreen.kt`, `app/src/main/java/org/ghostrain/PreviewBridge.kt` (create)
- Test: manual

**Interfaces:**
- Consumes: `SettingsScreen` skeleton + `LayoutsRepo` from Tasks 2–3
- Produces: `SetupCard()`, `ScreenSection()` with `AndroidView`-embedded `PreviewView`, `LayoutsRow()` used by the final screen

- [ ] **Step 1: Implement status card logic (setup instructions when wallpaper inactive, update prompt when version changed, else hidden) with `Scaffold` snackbar host**
- [ ] **Step 2: Implement `PreviewBridge` (`AndroidView` wrapping legacy `PreviewView`, `set(matrix prefs)` + `setAnimating` tied to lifecycle) inside a `Card`**
- [ ] **Step 3: Implement layouts row (load picker dialog, New, Save as, Delete with confirm) writing the same `layouts` JSON**
- [ ] **Step 4: Run and verify it passes**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; preview animates, layouts round-trip, status card correct in all three states

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/ghostrain/SettingsScreen.kt app/src/main/java/org/ghostrain/PreviewBridge.kt
git commit -S -m "feat: setup and screen sections with preview bridge"
```

### Task 5: HUD section

**Files:**
- Modify: `app/src/main/java/org/ghostrain/SettingsScreen.kt`
- Test: manual + `PrefsLogicTest` cases for empty-title and redact behavior live here

**Interfaces:**
- Consumes: `HudPrefs.keyOf`, `orderKeys` from Task 2; `ScreenSection` state from Task 4
- Produces: `HudSection()` complete (title field, position/size sliders, element switches + reorder, redact-IP confirm dialog)

- [ ] **Step 1: Extend tests for empty-title and redact expectations**

```kotlin
@Test fun emptyTitleHidesLine() { assertNull( HudLines.titleOrNull("") ) }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL (`HudLines` does not exist yet)

- [ ] **Step 3: Implement `HudSection` (`OutlinedTextField`, `Slider` 0–100/50–200, `Switch` rows in `ListItem` + ▲▼ `IconButton`s, redact `AlertDialog`) writing per-screen `Lock`-suffixed keys**
- [ ] **Step 4: Run tests and build to verify they pass**

Run: `./gradlew :app:testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL; HOME/LOCK values independent; empty title hides line

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/ghostrain/SettingsScreen.kt app/src/test/java/org/ghostrain/PrefsLogicTest.kt
git commit -S -m "feat: HUD section with per-screen prefs"
```

### Task 6: Rain section + polish

**Files:**
- Modify: `app/src/main/java/org/ghostrain/SettingsScreen.kt`
- Test: manual

**Interfaces:**
- Consumes: `RainSettings.fromPrefs` (unchanged), `HuePicker` legacy view via `AndroidView`
- Produces: complete Settings screen (rain sliders, glyph switches, presets, edge-to-edge, accessibility labels, Snackbar Undo on reset)

- [ ] **Step 1: Implement rain sliders + glyph `Switch`es + Classic/Amber/Ice presets (write `rainHue` + `shimmer` only)**
- [ ] **Step 2: Implement Reset actions with `Snackbar` Undo, 48dp targets, content descriptions, edge-to-edge + predictive back**
- [ ] **Step 3: Run and verify it passes**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; rain changes reflect in preview instantly; resets offer Undo

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/ghostrain/SettingsScreen.kt
git commit -S -m "feat: rain section with presets and Material polish"
```

### Task 7: Release hygiene

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode`/`versionName` bump), `AGENTS.md`, `README.md`, `fastlane/metadata/android/en-US/*` (screenshots + changelog)
- Test: `./gradlew assembleRelease`

**Interfaces:**
- Consumes: all Tasks 1–6
- Produces: shippable signed `app-release.apk`, docs reflecting AndroidX + Compose

- [ ] **Step 1: Bump versions, update `AGENTS.md` (AndroidX allowed, Compose deps, programmatic-Compose UI rule) and `README.md` (framework + Compose/Material)**
- [ ] **Step 2: Run and verify it passes**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL, signed APK at `app/build/outputs/apk/release/`

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts AGENTS.md README.md fastlane/metadata/android/en-US
git commit -S -m "chore: release bump and docs for Compose rewrite"
```

### Task 8: Phase 2 — Compose Canvas preview (after Phase 1 ships)

**Files:**
- Create: `app/src/main/java/org/ghostrain/RainPreview.kt`, `app/src/main/java/org/ghostrain/HueBar.kt`
- Modify: `PreviewBridge.kt` (swap `AndroidView` for new composables), delete legacy `PreviewView`/`HuePicker` from `SettingsActivity.kt`
- Test: side-by-side parity screenshots

**Interfaces:**
- Consumes: `RainSettings`, `RainRenderer` (unchanged engine)
- Produces: `RainPreview(prefs, modifier)`, `HueBar(hue, onHue)` with identical scaling/dt/box math

- [ ] **Step 1: Implement `RainPreview` (virtual-screen scale, wall-clock dt, HUD box measure, VT323) and `HueBar` (0–360 drag, persist `rainHue`)**
- [ ] **Step 2: Run parity check (same prefs on Phase 1 vs Phase 2 builds, screenshots match by eye within 1px)**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; parity screenshots approved

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/ghostrain/RainPreview.kt app/src/main/java/org/ghostrain/HueBar.kt app/src/main/java/org/ghostrain/PreviewBridge.kt app/src/main/java/org/ghostrain/SettingsActivity.kt
git commit -S -m "feat: pure Compose preview and hue bar"
```

### Task 9: Phase 3 — DataStore, dynamic HUD, network expansion (after Phase 2)

**Files:**
- Create: `app/src/main/java/org/ghostrain/MatrixDataStore.kt`
- Modify: engine + screen to read via repository with one-time `matrix` migration; `SettingsScreen.kt` (new toggle + NET detail)
- Test: migration + toggle checks

**Interfaces:**
- Consumes: all Phase 1–2 repositories
- Produces: migrated preferences, `Match system color` per-screen toggle (default OFF), extended NET line (transport + SSID + signal, existing permissions only)

- [ ] **Step 1: Implement one-time SharedPreferences→DataStore migration preserving every key/value**
- [ ] **Step 2: Implement opt-in dynamic HUD toggle and extended NET line**
- [ ] **Step 3: Run and verify they pass**

Run: `./gradlew :app:testDebugUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL; old installs keep values; toggle OFF output identical

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/ghostrain/MatrixDataStore.kt app/src/main/java/org/ghostrain/SettingsScreen.kt
git commit -S -m "feat: datastore migration with dynamic HUD and network detail"
```
