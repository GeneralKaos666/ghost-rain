// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Compose settings host. The whole UI is Compose ([SettingsScreen] under
 * [GhostRainTheme]); the preview and hue bar are pure-Compose canvases
 * ([RainPreview], [HueBar]) driving the shared [RainRenderer] engine, so no
 * Android Views remain.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // DataStore is the source of truth: one blocking load runs the
        // one-time legacy migration on upgrade (also triggered by the engine;
        // whichever runs first wins, later calls are no-ops).
        MatrixDataStore.startup(this)
        enableEdgeToEdge()
        setContent {
            GhostRainTheme {
                SettingsScreen()
            }
        }
    }
}
