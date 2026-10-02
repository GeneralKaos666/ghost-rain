// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Screen-agnostic layout keys (a layout = a snapshot of these for one
 * screen). Mirrors the legacy `SettingsActivity` `L_BOOL`/`L_INT` tables so
 * [LayoutsRow] reads/writes the same prefs the engine renders.
 */
internal val LAYOUT_BOOLS = arrayOf(
        "hud", "el_title", "el_ram", "el_disk", "el_bat", "el_cpu", "el_net", "el_up", "redactIp"
)
internal val LAYOUT_INTS = arrayOf("hudX", "hudPos", "hudScale")
internal val LAYOUT_INT_DEFS = intArrayOf(50, 50, 100)
internal const val LAYOUT_TITLE_DEFAULT = "KEEP//HUD"

/**
 * Sample HUD lines for the preview, mirroring the legacy `sampleLine()`:
 * fixed placeholder values (not live stats) with the same title/empty and
 * lock-redact rules the engine applies.
 */
internal fun previewLines(prefs: SharedPreferences, editingLock: Boolean): Array<String> {
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    val lines = mutableListOf<String>()
    if (prefs.getBoolean(keyOf("hud"), true)) {
        for (key in HudPrefs.orderKeys(prefs.getString("order", HudPrefs.DEFAULT_ORDER))) {
            if (!prefs.getBoolean(keyOf("el_$key"), true)) continue
            val line = when (key) {
                "title" -> {
                    val t = prefs.getString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
                    if (!t.isNullOrEmpty()) t else null
                }
                "ram" -> "RAM  [####\u00B7\u00B7\u00B7\u00B7] 62%"
                "disk" -> "DISK [######\u00B7\u00B7] 92/128G"
                "bat" -> "BAT  [#######\u00B7] 84% +"
                "cpu" -> "CPU  [##\u00B7\u00B7\u00B7\u00B7\u00B7\u00B7] 18%"
                "net" -> {
                    val redact = prefs.getBoolean(keyOf("redactIp"), true)
                    if (editingLock && redact) "NET  [locked]" else "NET  192.168.7.127"
                }
                "up" -> "UP   3d 04:12"
                else -> null
            }
            if (line != null) lines.add(line)
        }
    }
    return lines.toTypedArray()
}

/**
 * Live preview bridge: embeds the legacy [SettingsActivity.PreviewView] via
 * [AndroidView] inside a [Card] and feeds it the current per-screen HUD
 * geometry plus [RainSettings] on every recomposition. The prefs listener in
 * [SettingsScreen] recomposes on any pref write, so slider/toggle edits (Tasks
 * 5-6) refresh the preview through this path.
 *
 * @param animating rain loop on/off; the caller passes
 * `screenOpen && resumed` (SCREEN-section open state + activity lifecycle).
 * [SettingsActivity.PreviewView] already stops its handler on detach, so
 * removing this node (collapsing SCREEN) halts the loop with no leak.
 */
@Composable
fun PreviewBridge(
        activity: SettingsActivity,
        prefs: SharedPreferences,
        editingLock: Boolean,
        animating: Boolean,
        modifier: Modifier = Modifier
) {
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    val px = prefs.getInt(keyOf("hudX"), 50) / 100f
    val py = prefs.getInt(keyOf("hudPos"), 50) / 100f
    val scale = prefs.getInt(keyOf("hudScale"), 100) / 100f
    val lines = previewLines(prefs, editingLock)
    val rain = RainSettings.fromPrefs(prefs)
    Card(modifier = modifier.fillMaxWidth()) {
        AndroidView(
                factory = { ctx -> activity.PreviewView(ctx) },
                update = { view ->
                    view.set(px, py, scale, lines)
                    view.setRain(rain)
                    view.setAnimating(animating)
                },
                modifier = Modifier.fillMaxWidth().height(190.dp)
        )
    }
}
