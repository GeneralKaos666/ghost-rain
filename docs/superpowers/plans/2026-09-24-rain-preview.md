# Animated Rain Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Settings `PreviewView` show a live, animated rain that is rendered by the exact same code path as the live wallpaper.

**Architecture:** Extract the engine's rain drawing into a shared `RainRenderer` (new `RainRenderer.kt`) driven by a plain `RainSettings` holder. `MatrixEngine` and `PreviewView` each own an instance; the preview runs the renderer at virtual screen size and scales the canvas into its box. The preview animates only while the SCREEN section is expanded and the activity is resumed.

**Tech Stack:** Kotlin, Android framework only (no AndroidX, no third-party libraries), Gradle/AGP 8.5.2, minSdk 26, compileSdk 34, JDK 17.

**Spec:** `docs/superpowers/specs/2026-09-24-rain-preview-design.md`

## Global Constraints

- **Framework-only:** no AndroidX, no third-party dependencies, no new permissions. Use only `android.*`, `java.*`, Kotlin stdlib.
- **No test infrastructure exists and none may be added** (adding a test runner means adding a dependency, which the project forbids). Every task's verification is therefore: (a) the module builds, and (b) the stated manual check on a device. Do not write placeholder unit tests.
- Build commands: `./gradlew assembleDebug` and `./gradlew assembleRelease`. Both must pass at every commit.
- Do not change pref keys, the manifest, or wallpaper behavior. `RainSettings.fromPrefs` must read the same keys and defaults the engine uses today.
- Keep the moved rain draw code byte-for-byte identical in behaviour (including the `drawText(char[], …)` overload and the precomputed `brightness` ramp from D-perf).
- `AGENTS.md` is gitignored; keep it updated but do not `git add` it.

## Review Focus

- **Rain setting changed while the preview is animating** (speed/hue/shimmer/glyph sets): must apply live on the next frame with **no** resize and no visible stall.
- **Font-size change while previewing**: changes `cell`/`cols`, so the preview renderer must be resized; a missed resize would draw with stale geometry or blank.
- **Collapsing SCREEN or backgrounding the app**: the animation loop must fully stop — no `Handler` callbacks left pending.
- **Reopening Settings with SCREEN previously collapsed**: must not animate until the section is expanded again.
- **Preview fidelity**: must show the whole wallpaper composition (scaled), not a narrow slice, and match the home screen.

---

### Task 1: Add the shared `RainRenderer` and `RainSettings`

**Files:**
- Create: `app/src/main/java/org/ghostrain/RainRenderer.kt`

**Interfaces:**
- Consumes: nothing (new leaf component).
- Produces:
  - `data class RainSettings(speedMul: Float, hue: Int, fontSizeMul: Float, glyphKatakana: Boolean, glyphDigits: Boolean, glyphLatin: Boolean, glyphSymbols: Boolean, minLen: Int, maxLen: Int, shimmer: Float, frameDelayMs: Int)` with `companion object { fun fromPrefs(p: SharedPreferences): RainSettings }`.
  - `class RainRenderer(density: Float)` with `val cell: Float`, `fun resize(w: Int, h: Int, s: RainSettings)`, `fun draw(c: Canvas, dt: Float, s: RainSettings)`.

- [ ] **Step 1: Create the file with the full component**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Rain configuration shared by the live wallpaper engine and the Settings preview,
 * so both render from exactly the same values. Single source of rain pref keys and
 * defaults.
 */
data class RainSettings(
        val speedMul: Float,
        val hue: Int,
        val fontSizeMul: Float,
        val glyphKatakana: Boolean,
        val glyphDigits: Boolean,
        val glyphLatin: Boolean,
        val glyphSymbols: Boolean,
        val minLen: Int,
        val maxLen: Int,
        val shimmer: Float,
        val frameDelayMs: Int) {

    companion object {
        fun fromPrefs(p: SharedPreferences): RainSettings {
            val rawMin = p.getInt("rainMinLen", 6).coerceIn(1, 50)
            val rawMax = p.getInt("rainMaxLen", 32).coerceIn(1, 50)
            val fps = p.getInt("rainFps", 30).coerceIn(10, 60)
            return RainSettings(
                    speedMul = p.getInt("rainSpeed", 100) / 100f,
                    hue = p.getInt("rainHue", 120).coerceIn(0, 360),
                    fontSizeMul = p.getInt("rainFontSize", 100) / 100f,
                    glyphKatakana = p.getBoolean("glyphKatakana", true),
                    glyphDigits = p.getBoolean("glyphDigits", true),
                    glyphLatin = p.getBoolean("glyphLatin", true),
                    glyphSymbols = p.getBoolean("glyphSymbols", true),
                    minLen = minOf(rawMin, rawMax),
                    maxLen = maxOf(rawMin, rawMax),
                    shimmer = p.getInt("shimmer", 60) / 100f,
                    frameDelayMs = (1000f / fps).roundToInt().coerceIn(16, 100))
        }
    }
}

