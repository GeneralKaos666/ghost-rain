# Animated Rain Preview in Settings — Design

Date: 2026-09-24
Status: Approved for planning
Sub-project: D-preview (follows A, B, D-perf)

## Problem

The Settings screen's `PreviewView` shows only the HUD layout on a black rectangle.
Rain settings (speed, hue, glyph size, glyph sets, column length, shimmer, frame
rate) can only be judged by setting the wallpaper and looking at the home screen.
Users edit blind.

## Goal

Add a live, animated rain preview to the Settings screen that is **pixel-faithful**
to the real wallpaper, and that animates only while it is actually visible.

## Non-goals

- Unifying HUD drawing between the engine and the preview (see approach C below).
- Detecting the preview being scrolled out of view.
- Any change to live-wallpaper behavior, prefs, manifest, or permissions.
- New dependencies (the project is framework-only).

## Approaches considered

- **A — `RainRenderer` + `RainSettings` (chosen).** Extract the rain drawing into a
  shared class that both the wallpaper engine and the Settings preview instantiate.
  Settings are passed in via a plain data holder. Clean isolation; the rain is
  literally the same code path in both places.
- **B — Renderer reads `SharedPreferences` itself.** Less wiring, but couples the
  renderer to prefs and makes it harder to reason about and test. Rejected.
- **C — Render the whole wallpaper to an offscreen bitmap at phone size, then scale
  it into the preview.** Maximum fidelity (would also share HUD drawing), but costs a
  full-size bitmap per frame and is the largest refactor. Rejected as not worth the
  memory/CPU for the HUD portion, which drifts far less than the rain.

## Components

### New file: `app/src/main/java/org/ghostrain/RainRenderer.kt`

**`data class RainSettings`**

Fields:

- `speedMul: Float` — from `rainSpeed / 100`
- `hue: Int` — `rainHue`
- `fontSizeMul: Float` — from `rainFontSize / 100`
- `glyphKatakana`, `glyphDigits`, `glyphLatin`, `glyphSymbols: Boolean`
- `minLen`, `maxLen: Int` — each coerced to `1..50`, then normalised so `minLen <= maxLen`
- `shimmer: Float` — `shimmer / 100`, in `0..1`
- `frameDelayMs: Int` — `(1000 / rainFps).roundToInt().coerceIn(16, 100)`

`companion object fun fromPrefs(p: SharedPreferences): RainSettings` — the single
source of rain defaults and pref-key names. Both the engine and the preview use it,
so their rain configuration cannot drift.

**`class RainRenderer(density: Float)`**

Owns all per-column rain state currently in `MatrixEngine`:

- `w`, `h`, `cell`, `cols`
- `headY: FloatArray`, `speed: FloatArray`, `len: IntArray`
- `cellGlyphs: Array<CharArray>`, `brightness: Array<FloatArray>`
- the rain `Paint` (MONOSPACE), `latin`/`symbols` glyph tables, `Random`

API:

- `fun resize(w: Int, h: Int, settings: RainSettings)` — recompute `cell` and `cols`,
  (re)allocate the column arrays, and respawn every column.
- `fun draw(canvas: Canvas, dtSeconds: Float, settings: RainSettings)` — advance each
  column (fall), respawn when off-screen, shimmer, and draw. This is the existing
  `MatrixEngine` inner loop moved verbatim, including the D-perf optimisations
  (`Canvas.drawText(char[], index, count, …)` and the precomputed `brightness` ramp).

### `MatrixWallpaperService.kt` changes

- Remove the rain fields and methods now owned by `RainRenderer` (`latin`, `symbols`,
  `headY`, `speed`, `len`, `cellGlyphs`, `brightness`, `cell`, `cols`, `randGlyph`,
  `respawn`, the rain part of `initColumns`, the rain `Paint`).
- Hold one `RainRenderer` and a `rainSettings: RainSettings`.
- `readPrefs()` builds `rainSettings` via `RainSettings.fromPrefs(prefs)`.
- `onSurfaceChanged` / font-size pref change call `rainRenderer.resize(w, h, rainSettings)`.
- `drawFrame()` calls `rainRenderer.draw(c, dt, rainSettings)` for the rain, then draws
  the HUD as today.
- `drawHud()` reads `rainRenderer.cell` for HUD sizing.
- Frame pacing uses `rainSettings.frameDelayMs`.

### `SettingsActivity.kt` changes

- `PreviewView` owns a `RainRenderer`.
- `updatePreview()` (already invoked by every control) also calls
  `RainSettings.fromPrefs(p)` and passes it to the preview.
- `PreviewView` calls `rainRenderer.resize(...)` whenever its own size changes or
  `fontSizeMul` differs from the last resize (font size changes `cell`, hence `cols`);
  other rain changes (speed, hue, shimmer, glyph sets) need no resize because `draw`
  receives the latest `RainSettings` each frame.
- `PreviewView.setAnimating(active: Boolean)` starts/stops a `Handler` loop paced by
  `frameDelayMs`.
- Activity drives `setAnimating(sectionOpen && resumed)`:
  - `onResume` / `onPause`
  - a new optional `onToggle: ((Boolean) -> Unit)?` parameter on `section(...)`, wired
    for the SCREEN section so collapsing it stops the preview.

## Fidelity and sizing

The preview must show the whole wallpaper composition, not a narrow slice. Therefore
the renderer runs at the virtual phone size `sw × sh` (from `resources.displayMetrics`)
and `PreviewView.onDraw` scales the canvas by `pScale = rw / sw` into the letterboxed
preview rectangle (the existing `aspect` logic already computes `rw`/`rh`).

Draw order in `onDraw`: screen background → border → **scaled rain** → existing HUD
preview box and lines. The HUD preview rendering is unchanged.

## Lifecycle

- Animates only while `sectionOpen && activityResumed`.
- Frame delay comes from `rainSettings.frameDelayMs` (same 16–100 ms clamp as the engine).
- `stop()` removes pending handler callbacks; no timers run in the background.

## Compatibility

- No pref keys, manifest entries, or permissions change. Existing saved settings are
  read exactly as before.
- `AGENTS.md` is updated to describe the new shared `RainRenderer` and the
  engine/preview relationship.

## Verification

- `./gradlew assembleDebug` and `./gradlew assembleRelease` (R8 + lintVital) must pass.
- No test infrastructure exists; correctness is verified by build plus manual
  inspection on a device:
  - preview animates when SCREEN is expanded and the app is foregrounded;
  - it stops when SCREEN is collapsed and when the app is backgrounded;
  - changing rain controls updates the preview live;
  - the preview's rain matches the home-screen wallpaper.

## Risks

- Relocating the engine's draw loop could subtly change visuals. Mitigation: move the
  code byte-for-byte (no behavioural edits) and verify with a build and diff review.
- The preview renders roughly one screen's worth of columns while visible; acceptable
  because it runs only in the foreground with the SCREEN section open.
