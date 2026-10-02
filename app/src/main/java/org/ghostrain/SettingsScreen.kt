// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.roundToInt

/**
 * Compose settings host: top bar, HOME/LOCK target tabs, and per-screen
 * sections. Task 4 owns the SETUP status card, the wallpaper button, and the
 * SCREEN section (live [PreviewBridge] + [LayoutsRow]); Tasks 5-6 add the
 * HUD/RAIN controls below.
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
 * @param editingLock which screen is being edited; survives rotation via
 * [rememberSaveable].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val activity = context as SettingsActivity
    val prefs = remember {
        activity.getSharedPreferences("matrix", Context.MODE_PRIVATE)
    }
    var editingLock by rememberSaveable { mutableStateOf(false) }
    var screenOpen by remember {
        mutableStateOf(prefs.getBoolean("ui_open_screen", true))
    }
    var hudOpen by remember {
        mutableStateOf(prefs.getBoolean("ui_open_hud", true))
    }
    // Bumped on any pref write (controls in Tasks 5-6, layout ops below) and
    // on lifecycle resume (returning from the system wallpaper picker may have
    // changed wallpaper-active state), so preview + status card recompute.
    var prefsTick by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, prefs) {
        val prefsListener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> prefsTick++ }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
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
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
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
            TabRow(selectedTabIndex = if (editingLock) 1 else 0) {
                Tab(
                        selected = !editingLock,
                        onClick = { editingLock = false },
                        text = { Text("HOME") }
                )
                Tab(
                        selected = editingLock,
                        onClick = { editingLock = true },
                        text = { Text("LOCK") }
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    SetupCard(prefs, prefsTick, resumed, onChanged = { prefsTick++ })
                }
                item {
                    ScreenSection(
                            activity = activity,
                            prefs = prefs,
                            editingLock = editingLock,
                            open = screenOpen,
                            animating = screenOpen && resumed,
                            onToggle = {
                                screenOpen = !screenOpen
                                prefs.edit().putBoolean("ui_open_screen", screenOpen).apply()
                            },
                            onEditingLockChange = { editingLock = it },
                            onChanged = { prefsTick++ }
                    )
                }
                item {
                    HudSection(
                            prefs = prefs,
                            editingLock = editingLock,
                            open = hudOpen,
                            onToggle = {
                                hudOpen = !hudOpen
                                prefs.edit().putBoolean("ui_open_hud", hudOpen).apply()
                            },
                            onChanged = { prefsTick++ }
                    )
                }
                // TODO(Task 6: RAIN section, global keys, keyed off nothing)
            }
        }
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
        prefs: SharedPreferences,
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
        if (active && prefs.getInt("lastAppliedVersion", -1) == -1) {
            prefs.edit().putInt("lastAppliedVersion", current).apply()
            onChanged()
        }
    }
    val rawStored = prefs.getInt("lastAppliedVersion", -1)
    val stored = if (active && rawStored == -1) current else rawStored
    val dismissed = prefs.getInt("updatePromptDismissed", -1)

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
                        prefs.edit().putInt("updatePromptDismissed", current).apply()
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
                    prefs.edit().putInt("lastAppliedVersion", currentVersionCode(context)).apply()
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
 * SCREEN section: collapsible (state in app-level `ui_open_screen`), HOME/LOCK
 * target toggle, live [PreviewBridge], and [LayoutsRow]. The rain loop runs
 * only while the section is open and the activity is resumed.
 */
