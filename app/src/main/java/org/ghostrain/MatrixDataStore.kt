// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 3 (Task 9) preference repository: one-time `SharedPreferences`
 * `"matrix"` -> DataStore Preferences migration, preserving every
 * key/value.
 *
 * - The DataStore delegate below declares a [SharedPreferencesMigration] for
 *   the same `"matrix"` name, so the first DataStore access copies every
 *   legacy key/value into DataStore exactly once. Migration ordering needs
 *   no coordination: whether the wallpaper engine or Settings touches
 *   DataStore first, the loser sees the already-migrated store.
 * - The engine needs synchronous reads on its frame loop, so the legacy
 *   `"matrix"` file stays the synchronous cache: all readers obtain it
 *   through [prefs] (never `getSharedPreferences` directly), and every
 *   legacy write is mirrored into DataStore by the forwarding listener
 *   installed in [ensureMigrated] (idempotent, IO thread, best-effort).
 *   DataStore is therefore always complete: migrated snapshot plus every
 *   post-migration write.
 * - [migratableEntries] is the pure-JVM model of the preserve-everything
 *   rule (same supported-type filter the mirror applies), covered by
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
     * migration. The DataStore [SharedPreferencesMigration] applies the
     * same rule on device; the mirror in [ensureMigrated] applies it to
     * post-migration writes.
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

    /**
     * Single accessor for the `"matrix"` synchronous cache. All readers
     * (engine, Settings, preview) go through here.
     */
    fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    private val mirrorArmed = AtomicBoolean(false)
    private val io: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Trigger the one-time migration (first DataStore access runs the
     * `SharedPreferencesMigration`; later calls are a cheap no-op read) and
     * install the legacy->DataStore write mirror, once per process. Safe to
     * call from both the engine and Settings in any order; never blocks the
     * caller.
     */
    fun ensureMigrated(context: Context) {
        val app = context.applicationContext
        if (mirrorArmed.compareAndSet(false, true)) {
            prefs(app).registerOnSharedPreferenceChangeListener { sp, key ->
                if (key != null && sp != null) {
                    io.launch { mirrorOne(app, sp, key) }
                }
            }
        }
        io.launch {
            try {
                app.matrixDataStore.data.first()
            } catch (_: Exception) {
                // Migration/read failure must never break the wallpaper or
                // Settings: the synchronous cache keeps serving exact values.
            }
        }
    }

    /** Mirror one legacy write into DataStore, typed by runtime value. */
    private suspend fun mirrorOne(app: Context, sp: SharedPreferences, key: String) {
        try {
            val v = sp.all[key] ?: return // removals: nothing to mirror
            app.matrixDataStore.edit { store ->
                when (v) {
                    is Boolean -> store[booleanPreferencesKey(key)] = v
                    is Int -> store[intPreferencesKey(key)] = v
                    is Long -> store[longPreferencesKey(key)] = v
                    is Float -> store[floatPreferencesKey(key)] = v
                    is String -> store[stringPreferencesKey(key)] = v
                    is Set<*> ->
                        if (v.all { it is String }) {
                            @Suppress("UNCHECKED_CAST")
                            store[stringSetPreferencesKey(key)] = (v as Set<String>).toSet()
                        }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // Best-effort mirror: legacy file remains source of sync truth.
        }
    }
}