/**
 * Stateful matrix-rain renderer. Owns per-column state and draws onto any Canvas.
 * The wallpaper drives it at full screen size; the Settings preview drives it at the
 * virtual screen size and scales the canvas down, so both look identical.
 */
class RainRenderer(private val density: Float) {

    private val rnd = Random()
    private val latin = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray()   // no I/O (look like 1/0)
    private val symbols = "+=<>/\\|[]{}#$%&*".toCharArray()

    private val rain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE   // fallback covers katakana
    }
    private val hsvTemp = FloatArray(3)

    private var w = 0
    private var h = 0
    var cell = 0f
        private set
    private var cols = 0

    private var headY = FloatArray(0)
    private var speed = FloatArray(0)
    private var len = IntArray(0)
    private var cellGlyphs: Array<CharArray>? = null
    private var brightness: Array<FloatArray>? = null

    /** Recompute geometry and respawn every column. Call on size or font-size change. */
    fun resize(width: Int, height: Int, s: RainSettings) {
        w = width
        h = height
        cell = density * 13f * s.fontSizeMul
        rain.textSize = cell * 0.92f
        cols = maxOf(1, (w / cell).roundToInt())
        headY = FloatArray(cols)
        speed = FloatArray(cols)
        len = IntArray(cols)
        cellGlyphs = Array(cols) { CharArray(0) }
        brightness = Array(cols) { FloatArray(0) }
        for (i in 0 until cols) respawn(i, true, s)
    }

    /** Advance and draw one frame. */
    fun draw(c: Canvas, dt: Float, s: RainSettings) {
        val glyphs = cellGlyphs ?: return
        val brights = brightness ?: return
        hsvTemp[0] = s.hue.toFloat()
        hsvTemp[1] = 0.30f
        hsvTemp[2] = 1.0f
        val leadColor = Color.HSVToColor(255, hsvTemp)
        for (i in 0 until cols) {
            headY[i] += speed[i] * dt                   // FALL
            if (headY[i] - len[i] * cell > h) respawn(i, false, s)

            val x = i * cell
            val columnGlyphs = glyphs[i]
            val columnBright = brights[i]
            for (j in 0 until columnGlyphs.size) {
                if (rnd.nextFloat() < s.shimmer) columnGlyphs[j] = randGlyph(s)  // SHIMMER
                val y = headY[i] - j * cell
                if (y < -cell || y > h + cell) continue
                if (j == 0) {
                    rain.color = leadColor
                } else {
                    hsvTemp[1] = 1.0f
                    hsvTemp[2] = if (j < columnBright.size) columnBright[j] else 0.08f
                    rain.color = Color.HSVToColor(255, hsvTemp)
                }
                c.drawText(columnGlyphs, j, 1, x, y, rain)
            }
        }
    }

    private fun randGlyph(s: RainSettings): Char {
        val wKata = if (s.glyphKatakana) 40 else 0
        val wDig = if (s.glyphDigits) 25 else 0
        val wLat = if (s.glyphLatin) 23 else 0
        val wSym = if (s.glyphSymbols) 12 else 0
        val total = wKata + wDig + wLat + wSym
        if (total == 0) return ' '   // fallback — nothing enabled
        var r = rnd.nextInt(total)
        if (s.glyphKatakana) {
            if (r < wKata) return (0x30A1 + rnd.nextInt(83)).toChar()
            r -= wKata
        }
        if (s.glyphDigits) {
            if (r < wDig) return ('0'.code + rnd.nextInt(10)).toChar()
            r -= wDig
        }
        if (s.glyphLatin) {
            if (r < wLat) return latin[rnd.nextInt(latin.size)]
            r -= wLat
        }
        return symbols[rnd.nextInt(symbols.size)]
    }

    private fun respawn(i: Int, initial: Boolean, s: RainSettings) {
        len[i] = s.minLen + rnd.nextInt(s.maxLen - s.minLen + 1)
        cellGlyphs!![i] = CharArray(len[i]) { randGlyph(s) }
        // Precompute this column's brightness falloff once, so the frame loop
        // never calls Math.pow per glyph.
        val n = len[i]
        val denom = maxOf(n - 1, 1).toFloat()
        val b = FloatArray(n)
        for (j in 0 until n) {
            b[j] = maxOf(0.08f, (1 - j / denom).toDouble().pow(1.4).toFloat())
        }
        brightness!![i] = b
        speed[i] = h * (0.12f + rnd.nextFloat() * 0.42f) * s.speedMul
        headY[i] = if (initial) {
            rnd.nextFloat() * (h + len[i] * cell) - len[i] * cell
        } else {
            -len[i] * cell - if (rnd.nextFloat() < 0.35f) rnd.nextFloat() * h else 0f
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. The new file is not yet referenced; it only needs to compile.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/ghostrain/RainRenderer.kt
git commit -m "refactor: add shared RainRenderer and RainSettings"
```

---

### Task 2: Make `MatrixEngine` delegate to `RainRenderer`

**Files:**
- Modify: `app/src/main/java/org/ghostrain/MatrixWallpaperService.kt`

**Interfaces:**
- Consumes: `RainSettings`, `RainRenderer` from Task 1.
- Produces: an engine that renders rain through the shared renderer; `rainRenderer.cell` remains available for HUD sizing.

- [ ] **Step 1: Remove the now-unused imports**

Delete these three lines from the import block (they were only used by the rain code moving out):
`import android.graphics.Color`, `import kotlin.math.pow`, `import kotlin.math.roundToInt`.
Keep `android.graphics.Paint` and `android.graphics.Typeface` (still used by HUD/VT323).

- [ ] **Step 2: Replace the rain fields with a renderer and settings**

In `inner class MatrixEngine`, delete these field declarations:
`rnd`, `cols`, `cell`, `headY`, `speed`, `len`, `cellGlyphs`, `brightness`, `latin`, `symbols`, `rain`, `hsvTemp`, `shimmerP`, `rainSpeedMul`, `rainHue`, `rainFontSizeMul`, `glyphKatakana`, `glyphDigits`, `glyphLatin`, `glyphSymbols`, `rainMinLen`, `rainMaxLen`, and `frameDelay`.
Remove `import java.util.Random` as well (no longer used).

Add in their place:

```kotlin
        private lateinit var rainRenderer: RainRenderer
        private lateinit var rainSettings: RainSettings
```

- [ ] **Step 3: Initialise the renderer in `onCreate`**

In `onCreate`, delete the glyph-table setup (`latin = …`, `symbols = …`) and the `rain = Paint(...).apply { … }` block. Before `prefs = getSharedPreferences(...)`, add:

```kotlin
            rainRenderer = RainRenderer(resources.displayMetrics.density)
```

- [ ] **Step 4: Simplify `readPrefs` and the pref-change listener**

Replace the body of `readPrefs()` so it only reads HUD values plus builds the settings:

```kotlin
        private fun readPrefs() {
            showHud = prefs.getBoolean("hud", true)
            showHudLock = prefs.getBoolean("hudLock", true)
            hudPos = prefs.getInt("hudPos", 50) / 100f
            hudX = prefs.getInt("hudX", 50) / 100f
            hudScale = prefs.getInt("hudScale", 100) / 100f
            hudPosLock = prefs.getInt("hudPosLock", 50) / 100f
            hudXLock = prefs.getInt("hudXLock", 50) / 100f
            hudScaleLock = prefs.getInt("hudScaleLock", 100) / 100f
            rainSettings = RainSettings.fromPrefs(prefs)
        }
```

Replace `onSharedPreferenceChanged` so the font-size path resizes the renderer:

```kotlin
        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            readPrefs()
            if ("rainFontSize" == key && w > 0 && h > 0) rainRenderer.resize(w, h, rainSettings)
        }
```

- [ ] **Step 5: Replace `initColumns` and the surface handler**

Delete the whole `initColumns()` method. Replace `onSurfaceChanged` with:

```kotlin
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width
            h = height
            rainRenderer.resize(w, h, rainSettings)
        }
