// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCREEN_MAIN = "main"
private const val SCREEN_HUD = "hud"
private const val SCREEN_RAIN = "rain"

/**
 * Compose settings host: single config (no HOME/LOCK split), one screen at a
 * time — a main menu plus HUD / Rain detail screens.
 *
 * The main menu hosts the live [PreviewBridge] plus [LayoutsRow]; HUD and
 * Rain detail screens each repeat the preview at the top so tweaks give
 * instant feedback. Only one screen composes at a time, so only one
 * [RainPreview] frame loop runs.
 *
 * Restoration notes (legacy `SettingsActivity` behavior, kept verbatim):
 * - The SET button writes `lastAppliedVersion` optimistically before launching
 *   the picker (the system gives no completion signal; a cancelled picker
 *   behaves like tapping "Later").
 * - The status card shows setup instructions until Ghost Rain is the active
 *   wallpaper, then a one-tap update prompt after a version bump (dismissable
 *   per version via `updatePromptDismissed`), then nothing.
 * - Layouts round-trip the same `layouts` JSON via [LayoutsRepo].
 *
 * The rain preview loop runs while its host screen is open and the
 * activity is resumed.
 *
 * Refresh notes: [prefsTick] is read in this body (passed as
 * `snapshotVersion` below), so any pref write recomposes the whole screen —
 * slider thumbs/labels follow drags instantly. Detail screens are plain
 * scrollable Columns (no lazy item scopes), so there is no stale-scope class
 * of bug here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var screen by rememberSaveable { mutableStateOf(SCREEN_MAIN) }
    // Which named layout the layouts row shows as loaded. Hoisted here (not in
    // LayoutsRow) so the HUD Reset — a different screen — can clear the label,
    // exactly like legacy resetHud cleared currentLayout.
    var currentLayout by remember { mutableStateOf<String?>(null) }
    // Bumped on any pref write (controls, layout ops) and on lifecycle resume
    // (returning from the system wallpaper picker may have changed
    // wallpaper-active state), so the whole screen recomputes.
    var prefsTick by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Repo change listener: any write recomposes (same role the
        // SharedPreferences listener had — DataStore is now the store).
        val repoListener: (String?) -> Unit = { prefsTick++ }
        MatrixDataStore.addOnChangeListener(repoListener)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    prefsTick++
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            MatrixDataStore.removeOnChangeListener(repoListener)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    val snacks = remember { SnackbarHostState() }
    // Legacy saves may still hold "display" (removed screen): fall back to main.
    val effectiveScreen = when (screen) {
        SCREEN_HUD, SCREEN_RAIN -> screen
        else -> SCREEN_MAIN
    }

    BackHandler(enabled = effectiveScreen != SCREEN_MAIN) { screen = SCREEN_MAIN }

    Scaffold(
            topBar = {
                TopAppBar(
                        title = {
                            Text(
                                when (effectiveScreen) {
                                    SCREEN_HUD -> "HUD"
                                    SCREEN_RAIN -> "Rain"
                                    else -> "Ghost Rain"
                                }
                            )
                        },
                        navigationIcon = {
                            if (effectiveScreen != SCREEN_MAIN) {
                                TextButton(onClick = { screen = SCREEN_MAIN }) {
                                    Text("\u2039 Back")
                                }
                            }
                        }
                )
            },
            snackbarHost = { SnackbarHost(snacks) }
    ) { padding ->
        Column(
                modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            when (effectiveScreen) {
                SCREEN_HUD -> DetailScreen {
                    HudSection(
                            animating = resumed,
                            snapshotVersion = prefsTick,
                            onChanged = { prefsTick++ },
                            snacks = snacks,
                            currentLayout = currentLayout,
                            onLayoutChange = { currentLayout = it }
                    )
                }
                SCREEN_RAIN -> DetailScreen {
                    RainSection(
                            animating = resumed,
                            snapshotVersion = prefsTick,
                            onChanged = { prefsTick++ },
                            snacks = snacks
                    )
                }
                else -> MainScreen(
                        prefsTick = prefsTick,
                        resumed = resumed,
                        onChanged = { prefsTick++ },
                        onOpen = { screen = it },
                        currentLayout = currentLayout,
                        onLayoutChange = { currentLayout = it }
                )
            }
        }
    }
}

/** Plain scrollable detail column: one composition scope, no lazy staleness. */
@Composable
private fun DetailScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
            modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp)
    ) {
        content()
    }
}

