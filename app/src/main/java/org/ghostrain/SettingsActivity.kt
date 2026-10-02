// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlin.math.roundToInt

private const val BAR_HEIGHT_DP = 18


/**
 * Compose settings host. The content is Compose ([SettingsScreen] under
 * [GhostRainTheme]); the legacy [PreviewView]/[HuePicker] view classes below are
 * kept verbatim for the Task 4 bridge (embedded via AndroidView), along with the
 * members they touch (`p`, `updatePreview`, `dp`).
 */
class SettingsActivity : ComponentActivity() {

    companion object {

        // Screen-agnostic config keys (a layout = a snapshot of these for one screen).
        private val L_BOOL = arrayOf(
                "hud", "el_title", "el_ram", "el_disk", "el_bat", "el_cpu", "el_net", "el_up", "redactIp"
        )
        private val L_INT = arrayOf("hudX", "hudPos", "hudScale")
        private val L_INT_DEF = intArrayOf(50, 50, 100)
    }

    private lateinit var p: SharedPreferences

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge()
        p = getSharedPreferences("matrix", MODE_PRIVATE)
        setContent {
            GhostRainTheme {
                SettingsScreen()
            }
        }
    }

    /**
     * Bridge hook for [HuePicker]: the Compose screen (Tasks 4-6) refreshes the
     * preview through state instead; this stub only keeps the legacy class compiling.
     */
    // TODO(Task 4: route HuePicker state + prefs into preview)
    private fun updatePreview() {
    }

    private fun dp(d: Int): Int {
        return (d * resources.displayMetrics.density).roundToInt()
    }

    private fun dp(d: Float): Int {
        return (d * resources.displayMetrics.density).roundToInt()
    }

    /** Screen-proportioned preview that renders the actual HUD lines at scale/pos. */
    inner class PreviewView(ctx: Context) : View(ctx) {
        private var px = 0.5f
        private var py = 0.5f
        private var scale = 1f
        private var lines = emptyArray<String>()
        private val rainRenderer = RainRenderer(resources.displayMetrics.density)
        private var rainSettings: RainSettings? = null
        private var lastFontSizeMul = -1f
        private val handler = Handler(Looper.getMainLooper())
        private var animating = false
        private var lastDraw = 0L
        private val frame = object : Runnable {
            override fun run() {
                invalidate()
                if (animating) handler.postDelayed(this, (rainSettings?.frameDelayMs ?: 33).toLong())
            }
        }
        private val sw: Int
        private val sh: Int
        private val aspect: Float
        private val screen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = Paint(Paint.ANTI_ALIAS_FLAG)
        private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val meas = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            sw = resources.displayMetrics.widthPixels
            sh = resources.displayMetrics.heightPixels
            aspect = sw / maxOf(1, sh).toFloat()
            val vt = try {
                Typeface.createFromAsset(context.assets, "VT323-Regular.ttf")
            } catch (_: Exception) {
                Typeface.MONOSPACE
            }
            meas.typeface = vt
            lineP.typeface = vt
            lineP.color = 0xFF00FF66.toInt()
            lineP.textAlign = Paint.Align.CENTER
            screen.color = 0xFF000000.toInt()
            border.style = Paint.Style.STROKE
            border.strokeWidth = dp(1).toFloat()
            border.color = 0xFF00FF33.toInt()
            box.style = Paint.Style.STROKE
            box.strokeWidth = dp(1).toFloat()
            box.color = 0xFF00FF66.toInt()
            boxFill.color = 0x3300FF66.toInt()
        }

        fun set(x: Float, y: Float, s: Float, ls: Array<String>) {
            px = x; py = y; scale = s; lines = ls
            invalidate()
        }

        fun setRain(s: RainSettings) {
            rainSettings = s
            if (s.fontSizeMul != lastFontSizeMul) {
                lastFontSizeMul = s.fontSizeMul
                rainRenderer.resize(sw, sh, s)
            }
            invalidate()
        }

        fun setAnimating(on: Boolean) {
            if (on == animating) return
            animating = on
            handler.removeCallbacks(frame)
            if (on) {
                lastDraw = 0L
                handler.post(frame)
            }
        }

        override fun onDetachedFromWindow() {
            handler.removeCallbacks(frame)
            animating = false
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val vw = width
            val vh = height
            val (rw, rh) = if (vw / vh.toFloat() > aspect) {
                Pair(vh * aspect, vh.toFloat())
            } else {
                Pair(vw.toFloat(), vw / aspect)
            }
            val left = (vw - rw) / 2f
            val top = (vh - rh) / 2f
            c.drawRect(left, top, left + rw, top + rh, screen)
            c.drawRect(left, top, left + rw, top + rh, border)
            val rs = rainSettings
            if (rs != null) {
                // Wall-clock dt (not the nominal frame delay): extra invalidations from
                // slider drags or scrolling only advance the rain by the time actually
                // elapsed, so interaction can't speed it up and a delayed frame can't
                // slow it down. Clamped like the wallpaper engine's dt.
                val now = SystemClock.uptimeMillis()
                val dt = if (lastDraw == 0L) rs.frameDelayMs / 1000f
                        else minOf(0.1f, (now - lastDraw) / 1000f)
                lastDraw = now
                val pScale = rw / sw
                val save = c.save()
                c.translate(left, top)
                c.scale(pScale, pScale)
                rainRenderer.draw(c, dt, rs)
                c.restoreToCount(save)
            }
            if (lines.isEmpty()) return

            val density = resources.displayMetrics.density
            val size = density * 13f * 1.05f * scale
            meas.textSize = size
            var panelW = 0f
            for (s in lines) panelW = maxOf(panelW, meas.measureText(s))
            val padX = size
            val padY = size * 0.6f
            val lh = size * 1.35f
            val pScale = rw / sw

            val bw = minOf(rw, (panelW + 2 * padX) * pScale)
            val bh = minOf(rh, (lines.size * lh + 2 * padY) * pScale)
            val bcx = (left + clamp(px, 0f, 1f) * rw).coerceIn(left + bw / 2, left + rw - bw / 2)
            val bcy = (top + clamp(py, 0f, 1f) * rh).coerceIn(top + bh / 2, top + rh - bh / 2)

            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, boxFill)
            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, box)

            lineP.textSize = size * pScale
            val plh = lh * pScale
            val baseline = bcy - bh / 2 + padY * pScale + (size * pScale) * 0.8f
            for (i in lines.indices) c.drawText(lines[i], bcx, baseline + i * plh, lineP)
        }

        private fun clamp(v: Float, lo: Float, hi: Float): Float {
            return v.coerceIn(lo, hi)
        }
    }

    /**
     * Horizontal hue bar for picking the rain color. Touching or dragging
     * selects a hue degree (0-360) and persists to "rainHue" in prefs.
     */
    inner class HuePicker(ctx: Context, initialHue: Int, private val label: TextView?) : View(ctx) {
        private var hue = initialHue.coerceIn(0, 360)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            isAntiAlias = true
        }
        private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f).toFloat()
            isAntiAlias = true
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF555555.toInt()
            strokeWidth = dp(0.5f).toFloat()
            isAntiAlias = true
        }
        private var thumbR = 0

        init {
            isFocusable = true
            isClickable = true
        }

        fun setHue(h: Int) {
            hue = h.coerceIn(0, 360)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            thumbR = dp(10)
            val colors = IntArray(7) { i -> Color.HSVToColor(255, floatArrayOf(i * 60f, 1f, 1f)) }
            val pos = floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f)
            barPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, pos, Shader.TileMode.CLAMP)
        }

        override fun onMeasure(ws: Int, hs: Int) {
            setMeasuredDimension(MeasureSpec.getSize(ws), dp(44))
        }

        override fun onDraw(c: Canvas) {
            val w = width
            val barH = dp(BAR_HEIGHT_DP)
            val barTop = (height - barH) / 2

            val barL = (thumbR + dp(3)).toFloat()
            val barR = (w - thumbR - dp(3)).toFloat()
            val cy = barTop + barH / 2f

            // Hue gradient bar
            c.drawRoundRect(barL, barTop.toFloat(), barR, (barTop + barH).toFloat(), dp(4).toFloat(), dp(4).toFloat(), barPaint)
            c.drawRoundRect(barL, barTop.toFloat(), barR, (barTop + barH).toFloat(), dp(4).toFloat(), dp(4).toFloat(), borderPaint)

            // Thumb indicator
            val thumbCX = barL + (barR - barL) * (hue / 360f)
            c.drawCircle(thumbCX, cy, thumbR.toFloat(), thumbFill)
            c.drawCircle(thumbCX, cy, thumbR.toFloat(), thumbStroke)

            // Inner dot showing the selected color
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.HSVToColor(255, floatArrayOf(hue.toFloat(), 1f, 1f))
            }
            c.drawCircle(thumbCX, cy, thumbR * 0.45f, dot)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val barL = (thumbR + dp(3)).toFloat()
                    val barR = (width - thumbR - dp(3)).toFloat()
                    val x = e.x.coerceIn(barL, barR)
                    val newHue = (360 * (x - barL) / (barR - barL)).roundToInt().coerceIn(0, 360)
                    if (newHue != hue) {
                        hue = newHue
                        p.edit().putInt("rainHue", hue).apply()
                        label?.text = "Rain color hue:  $hue\u00B0"
                        invalidate()
                        updatePreview()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        override fun performClick(): Boolean {
            return super.performClick()
        }

    }
}