```

- [ ] **Step 6: Delegate the rain drawing and frame pacing**

In `drawFrame`, replace the entire `val glyphs = cellGlyphs … }` block (everything between `c.drawColor(0xFF000000.toInt())` and the `val locked = …` line) with:

```kotlin
                rainRenderer.draw(c, dt, rainSettings)
```

In the `frame` Runnable, replace `frameDelay.toLong()` with `rainSettings.frameDelayMs.toLong()`.

- [ ] **Step 7: Point HUD sizing at the renderer's cell**

In `drawHud`, change `val size = cell * 1.05f * uScale` to `val size = rainRenderer.cell * 1.05f * uScale`.

- [ ] **Step 8: Build and confirm no stale references**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` with no "unresolved reference" errors (proves `cell`/`rnd`/`initColumns` are gone everywhere).

- [ ] **Step 9: Manual check — wallpaper is visually unchanged**

Install `app/build/outputs/apk/debug/app-debug.apk`, set Ghost Rain, and confirm the rain still falls, shimmers, respawns at the top, and respects speed/hue/font-size. This is the regression gate for the move.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/org/ghostrain/MatrixWallpaperService.kt
git commit -m "refactor: render wallpaper rain via shared RainRenderer"
```

---

### Task 3: Render rain in the Settings preview

**Files:**
- Modify: `app/src/main/java/org/ghostrain/SettingsActivity.kt`

**Interfaces:**
- Consumes: `RainSettings`, `RainRenderer` from Task 1.
- Produces: `PreviewView` gains `fun setRain(s: RainSettings)`; `updatePreview()` feeds it.

- [ ] **Step 1: Add imports**

Add to the import block:

```kotlin
import android.os.Handler
import android.os.Looper
```

- [ ] **Step 2: Give `PreviewView` a renderer and settings**

Inside `inner class PreviewView`, add these fields (near the other private fields):

```kotlin
        private val rainRenderer = RainRenderer(resources.displayMetrics.density)
        private var rainSettings: RainSettings? = null
        private var lastFontSizeMul = -1f
