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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
                // TODO(Tasks 5-6: HUD + RAIN sections, keyed off editingLock)
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
