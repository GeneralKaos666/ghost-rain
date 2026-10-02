// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkBaseline = darkColorScheme(
        primary = Color(0xFF00FF66),
        onPrimary = Color(0xFF000000),
        background = Color(0xFF000000),
        onBackground = Color(0xFF00FF66),
        surface = Color(0xFF000000),
        onSurface = Color(0xFF00FF66)
)

private val LightBaseline = lightColorScheme(
        primary = Color(0xFF007A33),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF001510),
        surface = Color(0xFFF2F5F1),
        onSurface = Color(0xFF001510)
)

/**
 * Ghost Rain theme: Material You dynamic colors on API 31+, baseline
 * green-on-black (dark) / green-accent (light) fallback below.
 */
@Composable
fun GhostRainTheme(
        darkTheme: Boolean = isSystemInDarkTheme(),
        content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkBaseline
        else -> LightBaseline
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