```

- [ ] **Step 3: Add `setRain`**

Add this method next to `set(...)`:

```kotlin
        fun setRain(s: RainSettings) {
            rainSettings = s
            if (s.fontSizeMul != lastFontSizeMul) {
                lastFontSizeMul = s.fontSizeMul
                rainRenderer.resize(sw, sh, s)
            }
            invalidate()
        }
```

- [ ] **Step 4: Draw the scaled rain behind the HUD**

In `onDraw`, immediately after the two border rects (`c.drawRect(left, top, left + rw, top + rh, screen)` and `…, border)`) and before `if (lines.isEmpty()) return`, insert:

```kotlin
            val rs = rainSettings
            if (rs != null) {
                val pScale = rw / sw
                val save = c.save()
                c.translate(left, top)
                c.scale(pScale, pScale)
                rainRenderer.draw(c, rs.frameDelayMs / 1000f, rs)
                c.restoreToCount(save)
            }
```

- [ ] **Step 5: Feed settings from `updatePreview`**

At the end of `updatePreview()`, after `preview.set(...)`, add:

```kotlin
        preview.setRain(RainSettings.fromPrefs(p))
```

- [ ] **Step 6: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Manual check — fidelity and live updates**

Open Settings. The preview must show rain behind the HUD, at the same glyph density/colour as the home screen (full composition, not a narrow slice). Drag **Rain speed**, **Rain color hue**, **Glyph font size** (this one resizes), and toggle glyph sets — each must change the preview. This covers Review Focus items 1 and 5.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/ghostrain/SettingsActivity.kt
git commit -m "feat: render rain in the settings preview"
```

---

### Task 4: Animate the preview only while visible and resumed

**Files:**
- Modify: `app/src/main/java/org/ghostrain/SettingsActivity.kt`

**Interfaces:**
- Consumes: `PreviewView` from Task 3.
- Produces: `PreviewView.setAnimating(on: Boolean)`; `section(...)` gains an optional `onToggle: ((Boolean) -> Unit)?` parameter.

- [ ] **Step 1: Add the animation loop to `PreviewView`**

Add these fields inside `inner class PreviewView`:

```kotlin
        private val handler = Handler(Looper.getMainLooper())
        private var animating = false
        private val frame = object : Runnable {
            override fun run() {
                invalidate()
                if (animating) handler.postDelayed(this, (rainSettings?.frameDelayMs ?: 33).toLong())
            }
        }
```

Add the control method and detach cleanup:

