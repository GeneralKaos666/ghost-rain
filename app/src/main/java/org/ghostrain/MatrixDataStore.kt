// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 3 (Task 9) preference repository: DataStore Preferences is the
 * single source of truth, with a one-time `SharedPreferences` `"matrix"`
 * upgrade path preserving every key/value.
 *
 * - The DataStore delegate below declares a [SharedPreferencesMigration] for
 *   the same `"matrix"` name, so the first DataStore access on an upgraded
 *   install copies every legacy key/value into DataStore exactly once
 *   (covered by `DataStoreMigrationTest`, which runs the real migration
 *   against a seeded legacy store). Fresh installs simply start empty.
 * - The wallpaper engine needs synchronous reads, so [startup] performs ONE
 *   blocking `data.first()` (engine `onCreate`, Settings `onCreate` — the
 *   second call is a no-op) and snapshots every key into an in-memory map.
 *   All synchronous readers ([getBoolean]/[getInt]/[getString]/[snapshot])
 *   read that map; all writers update DataStore (async, IO thread) AND the
 *   snapshot synchronously, so readers never see stale values.
 * - Removals and clears go through [remove]/[clear], which update both the
 *   snapshot and DataStore — no divergence possible by construction (the
 *   previous write-mirror dropped removals).
 * - [addOnChangeListener] replaces the old `OnSharedPreferenceChangeListener`
 *   wiring: notified synchronously on the writer's thread with the changed
 *   key (`null` after [clear]); same process, same semantics as before.
 * - [migratableEntries] is the pure-JVM model of the preserve-everything
 *   rule (same supported-type filter the real migration applies), covered by
 *   `PrefsLogicTest`.
 */
private const val STORE_NAME = "matrix"

val Context.matrixDataStore by preferencesDataStore(
        name = STORE_NAME,
        produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, STORE_NAME)) }
)

object MatrixDataStore {

    /**
     * Per-screen "Match system color" HUD toggle default. OFF keeps the
     * wallpaper output pixel-identical to previous builds (classic green
     * text on a dark-green panel); see [hudColors].
     */
    const val HUD_DYNAMIC_DEFAULT = false

    /** Legacy HUD colors, used whenever the dynamic toggle is OFF. */
    const val LEGACY_TEXT_COLOR = 0xFF00FF66.toInt()
    const val LEGACY_PANEL_COLOR = 0xC8000A00.toInt()

    /** Resolved HUD paint colors for one screen. */
    data class HudColors(val text: Int, val panel: Int)

    /**
     * Resolve HUD colors: toggle OFF always returns the legacy pair
     * (byte-identical output), toggle ON returns the Material You palette
     * colors supplied by the caller (engine system-accent lookup, Compose
     * `MaterialTheme.colorScheme` in the preview).
     */
    fun hudColors(dynamic: Boolean, systemText: Int, systemPanel: Int): HudColors =
            if (dynamic) HudColors(systemText, systemPanel)
            else HudColors(LEGACY_TEXT_COLOR, LEGACY_PANEL_COLOR)

    /**
     * Pure-JVM model of the migration rule: keep every entry whose runtime
     * type DataStore Preferences supports (Boolean, Int, Long, Float,
     * String, Set<String>), preserving keys and values verbatim. Anything
     * else (impossible from `SharedPreferences.getAll` in practice, which
     * only holds those types) is dropped rather than crashing the
     * migration.
     */
    fun migratableEntries(all: Map<String, *>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in all) {
            when (v) {
                is Boolean, is Int, is Long, is Float, is String -> out[k] = v
                is Set<*> ->
                    if (v.all { it is String }) {
                        @Suppress("UNCHECKED_CAST")
                        out[k] = (v as Set<String>).toSet()
                    }
                else -> Unit // unsupported type: skip, never fail migration
            }
        }
        return out
    }

    private val lock = Any()
    private val snapshot = LinkedHashMap<String, Any?>()
    @Volatile private var started = false
    private var appCtx: Context? = null
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
    private val io: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Load DataStore (running the one-time legacy migration on upgrade) and
     * snapshot every key for synchronous readers. Blocks the caller once;
     * later calls return immediately. Must precede any read/write.
     */
    fun startup(context: Context) {
        synchronized(lock) { if (started) return }
        val app = context.applicationContext
        val data = try {
            runBlocking { app.matrixDataStore.data.first() }
        } catch (_: Exception) {
            emptyPreferences()
        }
        synchronized(lock) {
            if (!started) {
                appCtx = app
                snapshot.clear()
                for ((k, v) in data.asMap()) snapshot[k.name] = v
                started = true
            }
        }
    }

    /** Defensive copy of the snapshot (e.g. for [RainSettings]). */
    fun snapshot(): Map<String, Any?> = synchronized(lock) { snapshot.toMap() }

    fun getBoolean(key: String, def: Boolean): Boolean =
            synchronized(lock) { snapshot[key] as? Boolean ?: def }

    fun getInt(key: String, def: Int): Int =
            synchronized(lock) { snapshot[key] as? Int ?: def }

    fun getString(key: String, def: String?): String? =
            synchronized(lock) { snapshot[key] as? String ?: def }

    /**
     * Change listener; invoked synchronously on the writer's thread with the
     * changed key (`null` after [clear]). Unregister with
     * [removeOnChangeListener] when the owner is destroyed.
     */
    fun addOnChangeListener(l: (String?) -> Unit) {
        listeners.add(l)
    }

    fun removeOnChangeListener(l: (String?) -> Unit) {
        listeners.remove(l)
    }

    private fun store() = checkNotNull(appCtx) {
        "MatrixDataStore.startup() must be called before any write"
    }.matrixDataStore

    fun putBoolean(key: String, value: Boolean) {
        synchronized(lock) { snapshot[key] = value }
        notifyChanged(key)
        io.launch {
            try {
                store().edit { it[booleanPreferencesKey(key)] = value }
            } catch (_: Exception) { }
        }
    }

    fun putInt(key: String, value: Int) {
        synchronized(lock) { snapshot[key] = value }
        notifyChanged(key)
        io.launch {
            try {
                store().edit { it[intPreferencesKey(key)] = value }
            } catch (_: Exception) { }
        }
    }

    fun putString(key: String, value: String) {
        synchronized(lock) { snapshot[key] = value }
        notifyChanged(key)
        io.launch {
            try {
                store().edit { it[stringPreferencesKey(key)] = value }
            } catch (_: Exception) { }
        }
    }

    /**
     * Remove one key from both the snapshot and DataStore (every typed key
     * variant — removing an absent key is a no-op — since the snapshot does
     * not record which type the stored value had).
     */
    fun remove(key: String) {
        synchronized(lock) { snapshot.remove(key) }
        notifyChanged(key)
        io.launch {
            try {
                store().edit {
                    it.remove(booleanPreferencesKey(key))
                    it.remove(intPreferencesKey(key))
                    it.remove(longPreferencesKey(key))
                    it.remove(floatPreferencesKey(key))
                    it.remove(stringPreferencesKey(key))
                    it.remove(stringSetPreferencesKey(key))
                }
            } catch (_: Exception) { }
        }
    }

    /** Clear everything, snapshot and DataStore alike. */
    fun clear() {
        synchronized(lock) { snapshot.clear() }
        notifyChanged(null)
        io.launch {
            try {
                store().edit { it.clear() }
            } catch (_: Exception) { }
        }
    }

    private fun notifyChanged(key: String?) {
        for (l in listeners) {
            try {
                l(key)
            } catch (_: Exception) { }
        }
    }
}
