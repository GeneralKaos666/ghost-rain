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
        /**
         * Full-fidelity reader over a [MatrixDataStore.snapshot]: same keys,
         * defaults, and clamping as the legacy `SharedPreferences` reader.
         * [fromPrefs] delegates here, so there is exactly one rule.
         */
        fun fromSnapshot(s: Map<String, Any?>): RainSettings {
            fun i(k: String, d: Int) = (s[k] as? Int) ?: d
            fun b(k: String) = (s[k] as? Boolean) ?: true
            val rawMin = i("rainMinLen", 6).coerceIn(1, 50)
            val rawMax = i("rainMaxLen", 32).coerceIn(1, 50)
            val fps = i("rainFps", 30).coerceIn(10, 60)
            return RainSettings(
                    speedMul = i("rainSpeed", 100) / 100f,
                    hue = i("rainHue", 120).coerceIn(0, 360),
                    fontSizeMul = i("rainFontSize", 100) / 100f,
                    glyphKatakana = b("glyphKatakana"),
                    glyphDigits = b("glyphDigits"),
                    glyphLatin = b("glyphLatin"),
                    glyphSymbols = b("glyphSymbols"),
                    minLen = minOf(rawMin, rawMax),
                    maxLen = maxOf(rawMin, rawMax),
                    shimmer = i("shimmer", 60) / 100f,
                    frameDelayMs = (1000f / fps).roundToInt().coerceIn(16, 100))
        }

        fun fromPrefs(p: SharedPreferences): RainSettings = fromSnapshot(p.all)

        /**
         * Pure-JVM twin of [fromPrefs] for the unit-test source set and the
         * Compose UI (Tasks 4-5): same keys, same defaults, same clamping
         * (min/max swap, fps 10-60). Glyph flags have no `Int` representation,
         * so they keep the [fromPrefs] defaults (all enabled).
         */
        fun fromMap(m: Map<String, Int>): RainSettings {
            val rawMin = (m["rainMinLen"] ?: 6).coerceIn(1, 50)
            val rawMax = (m["rainMaxLen"] ?: 32).coerceIn(1, 50)
            val fps = (m["rainFps"] ?: 30).coerceIn(10, 60)
            return RainSettings(
                    speedMul = (m["rainSpeed"] ?: 100) / 100f,
                    hue = (m["rainHue"] ?: 120).coerceIn(0, 360),
                    fontSizeMul = (m["rainFontSize"] ?: 100) / 100f,
                    glyphKatakana = true,
                    glyphDigits = true,
                    glyphLatin = true,
                    glyphSymbols = true,
                    minLen = minOf(rawMin, rawMax),
                    maxLen = maxOf(rawMin, rawMax),
                    shimmer = (m["shimmer"] ?: 60) / 100f,
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
