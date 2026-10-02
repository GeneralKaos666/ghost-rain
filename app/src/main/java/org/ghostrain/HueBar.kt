// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Pure-Compose hue picker (Task 8, Phase 2). Replaces the legacy
 * `SettingsActivity.HuePicker` view with a Compose [Canvas] using the same
 * geometry and paint values: an 18dp HSV gradient bar (seven stops, red at
 * both ends) with a gray hairline border, a 10dp white thumb with a dark
 * outline, and an inner dot showing the selected color.
 *
 * Pressing or dragging anywhere on the bar selects a hue degree (0-360) from
 * the x position with the legacy mapping
 * `(360 * (x - barL) / (barR - barL)).roundToInt().coerceIn(0, 360)` and
 * reports it via [onHue]; the caller persists `rainHue` and refreshes the
 * preview (single write path — this composable holds no prefs reference).
 * Drawing goes through `nativeCanvas` with `android.graphics.Paint`, so the
 * bar/thumb rasterize exactly like the legacy view.
 */
@Composable
fun HueBar(
        hue: Int,
        onHue: (Int) -> Unit,
        modifier: Modifier = Modifier
) {
    val density = LocalContext.current.resources.displayMetrics.density
    fun dpPx(d: Float): Int = (d * density).roundToInt()
    val coerced = hue.coerceIn(0, 360)

    val thumbR = dpPx(10f)
    val edge = dpPx(3f)
    val barH = dpPx(18f)
    val corner = dpPx(4f).toFloat()
    val barPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG)
    }
    val borderPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF555555.toInt()
            strokeWidth = dpPx(0.5f).toFloat()
        }
    }
    val thumbFill = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    }
    val thumbStroke = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpPx(1.5f).toFloat()
        }
    }

    // View width in px for the touch mapping (same value the draw block sees
    // as size.width once laid out).
    var widthPx by remember { mutableFloatStateOf(0f) }
    // Latest values for the drag closure: pointerInput is keyed on widthPx,
    // so without these the mid-drag emitHue would use a stale hue/callback.
    val latestCoerced by rememberUpdatedState(coerced)
    val latestOnHue by rememberUpdatedState(onHue)
    fun emitHue(x: Float) {
        if (widthPx <= 0f) return
        val barL = (thumbR + edge).toFloat()
        val barR = widthPx - thumbR - edge
        if (barR <= barL) return
        val newHue = (360 * (x.coerceIn(barL, barR) - barL) / (barR - barL))
                .roundToInt().coerceIn(0, 360)
        if (newHue != latestCoerced) latestOnHue(newHue)
    }

    Canvas(
            modifier = modifier.fillMaxWidth().height(48.dp)
                    .onSizeChanged { widthPx = it.width.toFloat() }
                    .semantics {
                        contentDescription = "Rain color hue picker, $coerced degrees"
                    }
                    .pointerInput(widthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            emitHue(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                emitHue(change.position.x)
                                change.consume()
                                if (!event.changes.any { it.pressed }) break
                            } while (true)
                        }
                    }
    ) {
        val w = size.width
        val h = size.height
        val barL = (thumbR + edge).toFloat()
        val barR = w - thumbR - edge
        val barTop = (h - barH) / 2f
        val cy = barTop + barH / 2f
        drawIntoCanvas { nv ->
            val c = nv.nativeCanvas
            val colors = IntArray(7) { i ->
                Color.HSVToColor(255, floatArrayOf(i * 60f, 1f, 1f))
            }
            val pos = floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f)
            barPaint.shader = LinearGradient(
                    0f, 0f, w, 0f, colors, pos, Shader.TileMode.CLAMP
            )
            c.drawRoundRect(barL, barTop, barR, barTop + barH, corner, corner, barPaint)
            c.drawRoundRect(barL, barTop, barR, barTop + barH, corner, corner, borderPaint)

            val thumbCX = barL + (barR - barL) * (coerced / 360f)
            c.drawCircle(thumbCX, cy, thumbR.toFloat(), thumbFill)
            c.drawCircle(thumbCX, cy, thumbR.toFloat(), thumbStroke)

            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.HSVToColor(255, floatArrayOf(coerced.toFloat(), 1f, 1f))
            }
            c.drawCircle(thumbCX, cy, thumbR * 0.45f, dot)
        }
    }
}