/** Main menu: status/setup, live preview + layouts, plus HUD/Rain entries. */
@Composable
private fun MainScreen(
        prefsTick: Int,
        resumed: Boolean,
        onChanged: () -> Unit,
        onOpen: (String) -> Unit,
        currentLayout: String?,
        onLayoutChange: (String?) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SetupCard(prefsTick, resumed, onChanged = onChanged)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            PreviewBridge(
                    animating = resumed,
                    snapshotVersion = prefsTick
            )
            Spacer(modifier = Modifier.height(8.dp))
            LayoutsRow(
                    onChanged = onChanged,
                    currentLayout = currentLayout,
                    onLayoutChange = onLayoutChange
            )
            Text(
                    "New = fresh config; Save as\u2026 = store it as a named layout.",
                    style = MaterialTheme.typography.bodySmall
            )
        }
        NavRow(
                title = "HUD",
                subtitle = "Overlay, elements, position, color",
                onClick = { onOpen(SCREEN_HUD) }
        )
        NavRow(
                title = "Rain",
                subtitle = "Speed, color, glyphs, presets",
                onClick = { onOpen(SCREEN_RAIN) }
        )
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { Text("\u203A", style = MaterialTheme.typography.headlineSmall) },
            modifier = Modifier.clickable(onClick = onClick).heightIn(min = 64.dp)
    )
}

/**
 * Status card + wallpaper button. Three states, same `lastAppliedVersion` /
 * `updatePromptDismissed` semantics as the legacy banner:
 * - wallpaper not active -> setup instructions;
 * - active + version changed (and not dismissed for this version) -> update
 *   prompt with a Later-dismiss;
 * - active + versions match -> no card (button still shown below).
 */
@Composable
private fun SetupCard(
        prefsTick: Int,
        resumed: Boolean,
        onChanged: () -> Unit
) {
    val context = LocalContext.current
    val current = remember(prefsTick, resumed) { currentVersionCode(context) }
    val active = remember(prefsTick, resumed) { isOurWallpaperActive(context) }
    // First launch with the wallpaper already set (upgrade from a build that
    // predates this tracking, or set via the system picker): assume the running
    // build is current instead of nagging once for no reason.
    LaunchedEffect(active, current) {
        if (active && MatrixDataStore.getInt("lastAppliedVersion", -1) == -1) {
            MatrixDataStore.putInt("lastAppliedVersion", current)
            onChanged()
        }
    }
    val rawStored = MatrixDataStore.getInt("lastAppliedVersion", -1)
    val stored = if (active && rawStored == -1) current else rawStored
    val dismissed = MatrixDataStore.getInt("updatePromptDismissed", -1)

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("SETUP", style = MaterialTheme.typography.titleMedium)
        when {
            !active -> StatusCard(
                    text = "SETUP - DO THIS NOW:\n" +
                            "  1.  Tap  > SET GHOST RAIN WALLPAPER  below\n" +
                            "  2.  Choose  BOTH  (home + lock screen)\n\n" +
                            "Tip: if the lock-screen clock covers the HUD, set the lock clock size to Small " +
                            "(in your phone's lock screen / wallpaper settings)."
            )
            stored != current && dismissed != current -> StatusCard(
                    text = "Updated to version $current - apply the new build:\n" +
                            "Tap  > SET GHOST RAIN WALLPAPER  below, then choose BOTH.",
                    dismissLabel = "Later",
                    onDismiss = {
                        MatrixDataStore.putInt("updatePromptDismissed", current)
                        onChanged()
                    }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
                onClick = {
                    // Optimistically record the version being applied: the
                    // system picker gives no completion signal, so a cancelled
                    // picker behaves like tapping "Later" (prompt suppressed
                    // until the next version bump).
                    MatrixDataStore.putInt("lastAppliedVersion", currentVersionCode(context))
                    setWallpaper(context)
                    onChanged()
                },
                modifier = Modifier.fillMaxWidth()
        ) {
            Text("> SET GHOST RAIN WALLPAPER")
        }
    }
}