```kotlin
        fun setAnimating(on: Boolean) {
            if (on == animating) return
            animating = on
            handler.removeCallbacks(frame)
            if (on) handler.post(frame)
        }

        override fun onDetachedFromWindow() {
            handler.removeCallbacks(frame)
            animating = false
            super.onDetachedFromWindow()
        }
```

- [ ] **Step 2: Add the `onToggle` hook to `section(...)`**

Change the `section` signature to:

```kotlin
    private fun section(parent: LinearLayout, title: String, key: String, defaultOpen: Boolean,
                        onReset: (() -> Unit)? = null, onToggle: ((Boolean) -> Unit)? = null,
                        build: (LinearLayout) -> Unit) {
```

Inside the header click handler, after `text = headerText(title, nowOpen)`, add:

```kotlin
                onToggle?.invoke(nowOpen)
```

- [ ] **Step 3: Track resumed + section state on the activity**

Add fields:

```kotlin
    private var resumed = false
    private var screenOpen = true
```

- [ ] **Step 4: Wire the SCREEN section toggle**

Change the SCREEN section call to pass `onToggle`:

```kotlin
        section(root, "SCREEN (HOME / LOCK)", "screen", true,
                onToggle = { open -> screenOpen = open; preview.setAnimating(open && resumed) }) { c ->
```

Immediately after that `section(...)` call, sync the initial state from prefs:

```kotlin
        screenOpen = p.getBoolean("ui_open_screen", true)
```

- [ ] **Step 5: Drive animation from the activity lifecycle**

Replace the existing `onResume` with:

```kotlin
    override fun onResume() {
        super.onResume()
        resumed = true
        if (::banner.isInitialized) refreshBanner()
        preview.setAnimating(screenOpen)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        preview.setAnimating(false)
    }
```

- [ ] **Step 6: Build both variants**

Run: `./gradlew assembleDebug && ./gradlew assembleRelease`
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 7: Manual check — lifecycle**

With Settings open: rain animates. Collapse **SCREEN**: animation stops. Expand it: resumes. Background the app (Home) and reopen: animates only if SCREEN is expanded. Reopen after having collapsed SCREEN earlier: it stays collapsed and does not animate. This covers Review Focus items 2, 3, and 4.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/ghostrain/SettingsActivity.kt
git commit -m "feat: animate settings preview only while visible and resumed"
```

---

### Task 5: Update `AGENTS.md`

**Files:**
- Modify: `AGENTS.md` (gitignored — do not commit)

**Interfaces:**
- Consumes: all prior tasks.
- Produces: accurate repo guidance.

- [ ] **Step 1: Add the shared renderer to the Architecture section**

Under the `MatrixWallpaperService.kt` bullet, add a bullet:

```markdown
- `RainRenderer.kt` — shared rain renderer + `RainSettings` holder. The wallpaper engine and
  the Settings `PreviewView` each own an instance; `RainSettings.fromPrefs` is the single source
  of rain pref keys/defaults. The preview runs it at virtual screen size and scales the canvas,
  and animates only while the SCREEN section is expanded and the activity is resumed.
```

- [ ] **Step 2: Note the settings-preview animation rule**

Append to the existing App-level prefs bullet:

```markdown
  The preview animation is driven by `PreviewView.setAnimating(...)`, gated on `ui_open_screen`
  and the activity lifecycle.
```

- [ ] **Step 3: Verify the file reads correctly**

Run: `sed -n '24,60p' AGENTS.md`
Expected: the new bullets appear once, with no duplicated or broken sentences.

---

## Self-Review

**Spec coverage:**
- `RainSettings` + `fromPrefs` single source → Task 1.
- `RainRenderer.resize/draw`, moved D-perf code → Task 1.
- Engine delegation, `cell` for HUD, `frameDelayMs` pacing → Task 2.
- Preview virtual-size scaling and live settings → Task 3.
- Visible-&-resumed lifecycle, `section` onToggle, `ui_open_screen` sync → Task 4.
- `AGENTS.md` update → Task 5.
- Non-goals (HUD unification, scroll-off, pref/manifest changes) → no task, by design.

**Placeholder scan:** No TBD/TODO; every code step contains full code.

**Type consistency:** `RainSettings` field names and `RainRenderer.resize/draw/cell` signatures are identical across Tasks 1–4. `setRain`, `setAnimating`, `onToggle`, `screenOpen`, `resumed` are defined before use.

**Review Focus:** Items 1 & 5 → Task 3 Step 7; items 2, 3, 4 → Task 4 Step 7.
