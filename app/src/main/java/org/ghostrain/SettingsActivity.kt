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

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // Kick the one-time SharedPreferences -> DataStore migration (also
        // triggered by the engine; whichever runs first wins, exactly once).
        MatrixDataStore.ensureMigrated(this)
        enableEdgeToEdge()
        setContent {
            GhostRainTheme {
                SettingsScreen()
            }
        }
    }
}
