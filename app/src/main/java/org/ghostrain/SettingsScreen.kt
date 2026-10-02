// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Compose settings host: top bar, HOME/LOCK target switch, and per-screen
 * sections. SETUP + SCREEN came from Task 4, HUD from Task 5, RAIN from Task 6.
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
 * The rain loop runs only while SCREEN is open and the activity is resumed.
 *
 * State notes: `editingLock` (which screen is being edited) survives rotation
 * via [rememberSaveable]; section open/closed states persist in the app-level
 * `ui_open_*` prefs (RAIN collapsed by default, like the legacy section).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var editingLock by rememberSaveable { mutableStateOf(false) }
    var screenOpen by remember {
        mutableStateOf(MatrixDataStore.getBoolean("ui_open_screen", true))
    }
    var hudOpen by remember {
        mutableStateOf(MatrixDataStore.getBoolean("ui_open_hud", true))
    }
    var rainOpen by remember {
        mutableStateOf(MatrixDataStore.getBoolean("ui_open_rain", false))
    }
    // Which named layout the SCREEN row shows as loaded. Hoisted here (not in
    // LayoutsRow) so the HUD Reset — a sibling section — can clear the label,
    // exactly like legacy resetHud cleared currentLayout.
    var currentLayout by remember { mutableStateOf<String?>(null) }
    // Bumped on any pref write (controls in Tasks 5-6, layout ops below) and
    // on lifecycle resume (returning from the system wallpaper picker may have
    // changed wallpaper-active state), so preview + status card recompute.
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

    Scaffold(
            topBar = {
                TopAppBar(title = { Text("Ghost Rain") })
            },
            snackbarHost = { SnackbarHost(snacks) }
    ) { padding ->
        Column(
                modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            TargetSwitch(editingLock = editingLock, onChange = { editingLock = it })
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    SetupCard(prefsTick, resumed, onChanged = { prefsTick++ })
                }
                item {
                    ScreenSection(
                            
                            editingLock = editingLock,
                            open = screenOpen,
                            animating = screenOpen && resumed,
                            snapshotVersion = prefsTick,
                            onToggle = {
                                screenOpen = !screenOpen
                                MatrixDataStore.putBoolean("ui_open_screen", screenOpen)
                            },
                            onChanged = { prefsTick++ },
                            currentLayout = currentLayout,
                            onLayoutChange = { currentLayout = it }
                    )
                }
                item {
                    HudSection(
                            
                            editingLock = editingLock,
                            open = hudOpen,
                            onToggle = {
                                hudOpen = !hudOpen
                                MatrixDataStore.putBoolean("ui_open_hud", hudOpen)
                            },
                            onChanged = { prefsTick++ },
                            snacks = snacks,
                            currentLayout = currentLayout,
                            onLayoutChange = { currentLayout = it }
                    )
                }
                item {
                    RainSection(
                            
                            open = rainOpen,
                            onToggle = {
                                rainOpen = !rainOpen
                                MatrixDataStore.putBoolean("ui_open_rain", rainOpen)
                            },
                            onChanged = { prefsTick++ },
                            snacks = snacks
                    )
                }
            }
        }
    }
}

/**
 * Global HOME/LOCK target switch: a Material3 single-choice segmented button
 * (48dp targets, text labels double as TalkBack descriptions). One switcher
 * for the whole screen; the per-section duplicate from the early Compose port
 * is gone — legacy had a single toggle too (inside SCREEN).
 */
@Composable
private fun TargetSwitch(editingLock: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        SegmentedButton(
                selected = !editingLock,
                onClick = { onChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text("HOME") }
        )
        SegmentedButton(
                selected = editingLock,
                onClick = { onChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text("LOCK") }
        )
    }
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

/**
 * Shared collapsible-section header: title, optional Reset action, and a 48dp
 * expand/collapse [IconButton] with a TalkBack description. The title itself
 * is also tappable (large target); both toggle the section.
 */
