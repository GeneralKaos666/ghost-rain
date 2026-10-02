// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
 * lock-redact rules the engine applies. Reads the [MatrixDataStore]
 * snapshot, like every other reader. Single config: no screen targeting.
 */
internal fun previewLines(): Array<String> {
    fun keyOf(base: String) = HudPrefs.keyOf(base)
    val lines = mutableListOf<String>()
    if (MatrixDataStore.getBoolean(keyOf("hud"), true)) {
        for (key in HudPrefs.orderKeys(MatrixDataStore.getString("order", HudPrefs.DEFAULT_ORDER))) {
            if (!MatrixDataStore.getBoolean(keyOf("el_$key"), true)) continue
            val line = when (key) {
                "title" -> HudLines.titleOrNull(
                    MatrixDataStore.getString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
                )
                "ram" -> "RAM  [####\u00B7\u00B7\u00B7\u00B7] 62%"
                "disk" -> "DISK [######\u00B7\u00B7] 92/128G"
                "bat" -> "BAT  [#######\u00B7] 84% +"
                "cpu" -> "CPU  [##\u00B7\u00B7\u00B7\u00B7\u00B7\u00B7] 18%"
                "net" -> HudLines.netLine(
                    "192.168.7.127",
                    locked = false,
                    redact = MatrixDataStore.getBoolean(keyOf("redactIp"), true),
                    transport = "Wi-Fi",
                    ssid = "HomeNet",
                    signalLevel = 4
                )
                "up" -> "UP   3d 04:12"
                else -> null
            }
            if (line != null) lines.add(line)
        }
    }
    return lines.toTypedArray()
}

/**
 * Live preview bridge (Task 8, Phase 2): pure Compose [RainPreview] inside a
 * [Card], fed by the current per-screen HUD geometry plus [RainSettings] on
 * every recomposition. The repo change listener in [SettingsScreen] recomposes on
 * any pref write, so slider/toggle edits refresh the preview through this
 * path. No `AndroidView` remains: the legacy `PreviewView` is deleted.
 *
 * @param animating rain loop on/off; the caller passes the activity
 * `resumed` state (host screen open + activity lifecycle).
 * [RainPreview] cancels its frame loop when this node leaves the composition,
 * so there is no leak.
 */
@Composable
fun PreviewBridge(
        animating: Boolean,
        snapshotVersion: Int,
        modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_EXPRESSION")
    snapshotVersion // forwarded: structural preview refresh token
    Card(modifier = modifier.fillMaxWidth()) {
        RainPreview(
                animating = animating,
                snapshotVersion = snapshotVersion,
                modifier = Modifier.fillMaxWidth().height(400.dp)
        )
    }
}