@Composable
private fun StatusCard(text: String, dismissLabel: String? = null, onDismiss: () -> Unit = {}) {
    Card(
            colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dismissLabel != null) {
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(dismissLabel) }
                }
            }
        }
    }
}

/** Static detail header: title plus optional Reset action. */
@Composable
private fun DetailHeader(
        title: String,
        resetDescription: String? = null,
        onReset: (() -> Unit)? = null
) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
        )
        if (onReset != null) {
            TextButton(
                    onClick = onReset,
                    modifier = Modifier.semantics {
                        contentDescription = resetDescription ?: "Reset $title to defaults"
                    }
            ) { Text("Reset") }
        }
    }
}

/**
 * HUD screen: single overlay config (no HOME/LOCK split). Every key is the
 * base pref key — see [HudPrefs.keyOf]. Live [PreviewBridge] on top so
 * position/size/visibility tweaks give instant feedback.
 */
@Composable
private fun HudSection(
        animating: Boolean,
        // Changed on every pref write (caller passes prefsTick): a changed arg
        // defeats strong skipping, so slider thumbs/labels re-read fresh values
        // mid-drag instead of only on scroll.
        snapshotVersion: Int,
        onChanged: () -> Unit,
        snacks: SnackbarHostState,
        currentLayout: String?,
        onLayoutChange: (String?) -> Unit
) {
    @Suppress("UNUSED_EXPRESSION")
    snapshotVersion // subscribed refresh token (see above)
    var showReset by remember { mutableStateOf(false) }
    var showRedactConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        PreviewBridge(
                animating = animating,
                snapshotVersion = snapshotVersion
        )
        Spacer(modifier = Modifier.height(8.dp))
        DetailHeader(
                title = "HUD",
                resetDescription = "Reset HUD to defaults",
                onReset = { showReset = true }
        )

        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show HUD overlay", modifier = Modifier.weight(1f))
            Switch(
                    checked = MatrixDataStore.getBoolean("hud", true),
                    onCheckedChange = {
                        MatrixDataStore.putBoolean("hud", it)
                        onChanged()
                    }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Title text:")
        OutlinedTextField(
                value = MatrixDataStore.getString("title", LAYOUT_TITLE_DEFAULT)
                        ?: LAYOUT_TITLE_DEFAULT,
                onValueChange = {
                    MatrixDataStore.putString("title", it)
                    onChanged()
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
        )

        HudSlider(
                label = "Horizontal position",
                value = MatrixDataStore.getInt("hudX", 50),
                min = 0,
                max = 100,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt("hudX", it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Vertical position",
                value = MatrixDataStore.getInt("hudPos", 50),
                min = 0,
                max = 100,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt("hudPos", it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Size",
                value = MatrixDataStore.getInt("hudScale", 100),
                min = 50,
                max = 200,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt("hudScale", it)
                    onChanged()
                }
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text("HUD elements (check = show, arrows = reorder):")
        val order = HudPrefs.orderKeys(MatrixDataStore.getString("order", HudPrefs.DEFAULT_ORDER))
        for ((idx, key) in order.withIndex()) {
            val elKey = "el_$key"
            ListItem(
                    headlineContent = { Text(labelFor(key)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                    checked = MatrixDataStore.getBoolean(elKey, true),
                                    onCheckedChange = {
                                        MatrixDataStore.putBoolean(elKey, it)
                                        onChanged()
                                    }
                            )
                            IconButton(
                                    onClick = { moveOrder(order, idx, -1, onChanged) },
                                    enabled = idx > 0,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Move ${labelFor(key)} up"
                                    }
                            ) { Text("\u25B2") }
                            IconButton(
                                    onClick = { moveOrder(order, idx, 1, onChanged) },
                                    enabled = idx < order.size - 1,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Move ${labelFor(key)} down"
                                    }
                            ) { Text("\u25BC") }
                        }
                    }
            )
        }

        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Redact IP on lock screen", modifier = Modifier.weight(1f))
            Switch(
                    checked = MatrixDataStore.getBoolean("redactIp", true),
                    onCheckedChange = { checked ->
                        if (checked) {
                            MatrixDataStore.putBoolean("redactIp", true)
                            onChanged()
                        } else {
                            showRedactConfirm = true
                        }
                    }
            )
        }

        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Match system color")
                Text(
                        "Off = classic green HUD",
                        style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                    checked = MatrixDataStore.getBoolean("hudDynamic", MatrixDataStore.HUD_DYNAMIC_DEFAULT),
                    onCheckedChange = {
                        MatrixDataStore.putBoolean("hudDynamic", it)
                        onChanged()
                    }
            )
        }
    }

    if (showRedactConfirm) {
        AlertDialog(
                onDismissRequest = { showRedactConfirm = false },
                title = { Text("Show IP on the lock screen?") },
                text = {
                    Text(
                        "Not recommended. Your device IP will be visible to anyone " +
                                "who can see your lock screen, without unlocking. Are you sure?"
                    )
                },
                confirmButton = {
                    TextButton(
                            onClick = {
                                MatrixDataStore.putBoolean("redactIp", false)
                                showRedactConfirm = false
                                onChanged()
                            }
                    ) { Text("Show it anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { showRedactConfirm = false }) {
                        Text("Keep redacted")
                    }
                }
        )
    }
    if (showReset) {
        AlertDialog(
                onDismissRequest = { showReset = false },
                title = { Text("Reset HUD to defaults?") },
                text = {
                    Text("Restores position, size, title and element visibility.")
                },
                confirmButton = {
                    TextButton(
                            onClick = {
                                val prev = snapshotHud(currentLayout)
                                for (k in LAYOUT_BOOLS) MatrixDataStore.putBoolean(k, true)
                                for (i in LAYOUT_INTS.indices) {
                                    MatrixDataStore.putInt(LAYOUT_INTS[i], LAYOUT_INT_DEFS[i])
                                }
                                MatrixDataStore.putString("title", LAYOUT_TITLE_DEFAULT)
                                MatrixDataStore.putBoolean("hudDynamic", MatrixDataStore.HUD_DYNAMIC_DEFAULT)
                                showReset = false
                                onLayoutChange(null)
                                onChanged()
                                scope.launch {
                                    if (snacks.showSnackbar(
                                            "HUD reset to defaults",
                                            "Undo"
                                    ) == SnackbarResult.ActionPerformed) {
                                        restoreHud(prev)
                                        onLayoutChange(prev.layoutName)
                                        onChanged()
                                    }
                                }
                            }
                    ) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showReset = false }) { Text("Cancel") }
                }
        )
    }
}