@Composable
private fun SectionHeader(
        title: String,
        open: Boolean,
        onToggle: () -> Unit,
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
                modifier = Modifier.weight(1f).clickable(onClick = onToggle)
        )
        if (onReset != null) {
            TextButton(
                    onClick = onReset,
                    modifier = Modifier.semantics {
                        contentDescription = resetDescription ?: "Reset $title to defaults"
                    }
            ) { Text("Reset") }
        }
        IconButton(
                onClick = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = if (open) "Collapse $title section" else "Expand $title section"
                }
        ) { Text(if (open) "\u2212" else "+") }
    }
}

/**
 * SCREEN section: collapsible (state in app-level `ui_open_screen`), live
 * [PreviewBridge], and [LayoutsRow]. The edited-screen target is the global
 * [TargetSwitch] above; this section just shows which screen is being edited.
 * The rain loop runs only while the section is open and the activity is resumed.
 */
@Composable
private fun ScreenSection(
        editingLock: Boolean,
        open: Boolean,
        animating: Boolean,
        snapshotVersion: Int,
        onToggle: () -> Unit,
        onChanged: () -> Unit,
        currentLayout: String?,
        onLayoutChange: (String?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        SectionHeader(
                title = "SCREEN (HOME / LOCK)",
                open = open,
                onToggle = onToggle
        )
        if (!open) return@Column
        Spacer(modifier = Modifier.height(4.dp))
        Text("Editing screen: " + if (editingLock) "LOCK" else "HOME")
        Spacer(modifier = Modifier.height(8.dp))
        PreviewBridge(
                editingLock = editingLock,
                animating = animating,
                snapshotVersion = snapshotVersion
        )
        Spacer(modifier = Modifier.height(8.dp))
        LayoutsRow(
                editingLock = editingLock,
                onChanged = onChanged,
                currentLayout = currentLayout,
                onLayoutChange = onLayoutChange
        )
        Text(
                "New = fresh config for this screen; Save as\u2026 = store it as a named layout.",
                style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * HUD section: per-screen overlay config for whichever screen is being edited
 * (HOME/LOCK via [editingLock]; every key goes through [HudPrefs.keyOf], so
 * the LOCK variant is the base key plus a `Lock` suffix). Collapsible via the
 * app-level `ui_open_hud` pref (open by default, like the legacy section).
 *
 * Title rule (single rule, used everywhere): empty-or-blank hides the title
 * line — see [HudLines.titleOrNull], which the engine and the preview also
 * use. The `order` pref is global; a reorder-save merges stored unknown
 * tokens back via [HudPrefs.mergeOrderOnSave] so they are never deleted.
 */
@Composable
private fun HudSection(
        editingLock: Boolean,
        open: Boolean,
        onToggle: () -> Unit,
        onChanged: () -> Unit,
        snacks: SnackbarHostState,
        currentLayout: String?,
        onLayoutChange: (String?) -> Unit
) {
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    var showReset by remember { mutableStateOf(false) }
    var showRedactConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        SectionHeader(
                title = "HUD",
                open = open,
                onToggle = onToggle,
                resetDescription = "Reset HUD to defaults",
                onReset = { showReset = true }
        )
        if (!open) return@Column
        Spacer(modifier = Modifier.height(4.dp))

        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show HUD overlay", modifier = Modifier.weight(1f))
            Switch(
                    checked = MatrixDataStore.getBoolean(keyOf("hud"), true),
                    onCheckedChange = {
                        MatrixDataStore.putBoolean(keyOf("hud"), it)
                        onChanged()
                    }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Title text:")
        OutlinedTextField(
                value = MatrixDataStore.getString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
                        ?: LAYOUT_TITLE_DEFAULT,
                onValueChange = {
                    MatrixDataStore.putString(keyOf("title"), it)
                    onChanged()
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
        )

        HudSlider(
                label = "Horizontal position",
                value = MatrixDataStore.getInt(keyOf("hudX"), 50),
                min = 0,
                max = 100,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt(keyOf("hudX"), it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Vertical position",
                value = MatrixDataStore.getInt(keyOf("hudPos"), 50),
                min = 0,
                max = 100,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt(keyOf("hudPos"), it)
                    onChanged()
                }
        )
        HudSlider(
                label = "Size",
                value = MatrixDataStore.getInt(keyOf("hudScale"), 100),
                min = 50,
                max = 200,
                suffix = "%",
                onValue = {
                    MatrixDataStore.putInt(keyOf("hudScale"), it)
                    onChanged()
                }
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text("HUD elements (check = show, arrows = reorder):")
        val order = HudPrefs.orderKeys(MatrixDataStore.getString("order", HudPrefs.DEFAULT_ORDER))
        for ((idx, key) in order.withIndex()) {
            val elKey = keyOf("el_$key")
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
                    checked = MatrixDataStore.getBoolean(keyOf("redactIp"), true),
                    onCheckedChange = { checked ->
                        if (checked) {
                            MatrixDataStore.putBoolean(keyOf("redactIp"), true)
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
                    checked = MatrixDataStore.getBoolean(keyOf("hudDynamic"), MatrixDataStore.HUD_DYNAMIC_DEFAULT),
                    onCheckedChange = {
                        MatrixDataStore.putBoolean(keyOf("hudDynamic"), it)
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
                                MatrixDataStore.putBoolean(keyOf("redactIp"), false)
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
        val screen = if (editingLock) "LOCK" else "HOME"
        AlertDialog(
                onDismissRequest = { showReset = false },
                title = { Text("Reset $screen HUD to defaults?") },
                text = {
                    Text(
                        "Restores position, size, title and element visibility for " +
                                "the $screen screen."
                    )
                },
                confirmButton = {
                    TextButton(
                            onClick = {
                                val prev = snapshotHud(editingLock, currentLayout)
                                for (k in LAYOUT_BOOLS) MatrixDataStore.putBoolean(keyOf(k), true)
                                for (i in LAYOUT_INTS.indices) {
                                    MatrixDataStore.putInt(keyOf(LAYOUT_INTS[i]), LAYOUT_INT_DEFS[i])
                                }
                                MatrixDataStore.putString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
                                MatrixDataStore.putBoolean(keyOf("hudDynamic"), MatrixDataStore.HUD_DYNAMIC_DEFAULT)
                                showReset = false
                                onLayoutChange(null)
                                onChanged()
                                scope.launch {
                                    if (snacks.showSnackbar(
                                            "HUD reset to defaults",
                                            "Undo"
                                    ) == SnackbarResult.ActionPerformed) {
                                        restoreHud(editingLock, prev)
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
 * RAIN section: global (not per-screen) matrix-rain config. Collapsible via the
 * app-level `ui_open_rain` pref (collapsed by default, like the legacy
 * section). Slider/switch writes go straight to the [MatrixDataStore] snapshot;
 * the listener in [SettingsScreen] recomposes on any write, so [PreviewBridge]
 * (which reads [RainSettings.fromSnapshot] every recomposition) reflects
 * changes instantly — including [HueBar] drags, which write `rainHue` to the
 * same store directly.
 */
@Composable
private fun RainSection(
        open: Boolean,
        onToggle: () -> Unit,
        onChanged: () -> Unit,
        snacks: SnackbarHostState
) {
    var showReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        SectionHeader(
                title = "RAIN (global)",
                open = open,
                onToggle = onToggle,
                resetDescription = "Reset rain to defaults",
                onReset = { showReset = true }
        )
        if (!open) return@Column
        Spacer(modifier = Modifier.height(4.dp))

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
                label = "Glyph shimmer (both screens)",
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
                    Text(
                            "Restores speed, color, glyph sets, column length and frame rate " +
                                    "(applies to both screens)."
                    )
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

/** Integer slider row backed by an int pref (per-screen HUD keys and global rain keys). */
@Composable
private fun HudSlider(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onValue: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: $value$suffix")
        Slider(
                value = value.toFloat(),
                onValueChange = { onValue(it.roundToInt()) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Snapshot of one screen's HUD prefs + layout label, for Reset Undo. */
private data class HudSnapshot(
        val bools: Map<String, Boolean>,
        val ints: Map<String, Int>,
        val title: String,
        val dynamic: Boolean,
        val layoutName: String?
)

private fun snapshotHud(
        editingLock: Boolean,
        currentLayout: String?
): HudSnapshot {
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    val bools = LinkedHashMap<String, Boolean>()
    for (k in LAYOUT_BOOLS) bools[k] = MatrixDataStore.getBoolean(keyOf(k), true)
    val ints = LinkedHashMap<String, Int>()
    for (i in LAYOUT_INTS.indices) {
        ints[LAYOUT_INTS[i]] = MatrixDataStore.getInt(keyOf(LAYOUT_INTS[i]), LAYOUT_INT_DEFS[i])
    }
    val title = MatrixDataStore.getString(keyOf("title"), LAYOUT_TITLE_DEFAULT) ?: LAYOUT_TITLE_DEFAULT
    val dynamic = MatrixDataStore.getBoolean(keyOf("hudDynamic"), MatrixDataStore.HUD_DYNAMIC_DEFAULT)
    return HudSnapshot(bools, ints, title, dynamic, currentLayout)
}

private fun restoreHud(editingLock: Boolean, snap: HudSnapshot) {
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    for ((k, v) in snap.bools) MatrixDataStore.putBoolean(keyOf(k), v)
    for ((k, v) in snap.ints) MatrixDataStore.putInt(keyOf(k), v)
    MatrixDataStore.putString(keyOf("title"), snap.title)
    MatrixDataStore.putBoolean(keyOf("hudDynamic"), snap.dynamic)
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
 * Reads/writes the same `layouts` JSON via [LayoutsRepo]; loading/applying
 * targets whichever screen is currently being edited.
 */
@Composable
private fun LayoutsRow(
        editingLock: Boolean,
        onChanged: () -> Unit,
        currentLayout: String?,
        onLayoutChange: (String?) -> Unit
) {
    val context = LocalContext.current
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    var showPicker by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    fun loadLayouts(): List<LayoutSnapshot> =
            LayoutsRepo.load(MatrixDataStore.getString("layouts", "[]"))

    fun applySnapshot(s: LayoutSnapshot) {
        for (k in LAYOUT_BOOLS) MatrixDataStore.putBoolean(keyOf(k), s.bools[k] ?: true)
        for (i in LAYOUT_INTS.indices) {
            MatrixDataStore.putInt(keyOf(LAYOUT_INTS[i]), s.ints[LAYOUT_INTS[i]] ?: LAYOUT_INT_DEFS[i])
        }
        MatrixDataStore.putString(keyOf("title"), s.title.ifEmpty { LAYOUT_TITLE_DEFAULT })
        MatrixDataStore.putBoolean(keyOf("hudDynamic"), s.bools["hudDynamic"] ?: MatrixDataStore.HUD_DYNAMIC_DEFAULT)
        onChanged()
    }

    fun saveLayout(name: String) {
        try {
            val bools = LinkedHashMap<String, Boolean>()
            for (k in LAYOUT_BOOLS) bools[k] = MatrixDataStore.getBoolean(keyOf(k), true)
            bools["hudDynamic"] = MatrixDataStore.getBoolean(keyOf("hudDynamic"), MatrixDataStore.HUD_DYNAMIC_DEFAULT)
            val ints = LinkedHashMap<String, Int>()
            for (i in LAYOUT_INTS.indices) {
                ints[LAYOUT_INTS[i]] = MatrixDataStore.getInt(keyOf(LAYOUT_INTS[i]), LAYOUT_INT_DEFS[i])
            }
            val snapshot = LayoutSnapshot(
                    name = name,
                    bools = bools,
                    ints = ints,
                    title = MatrixDataStore.getString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
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
        for (k in LAYOUT_BOOLS) MatrixDataStore.putBoolean(keyOf(k), true)
        for (i in LAYOUT_INTS.indices) MatrixDataStore.putInt(keyOf(LAYOUT_INTS[i]), LAYOUT_INT_DEFS[i])
        MatrixDataStore.putString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
        MatrixDataStore.putBoolean(keyOf("hudDynamic"), MatrixDataStore.HUD_DYNAMIC_DEFAULT)
        onLayoutChange(null)
        onChanged()
        toast(context, "New config for " + if (editingLock) "LOCK" else "HOME")
    }

    Text("Layouts (apply to edited screen)")
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
                    title = { Text("Load layout into " + if (editingLock) "LOCK" else "HOME") },
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
