// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * Pure-Compose settings preview (Task 8, Phase 2). Replaces the legacy
 * `SettingsActivity.PreviewView` `AndroidView` bridge with a Compose [Canvas]
 * that drives the unchanged [RainRenderer] engine with the same
 * resize/draw/dt discipline, so the same prefs render the same preview:
 *
 * - Virtual-screen scaling: the engine is sized at the full display (`sw`/`sh`
 *   from display metrics) and drawn into the aspect-fitted rect under a
 *   `translate(left, top)` + `scale(pScale)` transform, exactly like
 *   `PreviewView.onDraw`.
 * - Wall-clock dt: each frame advances by the time actually elapsed since the
 *   previous draw (first frame uses the nominal frame delay), clamped to
 *   0.1s like the wallpaper engine, so extra recompositions from slider drags
 *   or scrolling neither speed up nor slow down the rain.
 * - HUD box: measured with an `android.graphics.Paint` carrying the VT323
 *   typeface (loaded from assets, monospace fallback), same `size`/`pad`/`lh`
 *   factors, same clamp-into-rect positioning, same centered green text on a
 *   translucent fill — all rasterized through `nativeCanvas` with the same
 *   paint values, so pixels match the legacy view within rounding.
 * - Frame loop: a `LaunchedEffect` re-triggers a redraw every `frameDelayMs`
 *   while [animating]; leaving the composition (collapsing SCREEN) cancels
 *   the loop with no leak. `resize(sw, sh, rain)` runs on first composition
 *   and whenever `fontSizeMul` changes, mirroring `setRain`.
 *
 * @param animating rain loop on/off; the caller passes
 * `screenOpen && resumed` (SCREEN-section open state + activity lifecycle).
 */
@Composable
fun RainPreview(
        editingLock: Boolean,
        animating: Boolean,
        snapshotVersion: Int,
        modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_EXPRESSION")
    snapshotVersion // structural refresh token: bumped on any pref write
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    val px = MatrixDataStore.getInt(keyOf("hudX"), 50) / 100f
    val py = MatrixDataStore.getInt(keyOf("hudPos"), 50) / 100f
    val scale = MatrixDataStore.getInt(keyOf("hudScale"), 100) / 100f
    val lines = previewLines(editingLock)
    val rain = RainSettings.fromSnapshot(MatrixDataStore.snapshot())
    // Per-screen opt-in Material You HUD: OFF keeps the legacy green paint
    // values (identical to the wallpaper engine defaults); ON follows the
    // Compose palette (the engine resolves the equivalent system accents).
    val dynamic = MatrixDataStore.getBoolean(keyOf("hudDynamic"), MatrixDataStore.HUD_DYNAMIC_DEFAULT)
    val scheme = MaterialTheme.colorScheme
    val previewText = if (dynamic) scheme.primary else Color(0xFF00FF66)
    val previewFill = if (dynamic) scheme.surfaceVariant.copy(alpha = 0.78f) else Color(0x3300FF66)

    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val sw = metrics.widthPixels
    val sh = metrics.heightPixels
    val aspect = sw / maxOf(1, sh).toFloat()
    fun dp(d: Float): Int = (d * density).roundToInt()

    // Sentinel outside the density key: reset it here so a fresh renderer
    // from a density change always gets resize() before its first draw.
    val lastFontSizeMul = remember { floatArrayOf(-1f) }
    val renderer = remember(density) {
        lastFontSizeMul[0] = -1f
        RainRenderer(density)
    }
    val vt = remember {
        try {
            Typeface.createFromAsset(context.assets, "VT323-Regular.ttf")
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }
    // Paints configured exactly like the legacy PreviewView init block.
    val screen = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    }
    val border = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
            color = 0xFF00FF33.toInt()
        }
    }
    val box = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
            color = 0xFF00FF66.toInt()
        }
    }
    val boxFill = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3300FF66.toInt() }
    }
    val meas = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = vt }
    }
    val lineP = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = vt
            color = 0xFF00FF66.toInt()
            textAlign = Paint.Align.CENTER
        }
    }

    // Non-snapshot holder mutated from the draw block (writing snapshot
    // state during draw is disallowed, plain remembered holders are fine).
    val lastDraw = remember { longArrayOf(0L) }
    val previewTextArgb = previewText.toArgb()
    val previewFillArgb = previewFill.toArgb()
    // resize() is a side effect: keep it out of the composable body so a
    // density/font-size change resizes once per commit, not per compose.
    SideEffect {
        if (rain.fontSizeMul != lastFontSizeMul[0]) {
            lastFontSizeMul[0] = rain.fontSizeMul
            renderer.resize(sw, sh, rain)
        }
    }

    // Frame driver: bump tick every frameDelayMs while animating. Keyed on
    // the delay too so an FPS-slider change takes effect on the next frame
    // (the legacy handler re-read the delay each post; restarting the loop
    // resets lastDraw, i.e. the next dt is one nominal frame — a sub-frame
    // difference that self-corrects via the wall-clock clamp below).
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(animating, rain.frameDelayMs) {
        if (!animating) return@LaunchedEffect
        lastDraw[0] = 0L
        while (true) {
            kotlinx.coroutines.delay(rain.frameDelayMs.toLong())
            tick++
        }
    }

    Canvas(modifier = modifier.fillMaxWidth()) {
        @Suppress("UNUSED_EXPRESSION")
        tick // subscribe: each bump schedules a redraw
        val vw = size.width
        val vh = size.height
        val (rw, rh) = if (vw / vh > aspect) {
            Pair(vh * aspect, vh)
        } else {
            Pair(vw, vw / aspect)
        }
        val left = (vw - rw) / 2f
        val top = (vh - rh) / 2f
        drawIntoCanvas { nv ->
            val c = nv.nativeCanvas
            c.drawRect(left, top, left + rw, top + rh, screen)
            c.drawRect(left, top, left + rw, top + rh, border)
            val now = SystemClock.uptimeMillis()
            val dt = if (lastDraw[0] == 0L) rain.frameDelayMs / 1000f
                    else minOf(0.1f, (now - lastDraw[0]) / 1000f)
            lastDraw[0] = now
            val pScale = rw / sw
            val save = c.save()
            c.translate(left, top)
            c.scale(pScale, pScale)
            renderer.draw(c, dt, rain)
            c.restoreToCount(save)

            if (lines.isEmpty()) return@drawIntoCanvas
            lineP.color = previewTextArgb
            box.color = previewTextArgb
            boxFill.color = previewFillArgb
            val textSize = density * 13f * 1.05f * scale
            meas.textSize = textSize
            var panelW = 0f
            for (s in lines) panelW = maxOf(panelW, meas.measureText(s))
            val padX = textSize
            val padY = textSize * 0.6f
            val lh = textSize * 1.35f

            val bw = minOf(rw, (panelW + 2 * padX) * pScale)
            val bh = minOf(rh, (lines.size * lh + 2 * padY) * pScale)
            val bcx = (left + px.coerceIn(0f, 1f) * rw)
                    .coerceIn(left + bw / 2, left + rw - bw / 2)
            val bcy = (top + py.coerceIn(0f, 1f) * rh)
                    .coerceIn(top + bh / 2, top + rh - bh / 2)

            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, boxFill)
            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, box)

            lineP.textSize = textSize * pScale
            val plh = lh * pScale
            val baseline = bcy - bh / 2 + padY * pScale + (textSize * pScale) * 0.8f
            for (i in lines.indices) c.drawText(lines[i], bcx, baseline + i * plh, lineP)
        }
    }
}