/** Rain pref keys with their legacy defaults (global, not per-screen). */
private val RAIN_INT_DEFS = mapOf(
        "shimmer" to 60,
        "rainSpeed" to 100,
        "rainHue" to 120,
        "rainFontSize" to 100,
        "rainMinLen" to 6,
        "rainMaxLen" to 32,
        "rainFps" to 30
)
private val RAIN_GLYPH_KEYS = arrayOf("glyphKatakana", "glyphDigits", "glyphLatin", "glyphSymbols")

/**
 * Color presets. Each writes `rainHue` + `shimmer` only — every other rain key
 * is left untouched. Classic is the legacy default pair.
 */
private val RAIN_PRESETS = arrayOf(
        Triple("Classic", 120, 60),
        Triple("Amber", 35, 70),
        Triple("Ice", 200, 50)
)

/** Snapshot of all rain prefs, for Reset Undo. */
private data class RainSnapshot(val ints: Map<String, Int>, val glyphs: Map<String, Boolean>)

private fun snapshotRain(): RainSnapshot {
    val ints = LinkedHashMap<String, Int>()
    for ((k, d) in RAIN_INT_DEFS) ints[k] = MatrixDataStore.getInt(k, d)
    val glyphs = LinkedHashMap<String, Boolean>()
    for (k in RAIN_GLYPH_KEYS) glyphs[k] = MatrixDataStore.getBoolean(k, true)
    return RainSnapshot(ints, glyphs)
}