@Composable
private fun ScreenSection(
        activity: SettingsActivity,
        prefs: SharedPreferences,
        editingLock: Boolean,
        open: Boolean,
        animating: Boolean,
        onToggle: () -> Unit,
        onEditingLockChange: (Boolean) -> Unit,
        onChanged: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    "SCREEN (HOME / LOCK)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
            )
            Text(if (open) "\u2212" else "+")
        }
        if (!open) return@Column
        Spacer(modifier = Modifier.height(4.dp))
        Text("Editing screen:")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                    onClick = { onEditingLockChange(false) },
                    enabled = editingLock,
                    modifier = Modifier.weight(1f)
            ) { Text("HOME") }
            Button(
                    onClick = { onEditingLockChange(true) },
                    enabled = !editingLock,
                    modifier = Modifier.weight(1f)
            ) { Text("LOCK") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        PreviewBridge(
                activity = activity,
                prefs = prefs,
                editingLock = editingLock,
                animating = animating
        )
        Spacer(modifier = Modifier.height(8.dp))
        LayoutsRow(prefs = prefs, editingLock = editingLock, onChanged = onChanged)
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
        prefs: SharedPreferences,
        editingLock: Boolean,
        open: Boolean,
        onToggle: () -> Unit,
        onChanged: () -> Unit
) {
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    var showReset by remember { mutableStateOf(false) }
    var showRedactConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    "HUD",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).clickable(onClick = onToggle)
            )
            TextButton(onClick = { showReset = true }) { Text("Reset") }
            Text(
                    if (open) "\u2212" else "+",
                    modifier = Modifier.clickable(onClick = onToggle)
            )
        }
        if (!open) return@Column
        Spacer(modifier = Modifier.height(4.dp))

        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show HUD overlay", modifier = Modifier.weight(1f))
            Switch(
                    checked = prefs.getBoolean(keyOf("hud"), true),
                    onCheckedChange = {
                        prefs.edit().putBoolean(keyOf("hud"), it).apply()
                        onChanged()
                    }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Title text:")
        OutlinedTextField(
                value = prefs.getString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
                        ?: LAYOUT_TITLE_DEFAULT,
                onValueChange = {
                    prefs.edit().putString(keyOf("title"), it).apply()
                    onChanged()
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
        )

        HudSlider(
                label = "Horizontal position",
                value = prefs.getInt(keyOf("hudX"), 50),
                min = 0,
                max = 100,
                suffix = "%",
                onValue = {
                    prefs.edit().putInt(keyOf("hudX"), it).apply()
                    onChanged()
                }
        )
        HudSlider(
                label = "Vertical position",
                value = prefs.getInt(keyOf("hudPos"), 50),
                min = 0,
                max = 100,
                suffix = "%",
                onValue = {
                    prefs.edit().putInt(keyOf("hudPos"), it).apply()
                    onChanged()
                }
        )
        HudSlider(
                label = "Size",
                value = prefs.getInt(keyOf("hudScale"), 100),
                min = 50,
                max = 200,
                suffix = "%",
                onValue = {
                    prefs.edit().putInt(keyOf("hudScale"), it).apply()
                    onChanged()
                }
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text("HUD elements (check = show, arrows = reorder):")
        val order = HudPrefs.orderKeys(prefs.getString("order", HudPrefs.DEFAULT_ORDER))
        for ((idx, key) in order.withIndex()) {
            val elKey = keyOf("el_$key")
            ListItem(
                    headlineContent = { Text(labelFor(key)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                    checked = prefs.getBoolean(elKey, true),
                                    onCheckedChange = {
                                        prefs.edit().putBoolean(elKey, it).apply()
                                        onChanged()
                                    }
                            )
                            IconButton(
                                    onClick = { moveOrder(order, idx, -1, prefs, onChanged) },
                                    enabled = idx > 0
                            ) { Text("\u25B2") }
                            IconButton(
                                    onClick = { moveOrder(order, idx, 1, prefs, onChanged) },
                                    enabled = idx < order.size - 1
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
                    checked = prefs.getBoolean(keyOf("redactIp"), true),
                    onCheckedChange = { checked ->
                        if (checked) {
                            prefs.edit().putBoolean(keyOf("redactIp"), true).apply()
                            onChanged()
                        } else {
                            showRedactConfirm = true
                        }
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
                                prefs.edit().putBoolean(keyOf("redactIp"), false).apply()
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
                                val e = prefs.edit()
                                for (k in LAYOUT_BOOLS) e.putBoolean(keyOf(k), true)
                                for (i in LAYOUT_INTS.indices) {
                                    e.putInt(keyOf(LAYOUT_INTS[i]), LAYOUT_INT_DEFS[i])
                                }
                                e.putString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
                                e.apply()
                                showReset = false
                                onChanged()
                            }
                    ) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showReset = false }) { Text("Cancel") }
                }
        )
    }
}

/** Integer slider row backed by a per-screen int pref. */
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
        prefs: SharedPreferences,
        onChanged: () -> Unit
) {
    val j = idx + dir
    if (j < 0 || j >= order.size) return
    val swapped = order.toMutableList()
    val tmp = swapped[idx]
    swapped[idx] = swapped[j]
    swapped[j] = tmp
    prefs.edit().putString(
            "order",
            HudPrefs.mergeOrderOnSave(swapped, prefs.getString("order", HudPrefs.DEFAULT_ORDER))
    ).apply()
    onChanged()
}

/**
 * Saved-layouts row: load picker dialog, New, Save as, Delete with confirm.
 * Reads/writes the same `layouts` JSON via [LayoutsRepo]; loading/applying
 * targets whichever screen is currently being edited.
 */
@Composable
private fun LayoutsRow(
        prefs: SharedPreferences,
        editingLock: Boolean,
        onChanged: () -> Unit
) {
    val context = LocalContext.current
    fun keyOf(base: String) = HudPrefs.keyOf(base, editingLock)
    var currentLayout by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    fun loadLayouts(): List<LayoutSnapshot> =
            LayoutsRepo.load(prefs.getString("layouts", "[]"))

    fun applySnapshot(s: LayoutSnapshot) {
        val e = prefs.edit()
        for (k in LAYOUT_BOOLS) e.putBoolean(keyOf(k), s.bools[k] ?: true)
        for (i in LAYOUT_INTS.indices) {
            e.putInt(keyOf(LAYOUT_INTS[i]), s.ints[LAYOUT_INTS[i]] ?: LAYOUT_INT_DEFS[i])
        }
        e.putString(keyOf("title"), s.title.ifEmpty { LAYOUT_TITLE_DEFAULT })
        e.apply()
        onChanged()
    }

    fun saveLayout(name: String) {
        try {
            val bools = LinkedHashMap<String, Boolean>()
            for (k in LAYOUT_BOOLS) bools[k] = prefs.getBoolean(keyOf(k), true)
            val ints = LinkedHashMap<String, Int>()
            for (i in LAYOUT_INTS.indices) {
                ints[LAYOUT_INTS[i]] = prefs.getInt(keyOf(LAYOUT_INTS[i]), LAYOUT_INT_DEFS[i])
            }
            val snapshot = LayoutSnapshot(
                    name = name,
                    bools = bools,
                    ints = ints,
                    title = prefs.getString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
                            ?: LAYOUT_TITLE_DEFAULT
            )
            val out = loadLayouts().filter { it.name != name } + snapshot
            prefs.edit().putString("layouts", LayoutsRepo.serialize(out)).apply()
            currentLayout = name
            onChanged()
            toast(context, "Saved \"$name\"")
        } catch (_: Exception) {
            toast(context, "Save failed")
        }
    }

    fun deleteLayout(name: String) {
        try {
            val out = loadLayouts().filter { it.name != name }
            prefs.edit().putString("layouts", LayoutsRepo.serialize(out)).apply()
            if (name == currentLayout) currentLayout = null
            onChanged()
        } catch (_: Exception) { }
    }

    fun newLayout() {
        val e = prefs.edit()
        for (k in LAYOUT_BOOLS) e.putBoolean(keyOf(k), true)
        for (i in LAYOUT_INTS.indices) e.putInt(keyOf(LAYOUT_INTS[i]), LAYOUT_INT_DEFS[i])
        e.putString(keyOf("title"), LAYOUT_TITLE_DEFAULT)
        e.apply()
        currentLayout = null
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
                                                    currentLayout = s.name
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