private fun restoreRain(snap: RainSnapshot) {
    for ((k, v) in snap.ints) MatrixDataStore.putInt(k, v)
    for ((k, v) in snap.glyphs) MatrixDataStore.putBoolean(k, v)
}

private fun resetRain() {
    for ((k, d) in RAIN_INT_DEFS) MatrixDataStore.putInt(k, d)
    for (k in RAIN_GLYPH_KEYS) MatrixDataStore.putBoolean(k, true)
}

/**
 * RAIN screen: global matrix-rain config. Slider/switch writes go straight to
 * the [MatrixDataStore] snapshot; the tick read in [SettingsScreen] recomposes
 * on any write, so the preview at the top stays in sync.
 */
@Composable
private fun RainSection(
        animating: Boolean,
        // See HudSection: defeats strong skipping so sliders follow drags.
        snapshotVersion: Int,
        onChanged: () -> Unit,
        snacks: SnackbarHostState
) {
    @Suppress("UNUSED_EXPRESSION")
    snapshotVersion // subscribed refresh token (see above)
    var showReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        PreviewBridge(
                animating = animating,
                snapshotVersion = snapshotVersion
        )
        Spacer(modifier = Modifier.height(8.dp))
        DetailHeader(
                title = "Rain (global)",
                resetDescription = "Reset rain to defaults",
                onReset = { showReset = true }
        )

        Text("Preset (color + shimmer only):")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, hue, shimmer) in RAIN_PRESETS) {
                Button(
                        onClick = {
                            MatrixDataStore.putInt("rainHue", hue)
                            MatrixDataStore.putInt("shimmer", shimmer)
                            onChanged()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(name) }
            }
        }

        HudSlider(
                label = "Glyph shimmer",
                value = MatrixDataStore.getInt("shimmer", 60),
                min = 0,
                max = 100,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt("shimmer", it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Rain speed",
                value = MatrixDataStore.getInt("rainSpeed", 100),
                min = 10,
                max = 300,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt("rainSpeed", it)
                    onChanged()
                }
        )

        val hue = MatrixDataStore.getInt("rainHue", 120)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Rain color hue:  $hue\u00B0")
        HueBar(
                hue = hue,
                onHue = {
                    MatrixDataStore.putInt("rainHue", it)
                    onChanged()
                },
                modifier = Modifier.fillMaxWidth()
        )

        HudSlider(
                label = "Glyph font size",
                value = MatrixDataStore.getInt("rainFontSize", 100),
                min = 50,
                max = 200,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt("rainFontSize", it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Column min length",
                value = MatrixDataStore.getInt("rainMinLen", 6),
                min = 1,
                max = 50,
                suffix = "",
                onValue = {
                    MatrixDataStore.putInt("rainMinLen", it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Column max length",
                value = MatrixDataStore.getInt("rainMaxLen", 32),
                min = 1,
                max = 50,
                suffix = "",
                onValue = {
                    MatrixDataStore.putInt("rainMaxLen", it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Frame rate",
                value = MatrixDataStore.getInt("rainFps", 30),
                min = 10,
                max = 60,
                suffix = " fps",
                onValue = {
                    MatrixDataStore.putInt("rainFps", it)
                    onChanged()
                }
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text("Glyph set:")
        for (key in RAIN_GLYPH_KEYS) {
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(glyphLabel(key), modifier = Modifier.weight(1f))
                Switch(
                        checked = MatrixDataStore.getBoolean(key, true),
                        onCheckedChange = {
                            MatrixDataStore.putBoolean(key, it)
                            onChanged()
                        }
                )
            }
        }
    }

    if (showReset) {
        AlertDialog(
                onDismissRequest = { showReset = false },
                title = { Text("Reset rain to defaults?") },
                text = {
                    Text("Restores speed, color, glyph sets, column length and frame rate.")
                },
                confirmButton = {
                    TextButton(
                            onClick = {
                                val prev = snapshotRain()
                                resetRain()
                                showReset = false
                                onChanged()
                                scope.launch {
                                    if (snacks.showSnackbar(
                                            "Rain reset to defaults",
                                            "Undo"
                                    ) == SnackbarResult.ActionPerformed) {
                                        restoreRain(prev)
                                        onChanged()
                                    }
                                }
                            }
                    ) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showReset = false }) { Text("Cancel") }
                }
        )
    }
}

/** Display label for a glyph-set pref key (mirrors the legacy rain rows). */
private fun glyphLabel(key: String): String {
    return when (key) {
        "glyphKatakana" -> "Katakana"
        "glyphDigits" -> "Digits"
        "glyphLatin" -> "Latin"
        "glyphSymbols" -> "Symbols"
        else -> key
    }
}

/**
 * Expressive integer slider row backed by an int pref. While pressed, the
 * label pops on a spring (expressive press feedback using only stable APIs —
 * the value-based `Slider` thumb/track slots are experimental-or-deprecated
 * in material3 1.4.0 and their opt-in is internal). The label tracks the live
 * value every recomposition, so it follows drags (the host recomposes on any
 * pref write).
 */
@Composable
private fun HudSlider(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onValue: (Int) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val labelScale by animateFloatAsState(
            targetValue = if (pressed) 1.12f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "sliderLabel"
    )
    Column(
            modifier = Modifier.fillMaxWidth()
                    .pointerInput(Unit) {
                        // Observe only (never consume): the Slider keeps full
                        // gesture ownership; this just drives the label pop.
                        awaitEachGesture {
                            awaitFirstDown()
                            pressed = true
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    if (!event.changes.any { it.pressed }) break
                                } while (true)
                            } finally {
                                pressed = false
                            }
                        }
                    }
    ) {
        Text(
                "$label: $value$suffix",
                modifier = Modifier.graphicsLayer(
                        scaleX = labelScale,
                        scaleY = labelScale,
                        transformOrigin = TransformOrigin(0f, 0.5f)
                )
        )
        Slider(
                value = value.toFloat(),
                onValueChange = { onValue(it.roundToInt()) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Snapshot of the HUD prefs + layout label, for Reset Undo. */
private data class HudSnapshot(
        val bools: Map<String, Boolean>,
        val ints: Map<String, Int>,
        val title: String,
        val dynamic: Boolean,
        val layoutName: String?
)

private fun snapshotHud(currentLayout: String?): HudSnapshot {
    val bools = LinkedHashMap<String, Boolean>()
    for (k in LAYOUT_BOOLS) bools[k] = MatrixDataStore.getBoolean(k, true)
    val ints = LinkedHashMap<String, Int>()
    for (i in LAYOUT_INTS.indices) {
        ints[LAYOUT_INTS[i]] = MatrixDataStore.getInt(LAYOUT_INTS[i], LAYOUT_INT_DEFS[i])
    }
    val title = MatrixDataStore.getString("title", LAYOUT_TITLE_DEFAULT) ?: LAYOUT_TITLE_DEFAULT
    val dynamic = MatrixDataStore.getBoolean("hudDynamic", MatrixDataStore.HUD_DYNAMIC_DEFAULT)
    return HudSnapshot(bools, ints, title, dynamic, currentLayout)
}

private fun restoreHud(snap: HudSnapshot) {
    for ((k, v) in snap.bools) MatrixDataStore.putBoolean(k, v)
    for ((k, v) in snap.ints) MatrixDataStore.putInt(k, v)
    MatrixDataStore.putString("title", snap.title)
    MatrixDataStore.putBoolean("hudDynamic", snap.dynamic)
}

/** Display label for an HUD element key (mirrors the legacy element rows). */
private fun labelFor(key: String): String {
    return when (key) {
        "title" -> "Title"
        "ram" -> "RAM"
        "disk" -> "Disk"
        "bat" -> "Battery"
        "cpu" -> "CPU"
        "net" -> "Network / IP"
        "up" -> "Uptime"
        else -> key
    }
}

/**
 * Persist a reorder of the displayed element list. The stored `order` value is
 * merged through [HudPrefs.mergeOrderOnSave] so unknown tokens survive the save.
 */
private fun moveOrder(
        order: List<String>,
        idx: Int,
        dir: Int,
        onChanged: () -> Unit
) {
    val j = idx + dir
    if (j < 0 || j >= order.size) return
    val swapped = order.toMutableList()
    val tmp = swapped[idx]
    swapped[idx] = swapped[j]
    swapped[j] = tmp
    MatrixDataStore.putString(
            "order",
            HudPrefs.mergeOrderOnSave(swapped, MatrixDataStore.getString("order", HudPrefs.DEFAULT_ORDER))
    )
    onChanged()
}

/**
 * Saved-layouts row: load picker dialog, New, Save as, Delete with confirm.
 * Reads/writes the same `layouts` JSON via [LayoutsRepo].
 */
@Composable
private fun LayoutsRow(
        onChanged: () -> Unit,
        currentLayout: String?,
        onLayoutChange: (String?) -> Unit
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    fun loadLayouts(): List<LayoutSnapshot> =
            LayoutsRepo.load(MatrixDataStore.getString("layouts", "[]"))

    fun applySnapshot(s: LayoutSnapshot) {
        for (k in LAYOUT_BOOLS) MatrixDataStore.putBoolean(k, s.bools[k] ?: true)
        for (i in LAYOUT_INTS.indices) {
            MatrixDataStore.putInt(LAYOUT_INTS[i], s.ints[LAYOUT_INTS[i]] ?: LAYOUT_INT_DEFS[i])
        }
        MatrixDataStore.putString("title", s.title.ifEmpty { LAYOUT_TITLE_DEFAULT })
        MatrixDataStore.putBoolean("hudDynamic", s.bools["hudDynamic"] ?: MatrixDataStore.HUD_DYNAMIC_DEFAULT)
        onChanged()
    }

    fun saveLayout(name: String) {
        try {
            val bools = LinkedHashMap<String, Boolean>()
            for (k in LAYOUT_BOOLS) bools[k] = MatrixDataStore.getBoolean(k, true)
            bools["hudDynamic"] = MatrixDataStore.getBoolean("hudDynamic", MatrixDataStore.HUD_DYNAMIC_DEFAULT)
            val ints = LinkedHashMap<String, Int>()
            for (i in LAYOUT_INTS.indices) {
                ints[LAYOUT_INTS[i]] = MatrixDataStore.getInt(LAYOUT_INTS[i], LAYOUT_INT_DEFS[i])
            }
            val snapshot = LayoutSnapshot(
                    name = name,
                    bools = bools,
                    ints = ints,
                    title = MatrixDataStore.getString("title", LAYOUT_TITLE_DEFAULT)
                            ?: LAYOUT_TITLE_DEFAULT
            )
            val out = loadLayouts().filter { it.name != name } + snapshot
            MatrixDataStore.putString("layouts", LayoutsRepo.serialize(out))
            onLayoutChange(name)
            onChanged()
            toast(context, "Saved \"$name\"")
        } catch (_: Exception) {
            toast(context, "Save failed")
        }
    }

    fun deleteLayout(name: String) {
        try {
            val out = loadLayouts().filter { it.name != name }
            MatrixDataStore.putString("layouts", LayoutsRepo.serialize(out))
            if (name == currentLayout) onLayoutChange(null)
            onChanged()
        } catch (_: Exception) { }
    }

    fun newLayout() {
        for (k in LAYOUT_BOOLS) MatrixDataStore.putBoolean(k, true)
        for (i in LAYOUT_INTS.indices) MatrixDataStore.putInt(LAYOUT_INTS[i], LAYOUT_INT_DEFS[i])
        MatrixDataStore.putString("title", LAYOUT_TITLE_DEFAULT)
        MatrixDataStore.putBoolean("hudDynamic", MatrixDataStore.HUD_DYNAMIC_DEFAULT)
        onLayoutChange(null)
        onChanged()
        toast(context, "New config")
    }

    Text("Layouts")
    Button(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Text(currentLayout ?: "(unsaved layout)")
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { newLayout() }, modifier = Modifier.weight(1f)) { Text("New") }
        Button(onClick = { showSave = true }, modifier = Modifier.weight(1f)) { Text("Save as\u2026") }
        Button(
                onClick = { showDelete = true },
                enabled = currentLayout != null,
                modifier = Modifier.weight(1f)
        ) { Text("Delete") }
    }

    if (showPicker) {
        val layouts = remember { loadLayouts() }
        if (layouts.isEmpty()) {
            showPicker = false
            toast(context, "No saved layouts yet - tap Save")
        } else {
            AlertDialog(
                    onDismissRequest = { showPicker = false },
                    title = { Text("Load layout") },
                    text = {
                        Column {
                            for (s in layouts) {
                                Text(
                                        s.name,
                                        modifier = Modifier.fillMaxWidth()
                                                .clickable {
                                                    applySnapshot(s)
                                                    onLayoutChange(s.name)
                                                    showPicker = false
                                                    toast(context, "Loaded \"" + s.name + "\"")
                                                }
                                                .padding(vertical = 8.dp)
                                )
                            }
                        }
                    },
                    confirmButton = { },
                    dismissButton = {
                        TextButton(onClick = { showPicker = false }) { Text("Cancel") }
                    }
            )
        }
    }
    if (showSave) {
        var name by remember { mutableStateOf(currentLayout ?: "") }
        AlertDialog(
                onDismissRequest = { showSave = false },
                title = { Text("Save layout as") },
                text = {
                    TextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                            onClick = {
                                val n = name.trim()
                                showSave = false
                                if (n.isNotEmpty()) saveLayout(n)
                            }
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showSave = false }) { Text("Cancel") }
                }
        )
    }
    if (showDelete) {
        val name = currentLayout
        if (name == null) {
            showDelete = false
            toast(context, "This layout isn't saved yet")
        } else {
            AlertDialog(
                    onDismissRequest = { showDelete = false },
                    text = { Text("Delete layout \"$name\"?") },
                    confirmButton = {
                        TextButton(
                                onClick = {
                                    deleteLayout(name)
                                    showDelete = false
                                }
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDelete = false }) { Text("Cancel") }
                    }
            )
        }
    }
}

/** Launch the system live-wallpaper picker for Ghost Rain, with fallbacks. */
private fun setWallpaper(context: Context) {
    try {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, MatrixWallpaperService::class.java)
            )
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } catch (_: Exception) {
            toast(context, "Open Settings -> Wallpaper -> Ghost Rain")
        }
    }
}

/** True when Ghost Rain is the live wallpaper currently set on the home screen. */
private fun isOurWallpaperActive(context: Context): Boolean {
    return try {
        val info = WallpaperManager.getInstance(context).wallpaperInfo
        info?.component == ComponentName(context, MatrixWallpaperService::class.java)
    } catch (_: Exception) {
        false
    }
}

/** Installed versionCode; works on API 26-27 without the longVersionCode field. */
private fun currentVersionCode(context: Context): Int {
    return try {
        @Suppress("DEPRECATION")
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else pi.versionCode
    } catch (_: PackageManager.NameNotFoundException) {
        0
    } catch (_: Exception) {
        0
    }
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
